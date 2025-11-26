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
import java.util.UUID; // Used for simulating blockchain TX IDs

/**
 * DMAService class handles all operations related to the Digital Maturity Assessment (DMA).
 * This includes fetching the questionnaire, saving progress, finalizing assessment, and validating blockchain anchors.
 */
public class DMA implements Action {

    // --- Configuration Constants ---
    private static final String EXPRESS_ANCHOR_API_URL = "http://express-middleware-service/api/v1/blockchain/anchor";
    private static final String EXPRESS_VERIFY_API_URL = "http://express-middleware-service/api/v1/verification/anchor";
    private static final String INTERNAL_SERVICE_TOKEN = "INTERNAL_JWT_SECRET_FOR_EXPRESS_COMMUNICATION"; // Placeholder
    private static final String API_URL = "/api/dma";

    /**
     * Handles all DMA operations via a single POST endpoint.
     */
    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        JSONObject input = null;
        JSONObject output = null;

        // NOTE: Authentication context check and AuditorId retrieval is critical here.
        // Long auditorId = getAuditorIdFromAuthContext(req);
        Long auditorId = 8L; // Hardcoded Placeholder ID for demonstration

        try {
            input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");
            String txId = (String) input.get("txId");
            String tsiHash = (String) input.get("tsiHash");

            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Missing required '_func'.", req.getRequestURI());
                return;
            }

            // Extract Assessment ID (required for Save and Finalize)
            Long assessmentId = null;
            Object assessmentIdObj = input.get("assessmentId");
            if (assessmentIdObj != null && !assessmentIdObj.equals("New")) {
                try {
                    assessmentId = Long.parseLong(assessmentIdObj.toString());
                } catch (NumberFormatException e) {
                    OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Invalid 'assessmentId' format.", req.getRequestURI());
                    return;
                }
            }

            switch (func.toLowerCase()) {
                case "get_dma_questionnaire":
                    output = getQuestionnaire();
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;

                case "save_assessment":
                    // Auditor saves progress, score, and qualitative input.
                    output = saveAssessment(assessmentId, auditorId, input);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output); // Use SC_OK for save/update
                    break;

                case "finalize_assessment":
                    // Auditor confirms final submission

                    output =  updateAnchorRecord(assessmentId, txId, tsiHash, "DMA");
                    OutputProcessor.send(res, HttpServletResponse.SC_ACCEPTED, output);
                    break;

                case "get_assessment_list":
                    output = getAssessmentList(input);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;

                case "get_dma_assessment_details":
                    output = getAssessmentDetails(assessmentId);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;

                case "validate_assessment":
                    output = validateAssessment(txId, tsiHash);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;

                case "submit_assessment": // Original function is renamed/replaced
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
     * Retrieves a list of all Digital Maturity Assessments (DMA) assigned to a specific auditor.
     * The list includes relevant details from MSME and AnchorRecord tables.
     * * @param input JSON object containing "auditorId".
     * @return JSONObject containing success status and an array of assessment records.
     */
    private JSONObject getAssessmentList(JSONObject input) throws SQLException {
        Long auditorId = null;
        Object auditorIdObj = input.get("auditorId");

        if (auditorIdObj == null) {
            JSONObject error = new JSONObject();
            error.put("error", true);
            error.put("status_code", (long) HttpServletResponse.SC_BAD_REQUEST);
            error.put("error_message", "Auditor ID is required to fetch the assessment list.");
            return error;
        }

        try {
            // Ensure auditorId is parsed correctly (handling String or Long input)
            auditorId = Long.parseLong(auditorIdObj.toString());
        } catch (NumberFormatException e) {
            JSONObject error = new JSONObject();
            error.put("error", true);
            error.put("status_code", (long) HttpServletResponse.SC_BAD_REQUEST);
            error.put("error_message", "Invalid format for auditorId.");
            return error;
        }

        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();

        JSONArray assessmentArray = new JSONArray();
        JSONObject output = new JSONObject();

        // SQL Query to join DMA_Assessment with MSME (to get company name)
        // and LEFT JOIN AnchorRecord (to see if it's been anchored)
        String sql = "SELECT dma.\"assessmentId\", dma.\"msmeId\", dma.\"finalTsiScore\", dma.status, dma.\"completionDate\", " +
                "m.\"companyName\", " +
                "ar.\"blockchainTxId\" " +
                "FROM \"DMA_Assessment\" dma " +
                "JOIN \"MSME\" m ON dma.\"msmeId\" = m.\"msmeId\" " +
                "LEFT JOIN \"AnchorRecord\" ar ON dma.\"assessmentId\" = ar.\"anchorId\" " +
                "WHERE dma.\"auditorId\" = ?";

        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, auditorId);
            rs = pstmt.executeQuery();

            while (rs.next()) {
                JSONObject assessment = new JSONObject();

                // Fields from DMA_Assessment and MSME
                assessment.put("assessmentId", rs.getLong("assessmentId"));
                assessment.put("msmeId", rs.getLong("msmeId"));
                assessment.put("companyName", rs.getString("companyName"));
                assessment.put("status", rs.getString("status"));

                // Conditional Fields
                assessment.put("finalTsiScore", rs.getObject("finalTsiScore") != null ? rs.getDouble("finalTsiScore") : null);
                assessment.put("completionDate", rs.getTimestamp("completionDate") != null ? rs.getTimestamp("completionDate").toInstant().toString() : null);

                // Anchor Status (from LEFT JOIN)
                assessment.put("isAnchored", rs.getString("blockchainTxId") != null);

                assessmentArray.add(assessment);
            }

            output.put("success", true);
            output.put("data", assessmentArray);

        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
        return output;
    }

    // ------------------------------------------
    // New Functionality: Save and Finalize
    // ------------------------------------------

    /**
     * Saves the assessment progress, including score, answers, and qualitative input.
     * Status remains 'PENDING'.
     * @return Result JSON with the latest saved score.
     */
    private JSONObject saveAssessment(Long assessmentId, Long auditorId, JSONObject input) throws SQLException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();
        JSONObject result = new JSONObject();

        // --- 1. Extract and Validate Required Data ---
        // finalTsiScore is calculated in the backend, but we need a default/placeholder value for the DB column
        Double finalTsiScore = 0.0;
        String qualitativeNotes = (String) input.get("qualitativeNotes");
        JSONObject assessmentDetailJson = (JSONObject) input.get("assessmentDetailJson");
        Long msmeId = (Long) input.get("msmeId"); // Required for INSERT

        if (assessmentDetailJson == null) {
            result.put("error", true);
            result.put("status_code", (long) HttpServletResponse.SC_BAD_REQUEST);
            result.put("error_message", "Incomplete Data - assessmentDetailJson is required.");
            return result;
        }

        // 2. Augment assessmentDetailJson with qualitative notes and save timestamp
        if (assessmentDetailJson != null) {
            assessmentDetailJson.put("auditorNotes", qualitativeNotes != null ? qualitativeNotes : "");
            assessmentDetailJson.put("lastSavedAt", new Timestamp(System.currentTimeMillis()).toString());
        }

        String jsonDetail = assessmentDetailJson.toJSONString();

        String sql = "";
        boolean isNewAssessment = (assessmentId == null || assessmentId.longValue() == 0L);

        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            if (isNewAssessment) {
                // --- INSERT LOGIC (New Assessment) ---
                if (msmeId == null) {
                    throw new IllegalArgumentException("msmeId is required to create a new assessment.");
                }

                sql = "INSERT INTO \"DMA_Assessment\" (\"msmeId\", \"auditorId\", \"finalTsiScore\", \"status\", \"assessmentDetailJson\", \"completionDate\") " +
                        "VALUES (?, ?, ?, 'PENDING', ?::jsonb, NOW()) RETURNING \"assessmentId\"";

                pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                pstmt.setLong(1, msmeId);
                pstmt.setLong(2, auditorId);
                pstmt.setDouble(3, finalTsiScore);
                pstmt.setString(4, jsonDetail);

            } else {
                // --- UPDATE LOGIC (Existing Assessment) ---
                // Only allow update if the assessment is PENDING and assigned to the correct auditor
                sql = "UPDATE \"DMA_Assessment\" SET \"finalTsiScore\" = ?, \"assessmentDetailJson\" = ?::jsonb, \"completionDate\" = NOW() " +
                        "WHERE \"assessmentId\" = ? AND \"auditorId\" = ? AND \"status\" = 'PENDING'";

                pstmt = conn.prepareStatement(sql);
                pstmt.setDouble(1, finalTsiScore);
                pstmt.setString(2, jsonDetail);
                pstmt.setLong(3, assessmentId);
                pstmt.setLong(4, auditorId);
            }

            int affectedRows = pstmt.executeUpdate();
            Long returnedAssessmentId = assessmentId;

            if (isNewAssessment) {
                // Retrieve the generated ID for a new insert
                rs = pstmt.getGeneratedKeys();
                if (rs.next()) {
                    returnedAssessmentId = rs.getLong(1);
                } else {
                    throw new SQLException("Creating assessment failed, no ID obtained.");
                }
            } else if (affectedRows == 0) {
                // Error if updating, meaning the assessment wasn't found or wasn't PENDING
                throw new SQLException("Assessment update failed: Record not found, not assigned to auditor, or already processed.");
            }

            conn.commit(); // Commit the transaction

            result.put("success", true);
            result.put("message", isNewAssessment ? "New assessment created successfully." : "Assessment progress saved successfully.");
            result.put("assessmentId", returnedAssessmentId);
            result.put("finalTsiScore", finalTsiScore);

        } catch (IllegalArgumentException e) {
            if (conn != null) conn.rollback();
            result.put("error", true);
            result.put("status_code", (long) HttpServletResponse.SC_BAD_REQUEST);
            result.put("error_message", e.getMessage());
            return result;
        } catch (SQLException e) {
            if (conn != null) conn.rollback();
            throw e;
        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
        return new JSONObject() {{ put("success", true); put("data", result); }};
    }

    // ------------------------------------------
    // Existing/Helper Functions (Moved/Simplified)
    // ------------------------------------------

    /**
     * Retrieves the latest DMA questionnaire structure.
     */
    private JSONObject getQuestionnaire() throws ParseException {
        // Renamed from getQuestionnaireTemplate
        JSONObject assessment = SystemConfig.readJSONTemplate("/WEB-INF/assessments/"+"dma"+"-"+"v1"+".json");
        return new JSONObject() {{ put("success", true); put("data", assessment); }};
    }

    /**
     * [HELPER] Inserts the blockchain anchor proof into the AnchorRecord table and updates DMA_Assessment status.
     */
    private JSONObject updateAnchorRecord(Long assessmentId, String txId, String tsiHash, String type) throws SQLException {
        // ... (implementation remains the same as provided in the previous turn)
        Connection conn = null;
        PreparedStatement pstmtAnchor = null;
        PreparedStatement pstmtUpdate = null;
        PoolDB pool = new PoolDB();

        // 1. Insert into AnchorRecord
        String sqlAnchor = "INSERT INTO \"AnchorRecord\" (\"anchorId\", \"type\", \"blockchainTxId\", \"tsiHash\", \"anchorDate\", \"blockchainNetwork\") VALUES (?, ?, ?, ?, NOW(), 'TSI-Ledger')";
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
        return new JSONObject() {{ put("success", true);}};
    }

    /**
     * Helper to safely get a Double from a JSONObject.
     */
    private Double getDoubleOrNull(Object obj) {
        if (obj == null) return null;
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Retrieves the comprehensive details of a single DMA assessment.
     * The list includes data from the DMA_Assessment table, the MSME (company name),
     * the User (auditor email), and the AnchorRecord (blockchain proof) if available.
     */
    private JSONObject getAssessmentDetails(Long assessmentId) throws Exception {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();
        JSONObject result = new JSONObject();

        if (assessmentId == null || assessmentId.longValue() <= 0) {
            result.put("error", true);
            result.put("status_code", (long) HttpServletResponse.SC_BAD_REQUEST);
            result.put("error_message", "Invalid Assessment ID provided.");
            return result;
        }

        // SQL Query to join DMA_Assessment with MSME, User (Auditor), and LEFT JOIN AnchorRecord
        String sql = "SELECT dma.\"assessmentId\", dma.\"msmeId\", dma.\"finalTsiScore\", dma.status, dma.\"completionDate\", dma.\"requestFormJson\", dma.\"assessmentDetailJson\", " +
                "ar.\"blockchainTxId\", ar.\"tsiHash\", ar.\"anchorDate\", " +
                "u.email AS auditor_email, m.\"companyName\" AS msme_name, m.\"udyamRegistrationNo\" " +
                "FROM \"DMA_Assessment\" dma " +
                "JOIN \"User\" u ON dma.\"auditorId\" = u.\"userId\" " +
                "JOIN \"MSME\" m ON dma.\"msmeId\" = m.\"msmeId\" " +
                "LEFT JOIN \"AnchorRecord\" ar ON dma.\"assessmentId\" = ar.\"anchorId\" " +
                "WHERE dma.\"assessmentId\" = ?";

        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, assessmentId);
            rs = pstmt.executeQuery();

            if (rs.next()) {
                double score = rs.getObject("finalTsiScore") != null ? rs.getDouble("finalTsiScore") : 0.0;
                String completionDate = rs.getTimestamp("completionDate") != null ? rs.getTimestamp("completionDate").toInstant().toString() : null;

                // --- Core Assessment Data ---
                result.put("assessmentId", rs.getLong("assessmentId"));
                result.put("msmeId", rs.getLong("msmeId"));
                result.put("finalTsiScore", score );
                result.put("status", rs.getString("status"));
                result.put("completionDate", completionDate);

                // --- MSME & Auditor Data ---
                result.put("msmeName", rs.getString("msme_name"));
                result.put("udyamRegistrationNo", rs.getString("udyamRegistrationNo"));
                result.put("auditorEmail", rs.getString("auditor_email"));

                // --- Assessment Payload Data (JSONB) ---
                String assessmentDetailJsonString = rs.getString("assessmentDetailJson");
                if (assessmentDetailJsonString != null) {
                     result.put("assessmentDetailJson", new JSONParser().parse(assessmentDetailJsonString));
                    //result.put("assessmentDetailJson", assessmentDetailJsonString);
                }

                // --- Anchor Record Data (Proof of Immutability) ---
                if (rs.getString("blockchainTxId") != null) {
                    JSONObject anchor = new JSONObject();
                    result.put("blockchainTxId", rs.getString("blockchainTxId"));
                    result.put("tsiHash", rs.getString("tsiHash"));
                    result.put("anchorDate", rs.getTimestamp("anchorDate").toInstant().toString());
                }else{
                    result.put("tsiHash", generateAssessmentString( assessmentId,
                                                                    score,
                                                                    completionDate));
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
     * Generates a concatenated string from MSME assessment data.
     */
    private static String generateAssessmentString(
            Object assessmentId,
            Object finalScore,
            Object assessmentDate) {

        final String DELIMITER = "|";

        return String.valueOf(assessmentId)
                + DELIMITER
                + String.valueOf(finalScore)
                + DELIMITER
                + String.valueOf(assessmentDate);
    }

    private JSONObject validateAssessment(String txId,String tsiHash) throws Exception {
        JSONObject result =  new JSONObject();
        try {
            result = new BSVUtil().validateAssessment(txId, tsiHash);
        }catch(Exception e){
            result.put("failed",true);
        }
        return result;
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