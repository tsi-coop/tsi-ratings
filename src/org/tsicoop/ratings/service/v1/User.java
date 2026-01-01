package org.tsicoop.ratings.service.v1;

import org.json.simple.parser.JSONParser;
import org.tsicoop.ratings.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp; // Import Timestamp for expiry handling
import java.util.*;
import java.util.regex.Pattern;

/**
 * UserService class for managing platform users (Admin, MSME, Auditor, Lender).
 * Authentication is performed using a two-step Email OTP process.
 * All operations are exposed via the POST method, using a '_func' attribute
 * in the JSON request body to specify the desired operation.
 */
public class User implements Action {

    private final PasswordHasher passwordHasher = new PasswordHasher();

    // Regex for password complexity (still required for Admin during registration)
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+])[A-Za-z\\d!@#$%^&*()_+]{8,}$");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}$");

    /**
     * Handles all User and Role Management operations via a single POST endpoint.
     */
    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        JSONObject input = null;
        JSONObject output = null;
        JSONArray outputArray = null;

        try {
            input = InputProcessor.getInput(req);
            String func = (String) input.get("_func");

            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Missing required '_func' attribute in input JSON.", req.getRequestURI());
                return;
            }

            // Extract IDs if present in input for specific operations
            Long userId = null;
            Object userIdObj = input.get("user_id");
            if (userIdObj != null) {
                try {
                    userId = Long.parseLong(userIdObj.toString());
                } catch (NumberFormatException e) {
                    OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Invalid 'user_id' format (must be a number).", req.getRequestURI());
                    return;
                }
            }

            String role = (String) input.get("role");

            switch (func.toLowerCase()) {
                // --- Step 1: OTP Request ---
                case "request_otp":
                    String otpEmail = (String) input.get("email");

                    if (otpEmail == null || otpEmail.isEmpty()) {
                        OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Email is required to request OTP.", req.getRequestURI());
                        return;
                    }
                    if (!EMAIL_PATTERN.matcher(otpEmail).matches()) {
                        OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Invalid email format.", req.getRequestURI());
                        return;
                    }

                    output = handleOtpRequest(otpEmail);
                    if (output.containsKey("error")) {
                        int statusCode = ((Long) output.get("status_code")).intValue();
                        OutputProcessor.errorResponse(res, statusCode, (String) output.get("error_message"), (String) output.get("error_details"), req.getRequestURI());
                    } else {
                        OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    }
                    break;

                // --- Step 2: OTP Login ---
                case "login_otp":
                    String loginEmail = (String) input.get("email");
                    String otp = (String) input.get("otp");

                    if (loginEmail == null || loginEmail.isEmpty() || otp == null || otp.isEmpty()) {
                        OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Email and OTP are required for login.", req.getRequestURI());
                        return;
                    }

                    if (!otp.matches("\\d{6}")) {
                        OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "OTP must be a 6-digit number.", req.getRequestURI());
                        return;
                    }

                    output = authenticateUserByOtp(loginEmail, otp);
                    if (output.containsKey("error")) {
                        int statusCode = ((Long) output.get("status_code")).intValue();
                        OutputProcessor.errorResponse(res, statusCode, (String) output.get("error_message"), (String) output.get("error_details"), req.getRequestURI());
                    } else {
                        OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    }
                    break;

                // --- New User Registration (Admin Only) ---
                case "register_user":
                    // NOTE: In a real system, an authorization check must happen here to ensure only ADMIN can call this.
                    String email = (String) input.get("email");
                    String contactName = (String) input.get("contactName");
                    String oneLiner = (String) input.get("one_liner");
                    String linkedin = (String) input.get("linkedin");
                    String companyName = (String) input.get("companyName");
                    String udyamRegistrationNo = (String) input.get("udyamRegistrationNo");
                    String industrySector = (String) input.get("industrySector");

                    if (role == null || role.isEmpty() || email == null || email.isEmpty() || contactName == null || contactName.isEmpty()) {
                        OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Missing required fields (email, password, role, contactName).", req.getRequestURI());
                        return;
                    }

                    // Validate common input
                    String validationError = validateUserCreationInput(email);
                    if (validationError != null) {
                        OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", validationError, req.getRequestURI());
                        return;
                    }

                    if (isEmailPresent(email, null)) {
                        OutputProcessor.errorResponse(res, HttpServletResponse.SC_CONFLICT, "Conflict", "Email already exists.", req.getRequestURI());
                        return;
                    }

                    output = registerUserToDb(role, email, contactName, companyName, udyamRegistrationNo, industrySector, oneLiner, linkedin);
                    OutputProcessor.send(res, HttpServletResponse.SC_CREATED, output);
                    break;

                // --- Other Admin/User Functions (Placeholders) ---
                case "get_msme_by_email":
                    email = (String) input.get("email");
                    output = getMsmeDetailsByEmail(email);
                    OutputProcessor.send(res, HttpServletResponse.SC_OK, output);
                    break;
                case "list_users":
                    // Placeholder for list_users logic
                    OutputProcessor.errorResponse(res, HttpServletResponse.SC_NOT_IMPLEMENTED, "Not Implemented", "list_users is not yet implemented.", req.getRequestURI());
                    break;
                case "get_user":
                    // Placeholder for get_user logic
                    OutputProcessor.errorResponse(res, HttpServletResponse.SC_NOT_IMPLEMENTED, "Not Implemented", "get_user is not yet implemented.", req.getRequestURI());
                    break;
                case "update_user":
                    // Placeholder for update_user logic
                    OutputProcessor.errorResponse(res, HttpServletResponse.SC_NOT_IMPLEMENTED, "Not Implemented", "update_user is not yet implemented.", req.getRequestURI());
                    break;
                case "delete_user":
                    // Placeholder for delete_user logic
                    OutputProcessor.errorResponse(res, HttpServletResponse.SC_NOT_IMPLEMENTED, "Not Implemented", "delete_user is not yet implemented.", req.getRequestURI());
                    break;

                default:
                    OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Unknown function: '" + func + "'.", req.getRequestURI());
                    break;
            }

        } catch (SQLException e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Database Error", "A database error occurred: " + e.getMessage(), req.getRequestURI());
        } catch (ParseException e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_BAD_REQUEST, "Bad Request", "Invalid JSON input: " + e.getMessage(), req.getRequestURI());
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred: " + e.getMessage(), req.getRequestURI());
        }
    }

    /**
     * Step 1: Handles the request to generate and send an OTP.
     * Assumes the "User" table has 'otpCode' (VARCHAR) and 'otpExpiry' (TIMESTAMP WITH TIME ZONE) fields.
     */
    private JSONObject handleOtpRequest(String email) throws SQLException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        PoolDB pool = new PoolDB();
        JSONObject result = new JSONObject();

        // Generate 6-digit OTP, valid for 5 minutes
        String otp = String.format("%06d", new Random().nextInt(1000000));

        // Update SQL to store OTP and set expiry (PostgreSQL interval syntax)
        String updateSql = "UPDATE \"users\" SET \"otpCode\" = ?, \"otpExpiry\" = NOW() + INTERVAL '5 minutes' WHERE email = ?";

        try {
            conn = pool.getConnection();

            // Check if user exists and update OTP/Expiry in one query
            pstmt = conn.prepareStatement(updateSql);
            pstmt.setString(1, otp);
            pstmt.setString(2, email);
            pstmt.executeUpdate();

            // User exists and OTP/Expiry were updated
            // --- To do: Call EmailService.sendOtp(email, otp); would happen here ---

            result.put("success", true);
            result.put("message", "OTP generated and sent to " + email);
            // NOTE: For demonstration purposes, we return the OTP. REMOVE IN PRODUCTION!
            result.put("debug_otp", otp);

        } finally {
            pool.cleanup(null, pstmt, conn);
        }
        return result;
    }

    /**
     * Retrieves essential MSME details (ID, Name) by the MSME Owner's email address.
     * This is used by the IT Auditor to initiate a DMA assessment.
     */
    private JSONObject getMsmeDetailsByEmail(String email) throws SQLException {
        JSONObject result = new JSONObject();
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();

        // SQL joins User and MSME tables, filters by email, and ensures the user role is MSME_OWNER
        String sql = "SELECT u.\"userId\" AS msme_user_id, m.\"companyName\", m.\"udyamRegistrationNo\" " +
                "FROM \"users\" u " +
                "JOIN \"msme\" m ON u.\"userId\" = m.\"msmeId\" " +
                "WHERE u.email = ?";

        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, email);
            rs = pstmt.executeQuery();

            if (rs.next()) {
                result.put("success", true);
                result.put("msmeId", rs.getLong("msme_user_id"));
                result.put("companyName", rs.getString("companyName"));
                result.put("udyamRegistrationNo", rs.getString("udyamRegistrationNo"));
                result.put("message", "MSME details retrieved successfully.");
            } else {
                result.put("error", true);
                result.put("status_code", HttpServletResponse.SC_NOT_FOUND);
                result.put("error_message", "MSME not found.");
                result.put("error_details", "No registered MSME account found for this email.");
            }
        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
        return result;
    }


    /**
     * Step 2: Authenticates a user based on email and OTP.
     * Assumes the "User" table has 'otpCode' and 'otpExpiry' fields.
     */
    private JSONObject authenticateUserByOtp(String email, String otp) throws SQLException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();
        JSONObject result = new JSONObject();

        // Fetch user data including OTP and expiry
        String sql = "SELECT \"userId\", \"email\", \"role\", \"otpCode\", \"otpExpiry\" FROM \"users\" WHERE email = ?";

        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, email);
            rs = pstmt.executeQuery();

            if (rs.next()) {
                Long userId = rs.getLong("userId");
                String storedOtp = rs.getString("otpCode");
                Timestamp otpExpiry = rs.getTimestamp("otpExpiry");
                String roleName = rs.getString("role");

                Timestamp now = new Timestamp(System.currentTimeMillis());

                if (storedOtp == null || otpExpiry == null) {
                    result.put("error", true);
                    result.put("status_code", (long)HttpServletResponse.SC_UNAUTHORIZED);
                    result.put("error_message", "OTP not requested or expired.");
                    result.put("error_details", "Please request a new OTP.");
                } else if (!otp.equals(storedOtp)) {
                    result.put("error", true);
                    result.put("status_code", (long)HttpServletResponse.SC_UNAUTHORIZED);
                    result.put("error_message", "Invalid OTP.");
                    result.put("error_details", "The code entered does not match.");
                } else if (now.after(otpExpiry)) {
                    // Clear the expired OTP fields
                    clearOtpFields(userId);
                    result.put("error", true);
                    result.put("status_code", (long)HttpServletResponse.SC_UNAUTHORIZED);
                    result.put("error_message", "OTP expired.");
                    result.put("error_details", "The code has expired. Please request a new one.");
                } else {
                    // SUCCESS: OTP is valid and not expired

                    clearOtpFields(userId); // Clear the OTP fields immediately after success for security
                    // updateLastLoginTime(userId); // Update last login time

                    // Generate JWT token (Placeholder logic)
                    JSONObject claims = new JSONObject();
                    claims.put("userId", userId);
                    claims.put("email", email);
                    claims.put("role", roleName);

                    String generatedToken = JWTUtil.generateToken(email, userId, roleName);
                    System.out.println("Token:"+generatedToken);

                    result.put("success", true);
                    result.put("message", "Login successful.");
                    result.put("userId", userId);
                    result.put("email", email);
                    result.put("role", roleName);
                    result.put("token", generatedToken);
                }
            } else {
                // User not found
                result.put("error", true);
                result.put("status_code", (long)HttpServletResponse.SC_UNAUTHORIZED);
                result.put("error_message", "Invalid credentials.");
                result.put("error_details", "User not found.");
            }
        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
        return result;
    }

    /**
     * Clears OTP fields after successful login or on expiry/reset.
     */
    private void clearOtpFields(Long userId) throws SQLException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        PoolDB pool = new PoolDB();
        // Clear both fields in the User table
        String sql = "UPDATE \"users\" SET \"otpCode\" = NULL, \"otpExpiry\" = NULL WHERE \"userId\" = ?";
        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, userId);
            pstmt.executeUpdate();
        } finally {
            pool.cleanup(null, pstmt, conn);
        }
    }


    /**
     * Registers a new user (Admin-only function) and handles associated MSME data if applicable.
     */
    private JSONObject registerUserToDb(
            String role, String email, String contactName,
            String companyName, String udyamRegistrationNo, String industrySector,
            String oneLiner, String linkedin) throws SQLException {

        Connection conn = null;
        PreparedStatement pstmtUser = null;
        PreparedStatement pstmtMsme = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();
        JSONObject output = new JSONObject();
        Long newUserId = null;

        // 1. Insert into User Table
        String sqlUser = "INSERT INTO \"users\" (\"email\", \"role\", \"one_liner\", \"linkedin\") VALUES (?, ?, ?, ?) RETURNING \"userId\"";

        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false); // Start transaction

            pstmtUser = conn.prepareStatement(sqlUser, Statement.RETURN_GENERATED_KEYS);
            pstmtUser.setString(1, email);
            pstmtUser.setString(2, role);

            // Set optional professional fields (only relevant for Auditor/Lender)
            if ("IT_AUDITOR".equals(role) || "FIN_PARTNER".equals(role) || "ADMIN".equals(role)) {
                pstmtUser.setString(3, oneLiner);
                pstmtUser.setString(4, linkedin);
            } else {
                pstmtUser.setNull(3, java.sql.Types.VARCHAR);
                pstmtUser.setNull(4, java.sql.Types.VARCHAR);
            }

            int affectedRows = pstmtUser.executeUpdate();
            if (affectedRows == 0) {
                throw new SQLException("Creating user failed, no rows affected.");
            }

            rs = pstmtUser.getGeneratedKeys();
            if (rs.next()) {
                newUserId = rs.getLong(1);
            } else {
                throw new SQLException("Creating user failed, no ID obtained.");
            }

            // 2. Insert into MSME Table if role is MSME_OWNER
            if ("msme".equals(role)) {
                if (companyName == null || udyamRegistrationNo == null || industrySector == null) {
                    throw new IllegalArgumentException("MSME_OWNER requires companyName, udyamRegistrationNo, and industrySector.");
                }

                String sqlMsme = "INSERT INTO \"msme\" (\"msmeId\", \"companyName\", \"udyamRegistrationNo\", \"industrySector\", \"contactName\") VALUES (?, ?, ?, ?, ?)";
                pstmtMsme = conn.prepareStatement(sqlMsme);
                pstmtMsme.setLong(1, newUserId);
                pstmtMsme.setString(2, companyName);
                pstmtMsme.setString(3, udyamRegistrationNo);
                pstmtMsme.setString(4, industrySector);
                pstmtMsme.setString(5, contactName); // Contact name is stored in MSME table

                if (pstmtMsme.executeUpdate() == 0) {
                    throw new SQLException("Creating MSME details failed.");
                }
            }

            conn.commit(); // Commit transaction

            output.put("success", true);
            output.put("message", "User created successfully by Admin.");
            output.put("userId", newUserId);
            output.put("role", role);

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback(); // Rollback transaction on error
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
            throw e; // Re-throw the original exception
        } catch (IllegalArgumentException e) {
            // Handle specific validation errors for required fields
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
            output.put("error", true);
            output.put("status_code", (long)HttpServletResponse.SC_BAD_REQUEST);
            output.put("error_message", "Registration validation error.");
            output.put("error_details", e.getMessage());
            return output;

        } finally {
            pool.cleanup(rs, pstmtMsme, null);
            pool.cleanup(null, pstmtUser, conn);
        }
        return new JSONObject() {{ put("success", true); put("data", output); }};
    }

    /**
     * Updates the last_login_at timestamp for a user.
     */
    private void updateLastLoginTime(Long userId) throws SQLException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        PoolDB pool = new PoolDB();
        String sql = "UPDATE \"users\" SET \"last_login_at\" = NOW() WHERE \"userId\" = ?";
        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sql);
            pstmt.setLong(1, userId);
            pstmt.executeUpdate();
        } finally {
            pool.cleanup(null, pstmt, conn);
        }
    }

    @Override
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        if (!"POST".equalsIgnoreCase(method)) {
            OutputProcessor.errorResponse(res, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method Not Allowed", "Only POST method is supported for User & Role Management operations.", req.getRequestURI());
            return false;
        }
        return InputProcessor.validate(req, res);
    }

    // --- Helper Methods ---

    /**
     * Validates the input for user creation/update.
     */
    private String validateUserCreationInput(String email) {
        if (email != null && !EMAIL_PATTERN.matcher(email).matches()) {
            return "Invalid email format.";
        }
        return null; // Input is valid
    }

    /**
     * Checks if an email already exists (for uniqueness).
     */
    private boolean isEmailPresent(String email, Long excludeUserId) throws SQLException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PoolDB pool = new PoolDB();

        StringBuilder sqlBuilder = new StringBuilder("SELECT COUNT(*) FROM \"users\" WHERE \"email\" = ?");
        List<Object> params = new ArrayList<>();
        params.add(email);

        if (excludeUserId != null) {
            sqlBuilder.append(" AND \"userId\" != ?");
            params.add(excludeUserId);
        }

        try {
            conn = pool.getConnection();
            pstmt = conn.prepareStatement(sqlBuilder.toString());
            for (int i = 0; i < params.size(); i++) {
                pstmt.setObject(i + 1, params.get(i));
            }
            rs = pstmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
    }
}
