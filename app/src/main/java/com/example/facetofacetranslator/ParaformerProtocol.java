package com.example.facetofacetranslator;

import org.json.JSONObject;

/** Realtime WebSocket protocol for qwen3.5-livetranslate-flash-realtime (one-step ASR + translation). */
final class ParaformerProtocol {
    static final String MODEL = "qwen3.5-livetranslate-flash-realtime";
    static final int SAMPLE_RATE = 16000;
    private ParaformerProtocol() {}

    static String endpoint(String workspaceId) {
        if (workspaceId == null || !workspaceId.matches("[A-Za-z0-9-]{1,128}")) {
            throw new IllegalArgumentException("请填写有效的百炼业务空间 ID（北京地域）");
        }
        return "wss://" + workspaceId + ".cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime?model=" + MODEL;
    }

    static String sessionUpdate(String targetLang) throws Exception {
        if (!"en".equals(targetLang) && !"zh".equals(targetLang))
            throw new IllegalArgumentException("翻译目标语言仅支持 en/zh");
        JSONObject session = new JSONObject()
                .put("modalities", new org.json.JSONArray().put("text"))
                .put("sample_rate", SAMPLE_RATE)
                .put("input_audio_format", "pcm")
                .put("input_audio_transcription", JSONObject.NULL)
                .put("translation", new JSONObject().put("language", targetLang));
        return new JSONObject().put("type", "session.update").put("session", session).toString();
    }

    static String finish() throws Exception {
        return new JSONObject().put("type", "session.finish").toString();
    }

    static String appendAudio(byte[] pcm) throws Exception {
        return new JSONObject().put("type", "input_audio_buffer.append")
                .put("audio", android.util.Base64.encodeToString(pcm, android.util.Base64.NO_WRAP)).toString();
    }

    static final class SentenceTracker {
        private String pending;
        String accept(JSONObject message) {
            String type = message.optString("type");
            if ("response.text.done".equals(type)) {
                String text = message.optString("text", "").trim();
                pending = text.isEmpty() ? null : text;
                return null;
            }
            if ("response.done".equals(type)) {
                String out = pending; pending = null;
                JSONObject response = message.optJSONObject("response");
                if (response == null || !"completed".equals(response.optString("status"))) return null;
                return (out == null || out.isEmpty()) ? null : out;
            }
            return null;
        }
    }
}
