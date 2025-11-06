package org.tsicoop.ratings.service.v1;

import org.tsicoop.ratings.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * DashboardService handles fetching summary data for IT Auditor, MSME Owner, and Financial Partner dashboards.
 * The primary function is to route the request based on the user's role.
 */
public class Dashboard implements Action {

    private static final String FUNC_AUDITOR = "get_auditor_dashboard";
    private static final String FUNC_MSME = "get_msme_dashboard";
    private static final String FUNC_PARTNER = "get_partner_dashboard";

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        JSONObject input = null;
        JSONObject output = null;

        try {
            input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            // NOTE: In a real app, userId and role should be extracted securely from the JWT/AuthContext.
            // Placeholder for extracting authenticated user ID and Role.
            Long currentUserId = (Long) input.getOrDefault("userId", 0L); // Assuming ID is passed for MVP testing
            String userRole = (String) input.getOrDefault("role", "UNKNOWN");

            if (currentUserId == 0L) {
                OutputProcessor.errorResponse(res, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "User ID not found in request context.", req.getRequestURI());
                return;
            }

            switch (func.toLowerCase()) {
                case FUNC_AUDITOR:
                    // if (!"IT_AUDITOR".equals(userRole)) throw new SecurityException("Access Denied.");
                    output = getAuditorDashboard(currentUserId);
                    break;
                case FUNC_MSME:
                    // if (!"MSME_OWNER".equals(userRole)) throw new SecurityException("Access Denied.");
                    output = getMsmeDashboard(currentUserId);
                    break;
                case FUNC_PARTNER:
                    // if (!"LENDER".equals(userRole)) throw new SecurityException("Access Denied.");
                    output = getFinancialPartnerDashboard(currentUserId);
                    break;
                default:
                    OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Unknown function: '" + func + "'.", req.getRequestURI());
                    return;
            }

            OutputProcessor.send(res, HttpServletResponse.SC_OK, output);

        } catch (SecurityException e) {
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_FORBIDDEN, "Forbidden", e.getMessage(), req.getRequestURI());
        } catch (SQLException e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Database Error", "A database error occurred: " + e.getMessage(), req.getRequestURI());
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred: " + e.getMessage(), req.getRequestURI());
        }
    }

    /**
     * Retrieves data for the IT Auditor Dashboard.
     */
    private JSONObject getAuditorDashboard(Long auditorId) throws SQLException {
        Connection conn = null;
        PoolDB pool = new PoolDB();
        JSONObject output = new JSONObject();

        // 1. Get Summary Stats
        String sqlSummary = "SELECT COUNT(*) AS total, " +
                "COUNT(*) FILTER (WHERE status = 'PENDING') AS pending_count, " +
                "COUNT(*) FILTER (WHERE status = 'ANCHORED') AS anchored_count " +
                "FROM \"DMA_Assessment\" WHERE \"auditorId\" = ?";

        try (Connection c = pool.getConnection(); PreparedStatement pstmt = c.prepareStatement(sqlSummary)) {
            pstmt.setLong(1, auditorId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    JSONObject summary = new JSONObject();
                    summary.put("totalAssessments", rs.getInt("total"));
                    summary.put("pendingAssignments", rs.getInt("pending_count"));
                    summary.put("anchoredRatings", rs.getInt("anchored_count"));
                    output.put("summary", summary);
                }
            }
        }

        // 2. Get Pending Assessments (Example List)
        JSONArray pendingAssessments = new JSONArray();
        String sqlPending = "SELECT d.\"assessmentId\", m.\"companyName\", m.\"industrySector\", d.\"completionDate\", d.status " +
                "FROM \"DMA_Assessment\" d JOIN \"MSME\" m ON d.\"msmeId\" = m.\"msmeId\" " +
                "WHERE d.\"auditorId\" = ? AND d.status = 'PENDING' ORDER BY d.\"completionDate\" ASC LIMIT 5";

        try (Connection c = pool.getConnection(); PreparedStatement pstmt = c.prepareStatement(sqlPending)) {
            pstmt.setLong(1, auditorId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    JSONObject assessment = new JSONObject();
                    assessment.put("assessmentId", rs.getLong("assessmentId"));
                    assessment.put("msmeName", rs.getString("companyName"));
                    assessment.put("sector", rs.getString("industrySector"));
                    assessment.put("status", rs.getString("status"));
                    assessment.put("requestDate", rs.getTimestamp("completionDate").toInstant().toString());
                    // NOTE: completionDate is used here as the date the assessment was entered/assigned
                    pendingAssessments.add(assessment);
                }
                output.put("pendingList", pendingAssessments);
            }
        }

        output.put("success", true);
        return output;
    }

    /**
     * Retrieves data for the MSME Owner Dashboard.
     */
    private JSONObject getMsmeDashboard(Long msmeId) throws SQLException {
        Connection conn = null;
        PoolDB pool = new PoolDB();
        JSONObject output = new JSONObject();

        // 1. Get MSME Name
        String sqlMsme = "SELECT \"companyName\" FROM \"MSME\" WHERE \"msmeId\" = ?";
        try (Connection c = pool.getConnection(); PreparedStatement pstmt = c.prepareStatement(sqlMsme)) {
            pstmt.setLong(1, msmeId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    output.put("companyName", rs.getString("companyName"));
                } else {
                    throw new SecurityException("MSME record not found for user ID.");
                }
            }
        }

        // 2. Get Latest Assessment (Highest assessmentId)
        String sqlLatest = "SELECT d.\"assessmentId\", d.\"finalTsiScore\", d.status, ar.\"blockchainTxId\", ar.\"anchorDate\" " +
                "FROM \"DMA_Assessment\" d LEFT JOIN \"AnchorRecord\" ar ON d.\"assessmentId\" = ar.\"anchorId\" " +
                "WHERE d.\"msmeId\" = ? ORDER BY d.\"completionDate\" DESC LIMIT 1";

        try (Connection c = pool.getConnection(); PreparedStatement pstmt = c.prepareStatement(sqlLatest)) {
            pstmt.setLong(1, msmeId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    JSONObject currentRating = new JSONObject();
                    currentRating.put("assessmentId", rs.getLong("assessmentId"));
                    currentRating.put("finalTsiScore", rs.getDouble("finalTsiScore"));
                    currentRating.put("status", rs.getString("status"));

                    if (rs.getString("blockchainTxId") != null) {
                        JSONObject anchor = new JSONObject();
                        anchor.put("blockchainTxId", rs.getString("blockchainTxId"));
                        anchor.put("anchorDate", rs.getTimestamp("anchorDate").toInstant().toString());
                        currentRating.put("anchorRecord", anchor);
                    }
                    output.put("currentTsiRating", currentRating);
                }
            }
        }

        // 3. Get Audit History (Last 5 completed assessments)
        JSONArray history = new JSONArray();
        String sqlHistory = "SELECT \"assessmentId\", \"finalTsiScore\", \"completionDate\", status " +
                "FROM \"DMA_Assessment\" WHERE \"msmeId\" = ? AND status != 'PENDING' ORDER BY \"completionDate\" DESC LIMIT 5";

        try (Connection c = pool.getConnection(); PreparedStatement pstmt = c.prepareStatement(sqlHistory)) {
            pstmt.setLong(1, msmeId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    JSONObject item = new JSONObject();
                    item.put("assessmentId", rs.getLong("assessmentId"));
                    item.put("finalTsiScore", rs.getDouble("finalTsiScore"));
                    item.put("completionDate", rs.getTimestamp("completionDate").toInstant().toString());
                    item.put("status", rs.getString("status"));
                    history.add(item);
                }
                output.put("auditHistory", history);
            }
        }

        output.put("success", true);
        return output;
    }

    /**
     * Retrieves data for the Financial Partner (Lender/AIF) Dashboard.
     */
    private JSONObject getFinancialPartnerDashboard(Long partnerId) throws SQLException {
        Connection conn = null;
        PoolDB pool = new PoolDB();
        JSONObject output = new JSONObject();

        // 1. Get Partner Name (from User table)
        String sqlPartnerName = "SELECT email, \"one_liner\" FROM \"User\" WHERE \"userId\" = ?";
        try (Connection c = pool.getConnection(); PreparedStatement pstmt = c.prepareStatement(sqlPartnerName)) {
            pstmt.setLong(1, partnerId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    output.put("partnerName", rs.getString("one_liner") != null ? rs.getString("one_liner") : rs.getString("email"));
                }
            }
        }

        // 2. Get Summary Stats (Overall platform activity relevant to a lender)
        String sqlSummary = "SELECT COUNT(*) AS total_anchored, AVG(\"finalTsiScore\") AS avg_score " +
                "FROM \"DMA_Assessment\" WHERE status = 'ANCHORED'";

        try (Connection c = pool.getConnection(); PreparedStatement pstmt = c.prepareStatement(sqlSummary)) {
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    JSONObject summary = new JSONObject();
                    summary.put("totalAnchoredScores", rs.getInt("total_anchored"));
                    summary.put("platformAvgTsiScore", rs.getDouble("avg_score"));
                    // Simulate a verification count (actual count would come from logs/another table)
                    summary.put("verificationsThisMonth", 55);
                    output.put("summary", summary);
                }
            }
        }

        // 3. Get Top MSME Applications (Top 5 scores, ready for lending)
        JSONArray topApplications = new JSONArray();
        String sqlTop = "SELECT d.\"finalTsiScore\", m.\"companyName\", m.\"industrySector\", ar.\"blockchainTxId\" " +
                "FROM \"DMA_Assessment\" d JOIN \"MSME\" m ON d.\"msmeId\" = m.\"msmeId\" " +
                "LEFT JOIN \"AnchorRecord\" ar ON d.\"assessmentId\" = ar.\"anchorId\" " +
                "WHERE d.status = 'ANCHORED' ORDER BY d.\"finalTsiScore\" DESC LIMIT 5";

        try (Connection c = pool.getConnection(); PreparedStatement pstmt = c.prepareStatement(sqlTop)) {
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    JSONObject app = new JSONObject();
                    app.put("finalTsiScore", rs.getDouble("finalTsiScore"));
                    app.put("msmeName", rs.getString("companyName"));
                    app.put("industry", rs.getString("industrySector"));
                    app.put("blockchainTxId", rs.getString("blockchainTxId"));
                    topApplications.add(app);
                }
                output.put("topApplications", topApplications);
            }
        }

        output.put("success", true);
        return output;
    }

    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        if (!"POST".equalsIgnoreCase(method)) {
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method Not Allowed", "Only POST method is supported.", req.getRequestURI());
            return false;
        }
        return InputProcessor.validate(req, res);
    }
}
