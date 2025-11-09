package org.tsicoop.ratings.service.v1;

import org.json.simple.parser.JSONParser;
import org.tsicoop.ratings.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

/**
 * DMAService class handles all operations related to the Digital Maturity Assessment (DMA).
 * This includes fetching the questionnaire, submitting final scores, and validating blockchain anchors.
 */
public class DMA implements Action {

    // --- Configuration Constants ---
    // NOTE: In a real system, these would be loaded from a secure configuration service.
    private static final String EXPRESS_ANCHOR_API_URL = "http://express-middleware-service/api/v1/blockchain/anchor";
    private static final String EXPRESS_VERIFY_API_URL = "http://express-middleware-service/api/v1/verification/anchor";
    private static final String INTERNAL_SERVICE_TOKEN = "INTERNAL_JWT_SECRET_FOR_EXPRESS_COMMUNICATION"; // Placeholder

    // Mock Questionnaire data (simulating fetching from configuration store)

    /**
     * Handles all DMA operations via a single POST endpoint.
     */
    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        JSONObject input = null;
        JSONObject output = null;

        try {
            input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Missing required '_func'.", req.getRequestURI());
                return;
            }

            // Authentication context check (Example: Ensure user is logged in)
            // AuthContext authContext = (AuthContext) req.getAttribute("authContext");
            // if (authContext == null) {
            //     OutputProcessor.errorResponse(res, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "User not authenticated.", req.getRequestURI());
            //     return;
            // }

            switch (func.toLowerCase()) {
                case "get_dma_questionnaire":
                    output = getQuestionnaire();
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;
                case "submit_assessment":
                    // Authorization: Must be an IT_AUDITOR
                    // if (!"IT_AUDITOR".equals(authContext.getRole())) {
                    //    OutputProcessor.errorResponse(res, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "Only IT Auditors can submit assessments.", req.getRequestURI());
                    //    return;
                    // }
                    output = submitAssessment(input);
                    OutputProcessor.send(res, HttpServletResponse.SC_ACCEPTED, output);
                    break;
                case "get_dma_assessment_details":
                    Long assessmentId = Long.parseLong(input.get("assessmentId").toString());
                    output = getAssessmentDetails(assessmentId);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;
                case "validate_dma_assessment":
                    output = validateAssessment(input);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;
                default:
                    OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Unknown function: '" + func + "'.", req.getRequestURI());
                    break;
            }

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred: " + e.getMessage(), req.getRequestURI());
        }
    }

    /**
     * Retrieves the latest DMA questionnaire structure.
     */
    private JSONObject getQuestionnaire() throws ParseException {
        JSONObject assessment = new JSONObject();
        assessment = SystemConfig.readJSONTemplate("/WEB-INF/assessments/"+"dma"+"-"+"v1"+".json");
        return assessment;
    }

    /**
     * Saves the assessment details, calculates score, and triggers blockchain anchoring.
     */
    private JSONObject submitAssessment(JSONObject input) throws SQLException, ParseException, Exception {
        Long msmeId = Long.parseLong(input.get("msmeId").toString());
        Long auditorId = Long.parseLong(input.get("auditorId").toString()); // Auditor is the authenticated user
        double finalTsiScore = Double.parseDouble(input.get("finalTsiScore").toString());
        String version = (String) input.get("version");

        // Ensure JSON objects are valid before saving
        JSONObject assessmentDetailJson = (JSONObject) input.get("assessmentDetailJson");
        JSONObject requestFormJson = (JSONObject) input.get("requestFormJson");

        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();
        JSONObject output = new JSONObject();
        Long newAssessmentId = null;

        // 1. Save Assessment to DMA_Assessment Table
        String sql = "INSERT INTO \"DMA_Assessment\" (\"msmeId\", \"auditorId\", \"finalTsiScore\", \"completionDate\", \"status\", \"requestFormJson\", \"assessmentDetailJson\") " +
                "VALUES (?, ?, ?, NOW(), 'AUDITED', ?::jsonb, ?::jsonb) RETURNING \"assessmentId\"";

        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            pstmt.setLong(1, msmeId);
            pstmt.setLong(2, auditorId);
            pstmt.setDouble(3, finalTsiScore);
            pstmt.setString(4, requestFormJson.toJSONString());
            pstmt.setString(5, assessmentDetailJson.toJSONString());

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows == 0) {
                throw new SQLException("Submitting assessment failed, no rows affected.");
            }

            rs = pstmt.getGeneratedKeys();
            if (rs.next()) {
                newAssessmentId = rs.getLong(1);
            } else {
                throw new SQLException("Submitting assessment failed, no ID obtained.");
            }

            conn.commit(); // Commit the database save

            // 2. Prepare data for ExpressJS Blockchain Anchor API
            JSONObject anchorData = new JSONObject();
            anchorData.put("assessmentId", newAssessmentId);
            anchorData.put("msmeId", msmeId.toString());
            anchorData.put("auditorId", auditorId.toString());
            anchorData.put("finalScore", finalTsiScore);
            anchorData.put("assessmentDate", new Timestamp(System.currentTimeMillis()).toInstant().toString());
            anchorData.put("type", "DMA");
            anchorData.put("version", version);

            // 3. Call ExpressJS Service to Anchor Hash
            JSONObject expressResponse = callExpressService(EXPRESS_ANCHOR_API_URL, anchorData);

            // 4. Update AnchorRecord table based on ExpressJS response
            if ("success".equals(expressResponse.get("status"))) {
                String txId = (String) expressResponse.get("blockchainTxId");
                String tsiHash = (String) expressResponse.get("tsiHash");
                updateAnchorRecord(newAssessmentId, txId, tsiHash, "DMA");

                output.put("assessmentId", newAssessmentId);
                output.put("message", "Assessment saved and successfully anchored to blockchain.");
                output.put("blockchainTxId", txId);
            } else {
                // Anchoring failed, log and update status to 'AUDITED' but not 'ANCHORED'
                System.err.println("Blockchain anchoring failed for assessment ID " + newAssessmentId + ": " + expressResponse.get("message"));
                output.put("assessmentId", newAssessmentId);
                output.put("message", "Assessment saved, but blockchain anchoring failed. Status remains 'AUDITED'.");
                // Note: The status is already 'AUDITED', no explicit rollback needed, just logging the failure.
            }

        } catch (SQLException e) {
            if (conn != null) conn.rollback();
            throw e;
        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
        return new JSONObject() {{ put("success", true); put("data", output); }};
    }

    /**
     * Retrieves the details of a single assessment, including the AnchorRecord data.
     */
    private JSONObject getAssessmentDetails(Long assessmentId) throws SQLException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();
        JSONObject result = new JSONObject();

        String sql = "SELECT dma.\"assessmentId\", dma.\"finalTsiScore\", dma.status, dma.\"completionDate\", " +
                "ar.\"blockchainTxId\", ar.\"tsiHash\", ar.\"anchorDate\", " +
                "u.email AS auditor_email, m.companyName AS msme_name " +
                "FROM \"DMA_Assessment\" dma " +
                "LEFT JOIN \"AnchorRecord\" ar ON dma.\"assessmentId\" = ar.\"anchorId\" " +
                "JOIN \"User\" u ON dma.\"auditorId\" = u.\"userId\" " +
                "JOIN \"MSME\" m ON dma.\"msmeId\" = m.\"msmeId\" " +
                "WHERE dma.\"assessmentId\" = ?";

        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, assessmentId);
            rs = pstmt.executeQuery();

            if (rs.next()) {
                result.put("assessmentId", rs.getLong("assessmentId"));
                result.put("msmeName", rs.getString("msme_name"));
                result.put("auditorEmail", rs.getString("auditor_email"));
                result.put("finalTsiScore", rs.getDouble("finalTsiScore"));
                result.put("status", rs.getString("status"));
                result.put("completionDate", rs.getTimestamp("completionDate").toInstant().toString());

                if (rs.getString("blockchainTxId") != null) {
                    JSONObject anchor = new JSONObject();
                    anchor.put("blockchainTxId", rs.getString("blockchainTxId"));
                    anchor.put("tsiHash", rs.getString("tsiHash"));
                    anchor.put("anchorDate", rs.getTimestamp("anchorDate").toInstant().toString());
                    result.put("anchorRecord", anchor);
                }
            } else {
                throw new SQLException("Assessment not found for ID: " + assessmentId);
            }

        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
        return new JSONObject() {{ put("success", true); put("data", result); }};
    }

    /**
     * Calls ExpressJS Verification API to check hash integrity against the blockchain.
     */
    private JSONObject validateAssessment(JSONObject input) throws Exception {
        // This input should mirror the fields expected by the ExpressJS middleware verification endpoint
        // Example fields: blockchainTxId, msmeId, finalScore, assessmentDate, version, etc.

        // Pass the input data directly to the external Express service
        JSONObject verificationRequest = new JSONObject();
        verificationRequest.put("blockchainTxId", input.get("blockchainTxId"));
        verificationRequest.put("msmeId", input.get("msmeId"));
        verificationRequest.put("auditorId", input.get("auditorId"));
        verificationRequest.put("finalScore", input.get("finalScore"));
        verificationRequest.put("assessmentDate", input.get("assessmentDate"));
        verificationRequest.put("version", input.get("version"));

        // Call ExpressJS Service to Validate Hash
        JSONObject expressResponse = callExpressService(EXPRESS_VERIFY_API_URL, verificationRequest);

        if ("MATCH".equals(expressResponse.get("verification_result"))) {
            // Update local DB status if necessary (e.g., if status was 'AUDITED' but not 'ANCHORED')
            // This is primarily a verification check, so local update isn't mandatory here.
            return expressResponse;
        } else {
            return expressResponse; // Return mismatch/tampering error
        }
    }


    /**
     * [HELPER] Inserts the blockchain anchor proof into the AnchorRecord table and updates DMA_Assessment status.
     */
    private void updateAnchorRecord(Long assessmentId, String txId, String tsiHash, String type) throws SQLException {
        Connection conn = null;
        PreparedStatement pstmtAnchor = null;
        PreparedStatement pstmtUpdate = null;
        PoolDB pool = new PoolDB();

        // 1. Insert into AnchorRecord
        String sqlAnchor = "INSERT INTO \"AnchorRecord\" (\"anchorId\", \"type\", \"blockchainTxId\", \"tsiHash\", \"anchorDate\", \"blockchainNetwork\") VALUES (?, ?, ?, ?, NOW(), 'BSV')";
        // 2. Update DMA_Assessment status
        String sqlUpdate = "UPDATE \"DMA_Assessment\" SET status = 'ANCHORED' WHERE \"assessmentId\" = ?";

        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            pstmtAnchor = conn.prepareStatement(sqlAnchor);
            pstmtAnchor.setLong(1, assessmentId);
            pstmtAnchor.setString(2, type);
            pstmtAnchor.setString(3, txId);
            pstmtAnchor.setString(4, tsiHash);
            pstmtAnchor.executeUpdate();

            pstmtUpdate = conn.prepareStatement(sqlUpdate);
            pstmtUpdate.setLong(1, assessmentId);
            pstmtUpdate.executeUpdate();

            conn.commit();
        } catch (SQLException e) {
            if (conn != null) conn.rollback();
            throw e;
        } finally {
            pool.cleanup(null, pstmtAnchor, null);
            pool.cleanup(null, pstmtUpdate, conn);
        }
    }

    /**
     * [HELPER] Executes a POST request to the external ExpressJS service.
     */
    private JSONObject callExpressService(String urlString, JSONObject payload) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + INTERNAL_SERVICE_TOKEN); // Internal Auth
        conn.setDoOutput(true);

        // Send request payload
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = payload.toJSONString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        // Read response
        int responseCode = conn.getResponseCode();
        JSONObject responseJson = new JSONObject();
        JSONParser parser = new JSONParser();

        if (responseCode >= 200 && responseCode < 300) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                responseJson = (JSONObject) parser.parse(br);
            }
        } else {
            // Read error stream for detailed failure message
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                responseJson = (JSONObject) parser.parse(br);
            } catch (Exception e) {
                responseJson.put("status", "error");
                responseJson.put("message", "External service failed with HTTP code " + responseCode);
            }
        }
        return responseJson;
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
