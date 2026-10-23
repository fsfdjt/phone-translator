package com.example.facetofacetranslator;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

/** Does not expose any microphone/audio-stream upload API. WAV uploads are synthesized TTS only. */
final class GatewayClient {
    interface Listener {
        void onEvent(GatewayClient source, JSONObject event);
        void onAudio(GatewayClient source, ByteString pcm);
        void onFailure(GatewayClient source, String message);
    }
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    private final String base, token;
    private volatile String callId;
    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile String number;
    private final Listener listener;
    GatewayClient(String base, String token, Listener listener) {
        this.base = GatewayProtocol.baseUrl(base);
        if (!token.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException("网关访问令牌需为 32 至 256 位字母、数字、连字符或下划线");
        this.token = token; this.listener = listener;
    }
    private Request.Builder request(String path) {
        return new Request.Builder().url(base + path).header("Authorization", "Bearer " + token);
    }
    private Request.Builder requestFresh(String path) {
        return request(path).header("Connection", "close");
    }
    private String body(Response response) throws IOException {
        if (!response.isSuccessful()) throw new IOException(response.code() == 401 ? "网关访问令牌错误"
                : response.code() == 409 ? "网关通话状态冲突，请先挂断或等待对方接听"
                : response.code() == 403 ? "网关未开放目标区号"
                : response.code() == 400 ? "网关拒绝音频：需要不超过 60 秒的 PCM 16 位 WAV；请检查 TTS 或分段发送"
                : response.code() == 503 ? "挂断尚未确认，服务器会继续重试，请检查供应商控制台"
                : "网关请求失败（HTTP " + response.code() + "）");
        if (response.body() == null || response.body().contentLength() > 65536) throw new IOException("网关响应异常");
        java.io.InputStream input=response.body().byteStream();
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        byte[] chunk=new byte[4096]; int count;
        while((count=input.read(chunk))!=-1){
            if(output.size()+count>65536)throw new IOException("网关响应过大");
            output.write(chunk,0,count);
        }
        return new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
    }
    /** 网关断线自动重连：先重连同一通话(3s×N)，N 次后自动重新拨号；全部失败才上报。 */
    private volatile int reconnectAttempt;
    private void scheduleReconnect() { scheduleReconnect(reconnectAttempt + 1); }
    private void scheduleReconnect(final int attempt) {
        if (closed) return;
        reconnectAttempt = attempt;
        android.util.Log.d("PT", "gw-reconnect-scheduled a=" + attempt);
        if (attempt > 4) { // 同一通话重连 4 次失败：网关可能已重启，自动重新拨号
            android.util.Log.d("PT", "gw-redial");
            try { connect(number); dial(); return; }
            catch (Exception error) { failed("网关连接中断；自动重连失败，请重新拨号"); return; }
        }
        new Thread(() -> {
            try { Thread.sleep(3000L * attempt); } catch (InterruptedException error) { return; }
            if (closed) return;
            android.util.Log.d("PT", "gw-reconnect try=" + attempt);
            try {
                Request upgrade = request("/v1/calls/" + callId + "/events").build();
                WebSocket next = CLIENT.newWebSocket(upgrade, listener());
                socket = next;
                if (closed) { next.cancel(); return; }
                // 异步建连：成功由 onOpen 确认并重置计数；失败由 onFailure 触发下一次
            } catch (Exception error) { scheduleReconnect(reconnectAttempt + 1); }
        }).start();
    }
    private WebSocketListener listener() { return new WebSocketListener() {
        @Override public void onOpen(WebSocket ws, Response response) {
            reconnectAttempt = 0;
            android.util.Log.d("PT", "gw-reconnect-ok");
        }
        @Override public void onMessage(WebSocket ws, String text) {
            if (closed) return;
            android.util.Log.d("PT", "gw-event=" + text);
            if (text.length()>16384) { failed("网关响应过大"); return; }
            try {
                JSONObject event = new JSONObject(text);
                listener.onEvent(GatewayClient.this, event);
            } catch (Exception error) { failed("网关响应格式错误"); }
        }
        @Override public void onMessage(WebSocket ws, ByteString bytes) {
            android.util.Log.d("PT", "gw-audio size=" + bytes.size() + " closed=" + closed);
            if (!closed && bytes.size() <= 32000 && bytes.size() % 2 == 0) listener.onAudio(GatewayClient.this, bytes);
        }
        @Override public void onFailure(WebSocket ws, Throwable error, Response response) { scheduleReconnect(); }
        @Override public void onClosing(WebSocket ws, int code, String reason) {
            ws.close(1000, "done"); scheduleReconnect();
        }
    }; }
    void connect(String number) throws Exception {
        this.number = number;
        String payload = new JSONObject().put("number", GatewayProtocol.number(number)).toString();
        try (Response response = CLIENT.newCall(requestFresh("/v1/calls").post(RequestBody.create(
                MediaType.parse("application/json"), payload)).build()).execute()) {
            JSONObject result = new JSONObject(body(response));
            callId = result.getString("callId");
            if (!callId.matches("[0-9a-f-]{36}")) throw new IOException("网关响应异常");
        }
        if (closed) { hangup(); return; }
        Request upgrade = request("/v1/calls/" + callId + "/events").build();
        socket = CLIENT.newWebSocket(upgrade, listener());
        if (closed) socket.cancel();
    }
    private void failed(String message) { if (!closed) listener.onFailure(this, message); }
    void dial() {
        WebSocket ws=socket;
        if (closed || ws==null || !ws.send("{\"event\":\"dial\"}")) failed("无法发送拨号指令");
    }
    void upload(File synthesizedWav, String utteranceId) throws Exception {
        if (closed || callId == null || synthesizedWav.length() == 0 || synthesizedWav.length() > 8 * 1024 * 1024)
            throw new IOException("英语语音文件不可用");
        try (Response response = CLIENT.newCall(requestFresh("/v1/calls/" + callId + "/utterances/" + utteranceId)
                .post(RequestBody.create(MediaType.parse("audio/wav"), synthesizedWav)).build()).execute()) { body(response); }
    }
    void hangup() throws Exception {
        closed = true;
        WebSocket old = socket;
        if (old != null) { old.send("{\"event\":\"hangup\"}"); old.close(1000, "hangup"); }
        if (callId == null) return;
        try (Response response = CLIENT.newCall(requestFresh("/v1/calls/" + callId).delete().build()).execute()) { body(response); }
    }
    void close() { closed = true; if (socket != null) socket.cancel(); }
}
