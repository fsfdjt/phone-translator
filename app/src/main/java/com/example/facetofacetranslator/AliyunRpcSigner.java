package com.example.facetofacetranslator;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Alibaba Cloud RPC signature v1. Secrets never enter the request URL. */
final class AliyunRpcSigner {
    private AliyunRpcSigner() {}

    static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
                .replace("*", "%2A").replace("%7E", "~");
    }

    static String canonical(Map<String, String> parameters) throws Exception {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> item : new TreeMap<>(parameters).entrySet()) {
            if ("Signature".equals(item.getKey())) continue;
            if (result.length() > 0) result.append('&');
            result.append(encode(item.getKey())).append('=').append(encode(item.getValue()));
        }
        return result.toString();
    }

    static String signedForm(Map<String, String> parameters, String secret) throws Exception {
        String query = canonical(parameters);
        String stringToSign = "POST&%2F&" + encode(query);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec((secret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String signature = Base64.getEncoder().encodeToString(
                mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        return query + "&Signature=" + encode(signature);
    }
}
