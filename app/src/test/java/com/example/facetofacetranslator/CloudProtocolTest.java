package com.example.facetofacetranslator;

import org.json.JSONObject;
import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class CloudProtocolTest {
    @Test public void rpcEncodingPreservesUnicodeAndReservedCharacters() throws Exception {
        assertEquals("%E4%B8%AD%E6%96%87%20%2B%2A~", AliyunRpcSigner.encode("中文 +*~"));
        assertEquals("%2F%26%3D%25", AliyunRpcSigner.encode("/&=%"));
    }
    @Test public void signatureMatchesIndependentPythonHmacFixture() throws Exception {
        // Generated independently with urllib.parse.quote + hmac.new(..., hashlib.sha1).
        Map<String, String> params = new LinkedHashMap<>();
        params.put("B", "中文 +*~"); params.put("Signature", "ignored"); params.put("A", "a b");
        assertEquals("A=a%20b&B=%E4%B8%AD%E6%96%87%20%2B%2A~&Signature=6rxhnzXdia5OvgEHhiJ7XYEjItM%3D",
                AliyunRpcSigner.signedForm(params, "test-secret"));
        assertEquals("ignored", params.get("Signature"));
    }
    private JSONObject event(boolean end, boolean heartbeat, Object endTime, int begin) throws Exception {
        JSONObject sentence = new JSONObject().put("text", " Hello. ").put("sentence_end", end)
                .put("heartbeat", heartbeat).put("end_time", endTime).put("begin_time", begin);
        return new JSONObject().put("header", new JSONObject().put("task_id", "task-1").put("event", "result-generated"))
                .put("payload", new JSONObject().put("output", new JSONObject().put("sentence", sentence)));
    }
    @Test public void finalResultWithNullEndTimeIsTranslated() throws Exception {
        assertEquals("Hello.", new ParaformerProtocol.SentenceTracker().accept(event(true, false, JSONObject.NULL, 170), "task-1"));
    }
    @Test public void intermediateResultsAndHeartbeatsAreNotTranslated() throws Exception {
        ParaformerProtocol.SentenceTracker tracker = new ParaformerProtocol.SentenceTracker();
        assertNull(tracker.accept(event(false, false, 200, 170), "task-1"));
        assertNull(tracker.accept(event(true, true, 200, 170), "task-1"));
        JSONObject empty = event(true, false, 200, 170);
        empty.getJSONObject("payload").getJSONObject("output").getJSONObject("sentence").put("text", " ");
        assertNull(tracker.accept(empty, "task-1"));
        empty.getJSONObject("payload").getJSONObject("output").getJSONObject("sentence").put("text", JSONObject.NULL);
        assertNull(tracker.accept(empty, "task-1"));
    }
    @Test public void wrongTaskAndNonResultEventAreIgnored() throws Exception {
        JSONObject message = event(true, false, 200, 170);
        assertNull(ParaformerProtocol.finalSentence(message, "old-task"));
        message.getJSONObject("header").put("event", "task-started");
        assertNull(ParaformerProtocol.finalSentence(message, "task-1"));
        assertNull(ParaformerProtocol.finalSentence(new JSONObject(), "task-1"));
    }
    @Test public void repeatedFinalResultsAreDeduplicatedButRepeatedSpeechIsKept() throws Exception {
        ParaformerProtocol.SentenceTracker tracker = new ParaformerProtocol.SentenceTracker();
        assertEquals("Hello.", tracker.accept(event(true, false, 200, 170), "task-1"));
        assertNull(tracker.accept(event(true, false, 200, 170), "task-1"));
        assertEquals("Hello.", tracker.accept(event(true, false, 400, 370), "task-1"));
    }
    @Test public void taskStartAndFinishUseSameIdAndPcmEnglishParameters() throws Exception {
        JSONObject start = new JSONObject(ParaformerProtocol.start("task-1"));
        JSONObject finish = new JSONObject(ParaformerProtocol.finish("task-1"));
        assertEquals(start.getJSONObject("header").getString("task_id"), finish.getJSONObject("header").getString("task_id"));
        assertEquals("finish-task", finish.getJSONObject("header").getString("action"));
        JSONObject payload = start.getJSONObject("payload");
        assertEquals("paraformer-realtime-v2", payload.getString("model"));
        JSONObject parameters = payload.getJSONObject("parameters");
        assertEquals(16000, parameters.getInt("sample_rate"));
        assertEquals("pcm", parameters.getString("format"));
        assertEquals("en", parameters.getJSONArray("language_hints").getString(0));
        assertTrue(parameters.getBoolean("heartbeat"));
    }
    @Test public void workspaceCannotRedirectBearerCredentialsToOtherHost() {
        for (String invalid : new String[]{"", "foo.example.com", "foo/bar", "user@evil", "abc\r\nX-Test:evil"}) {
            try { ParaformerProtocol.endpoint(invalid); fail("Accepted " + invalid); }
            catch (IllegalArgumentException expected) { }
        }
        assertEquals("wss://abc-123.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference", ParaformerProtocol.endpoint("abc-123"));
    }
    @Test public void chineseVoiceInputUsesChineseRecognitionModelHint() throws Exception {
        JSONObject start = new JSONObject(ParaformerProtocol.start("chinese-input", "zh"));
        assertEquals("zh", start.getJSONObject("payload").getJSONObject("parameters")
                .getJSONArray("language_hints").getString(0));
        try { ParaformerProtocol.start("chinese-input", "auto"); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void successfulTranslationIsParsed() throws Exception {
        assertEquals("你好", TranslatorClient.parseResponse("{\"Code\":200,\"Data\":{\"Translated\":\"你好\"}}"));
    }
    @Test public void businessFailureInSuccessfulHttpResponseIsRejectedWithoutMessageLeak() throws Exception {
        try { TranslatorClient.parseResponse("{\"Code\":401,\"Message\":\"secret-token\",\"Data\":{\"Translated\":\"bad\"}}"); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("401")); assertFalse(expected.getMessage().contains("secret-token")); }
    }
    @Test public void unrecognizedErrorCodeCannotEchoCredentials() throws Exception {
        try { TranslatorClient.parseResponse("{\"Code\":\"secret-token\"}"); fail(); }
        catch (IllegalStateException expected) { assertFalse(expected.getMessage().contains("secret-token")); }
    }
    @Test public void emptyTranslationIsRejected() throws Exception {
        for (String body : new String[]{"{\"Code\":200}", "{\"Code\":200,\"Data\":{\"Translated\":\" \"}}", "{\"Code\":200,\"Data\":{\"Translated\":null}}"}) {
            try { TranslatorClient.parseResponse(body); fail(); } catch (IllegalStateException expected) { }
        }
    }
}
