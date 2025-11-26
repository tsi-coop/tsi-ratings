package org.tsicoop.ratings.framework;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.tsicoop.ratings.service.v1.DMA;

import java.util.Iterator;

public class BSVUtil {

    public JSONObject validateAssessment(String txId, String tsiHash) throws Exception {
        JSONObject result =  new JSONObject();
        result.put("valid",false);
        JSONObject response = null;
        try {
            response = new HttpClient().sendGet("https://api.whatsonchain.com/v1/bsv/main/tx/"+txId);
            JSONArray voutArr = (JSONArray) response.get("vout");
            Iterator<JSONObject> it = voutArr.iterator();
            JSONObject vout = null;
            JSONObject scriptPubKey = null;
            JSONObject opReturn = null;
            String part = null;
            while(it.hasNext()) {
                vout = (JSONObject) it.next();
                scriptPubKey = (JSONObject) vout.get("scriptPubKey");
                if (scriptPubKey != null) {
                    opReturn = (JSONObject) scriptPubKey.get("opReturn");
                    if (opReturn != null) {
                        JSONArray parts = (JSONArray) opReturn.get("parts");
                        part = (String) parts.get(0);
                        if (part.equalsIgnoreCase(tsiHash)){
                            result.put("valid",true);
                            break;
                        }
                    }
                }
            }
        }catch(Exception e){
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        JSONObject result = new BSVUtil().validateAssessment("2445ad60ae786b92e8375f0ab739023975c9966c8c02c7d6b17c7cb52511dbad", "1234");
        System.out.println(result);
    }
}
