package com.example.facetofacetranslator;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

final class TranslatorClient {
    private TranslatorClient() {}

    static String translate(String text, String source, String target, String id, String secret) throws Exception {
        Map<String, String> params = new TreeMap<>();
        params.put("Action", "TranslateGeneral");
        params.put("Version", "2018-10-12");
        params.put("Format", "JSON");
        params.put("AccessKeyId", id);
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureVersion", "1.0");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("Timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        params.put("FormatType", "text");
        params.put("SourceLanguage", source);
        params.put("TargetLanguage", target);
        params.put("SourceText", text);
        params.put("Scene", "general");
        URL url = new URL("https://mt.cn-hangzhou.aliyuncs.com/");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] body = AliyunRpcSigner.signedForm(params, secret).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("翻译 HTTP " + code + "，请检查机器翻译权限和网络");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 1024 * 1024) throw new IllegalStateException("翻译响应过大");
                    output.write(buffer, 0, count);
                }
                return parseResponse(output.toString("UTF-8"));
            }
        } finally {
            connection.disconnect();
        }
    }

    static String parseResponse(String body) throws Exception {
        JSONObject response = new JSONObject(body);
        if (response.optInt("Code", -1) != 200) {
            String code = response.optString("Code", "unknown");
            if (!code.matches("[0-9]{1,8}") && !code.equals("SignatureDoesNotMatch")
                    && !code.equals("InvalidAccessKeyId.NotFound") && !code.equals("Forbidden.RAM")) code = "unknown";
            throw new IllegalStateException("机器翻译错误 " + code + "，请检查服务开通、权限和余额");
        }
        JSONObject data = response.optJSONObject("Data");
        Object value = data == null ? null : data.opt("Translated");
        String result = value instanceof String ? ((String) value).trim() : "";
        if (result.isEmpty()) throw new IllegalStateException("机器翻译返回空译文");
        return result;
    }
}
