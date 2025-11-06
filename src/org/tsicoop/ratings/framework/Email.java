package org.tsicoop.ratings.framework;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.security.SecureRandom;

public class Email {

    public static String ZOHO_API_HOST = System.getenv("ZOHO_API_HOST");
    public static String ZOHO_AUTH_KEY = System.getenv("ZOHO_AUTH_KEY");

    public static void sendEmail(String apihost, String authorization, String email, String name, String subject, String content) throws Exception {
        HttpClient obj = new HttpClient();
        JSONObject test = new JSONObject();
        JSONObject fromOb = new JSONObject();
        fromOb.put("address",System.getenv("SENDER_EMAIL"));
        fromOb.put("name",System.getenv("SENDER_NAME"));
        test.put("from",fromOb);
        JSONObject address = new JSONObject();
        address.put("address",email);
        address.put("name",name);
        JSONObject toAddress = new JSONObject();
        toAddress.put("email_address",address);
        JSONArray toArray = new JSONArray();
        toArray.add(toAddress);
        test.put("to",toArray);
        test.put("subject",subject);
        test.put("htmlbody",content);
        //System.out.println(test);
        JSONObject output = obj.sendPost(apihost,authorization,test);
        //System.out.println(output);
    }

    public static void sendOTP(String email, String otp){
        if(System.getenv("TSI_RATINGS_ENV") != null && System.getenv("TSI_RATINGS_ENV").equalsIgnoreCase("PRODUCTION")) {
            String subject = "Your Login OTP";
            StringBuffer buff = new StringBuffer();
            buff.append("<p>The OTP for logging into your"+System.getenv("SENDER_EMAIL")+" account is "+otp+". It is valid for 5 minutes.</p>");
            buff.append("<p>Please do not share this OTP with anyone.</p>");
            buff.append("<p>Warm Regards<br/>"+System.getenv("SENDER_EMAIL")+" Team</p>");
            String content = buff.toString();
            try {
                new Email().sendEmail(ZOHO_API_HOST,
                        ZOHO_AUTH_KEY,
                        email,
                        "",
                        subject,
                        content);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static String generate4DigitOTP() {
        String otpS = null;
        if(System.getenv("TSI_RATINGS_ENV") != null && System.getenv("TSI_RATINGS_ENV").equalsIgnoreCase("PRODUCTION")) {
            SecureRandom random = new SecureRandom();
            int otp = 1000 + random.nextInt(9000); // Generates a number between 1000 (inclusive) and 9999 (inclusive)
            otpS = String.valueOf(otp);
        }else{
            otpS = "1234";
        }
        return otpS;
    }
}
