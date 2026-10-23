package com.example.facetofacetranslator;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** One recognition task; cancellation never delivers new recognition callbacks. */
final class ParaformerRecognizer {
    interface Listener {
        void onReady(ParaformerRecognizer source);
        void onSentence(ParaformerRecognizer source, String text);
        void onError(ParaformerRecognizer source, String message, boolean retryable);
    }
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build();
    private final Listener listener;
    private final String language;
    private final boolean external;
    private final java.util.ArrayDeque<ByteString> earlyAudio = new java.util.ArrayDeque<>();
    private int earlyBytes;
    private final String taskId = UUID.randomUUID().toString();
    private final ParaformerProtocol.SentenceTracker sentences = new ParaformerProtocol.SentenceTracker();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> fail("识别连接超时，请检查网络和业务空间 ID", true);
    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile boolean started;
    private AudioRecord recorder;

    ParaformerRecognizer(Listener listener) { this(listener, "zh"); }
    ParaformerRecognizer(Listener listener, String language) { this(listener, language, false); }
    ParaformerRecognizer(Listener listener, String language, boolean external) {
        if (!"en".equals(language) && !"zh".equals(language))
            throw new IllegalArgumentException("翻译目标语言仅支持 en/zh");
        this.listener = listener;
        this.language = language;
        this.external = external;
    }

    void start(String apiKey, String workspaceId) {
        Request request = new Request.Builder().url(ParaformerProtocol.endpoint(workspaceId))
                .header("Authorization", "Bearer " + apiKey).build();
        handler.postDelayed(timeout, 15000);
        socket = CLIENT.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                android.util.Log.d("PT", "pf-open");
                if (closed) { ws.cancel(); return; }
            }
            @Override public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    String type = message.optString("type");
                    if (closed) {
                        if ("session.finished".equals(type)) ws.close(1000, "done");
                        return;
                    }
                    if ("session.created".equals(type)) {
                        if (started) return;
                        if (!ws.send(ParaformerProtocol.sessionUpdate(language))) fail("无法配置识别任务", true);
                        else android.util.Log.d("PT", "pf-update lang=" + language);
                    } else if ("session.updated".equals(type)) {
                        android.util.Log.d("PT", "pf-started");
                        synchronized (ParaformerRecognizer.this) {
                            if (started) return;
                            started = true;
                            handler.removeCallbacks(timeout);
                            if (external) {
                                for (ByteString frame : earlyAudio) ws.send(ParaformerProtocol.appendAudio(frame.toByteArray()));
                                earlyAudio.clear(); earlyBytes = 0;
                            }
                        }
                        if (external) listener.onReady(ParaformerRecognizer.this);
                        else new Thread(() -> capture(ws), "paraformer-microphone").start();
                    } else if ("session.finished".equals(type)) {
                        android.util.Log.d("PT", "pf-finished");
                        fail("识别会话已结束，正在重新连接", true);
                    } else if ("error".equals(type)) {
                        android.util.Log.d("PT", "pf-failed code=" + message.optJSONObject("error").optString("code"));
                        // Do not display raw server messages, which can echo request credentials.
                        fail("识别任务被拒绝，请检查百炼密钥、北京业务空间、模型权限和余额", false);
                    } else {
                        String sentence = sentences.accept(message);
                        if (sentence != null && !closed) { android.util.Log.d("PT", "pf-sentence=" + sentence); listener.onSentence(ParaformerRecognizer.this, sentence); }
                    }
                } catch (Exception error) { fail("识别响应格式异常", false); }
            }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                int code = response == null ? 0 : response.code();
                android.util.Log.d("PT", "pf-onFailure code=" + code + " err=" + (error == null ? "null" : error.getMessage()));
                fail(code == 401 || code == 403 ? "百炼认证失败，请检查 API Key 和业务空间权限"
                        : "识别网络连接中断", code != 401 && code != 403 && code != 400 && code != 404);
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, "closed");
                if (!closed) fail("识别连接已关闭", true);
            }
        });
        if (closed) socket.cancel();
    }

    /** Remote phone PCM only. Microphone mode cannot use this API. */
    synchronized void offerAudio(ByteString pcm) {
        android.util.Log.d("PT", "pf-offer size=" + pcm.size() + " external=" + external + " started=" + started + " closed=" + closed + " q=" + (socket == null ? -1 : socket.queueSize()));
        if (!external || closed || pcm.size() == 0 || pcm.size() % 2 != 0) return;
        if (!started || socket == null) {
            earlyAudio.add(pcm); earlyBytes += pcm.size();
            while (earlyBytes > 32000 && !earlyAudio.isEmpty()) earlyBytes -= earlyAudio.remove().size();
            return;
        }
        try {
            if (socket.queueSize() > 512 * 1024 || !socket.send(ParaformerProtocol.appendAudio(pcm.toByteArray())))
                fail("对方语音上传积压，请检查网络", true);
        } catch (Exception error) { fail("对方语音上传失败", true); }
    }

    private void capture(WebSocket ws) {
        AudioRecord audio = null;
        try {
            synchronized (this) {
                if (closed) return;
                int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) throw new IllegalStateException();
                audio = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 6400));
                recorder = audio;
                if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException();
                audio.startRecording();
                if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException();
            }
            if (!closed) listener.onReady(this);
            byte[] chunk = new byte[3200];
            while (!closed) {
                int count = audio.read(chunk, 0, chunk.length);
                if (closed) break;
                if (count <= 0) { fail("麦克风收音失败，请检查权限或其他录音应用", false); break; }
                synchronized (this) {
                    if (closed) break;
                    try {
                        if (ws.queueSize() > 512 * 1024 || !ws.send(ParaformerProtocol.appendAudio(ByteString.of(chunk, 0, count).toByteArray()))) {
                            fail("网络过慢，识别音频无法及时上传", true); break;
                        }
                    } catch (Exception error) { fail("网络过慢，识别音频无法及时上传", true); break; }
                }
            }
        } catch (Exception error) { fail("无法开启麦克风，请检查录音权限和设备", false); }
        finally {
            synchronized (this) {
                if (audio != null) { try { audio.stop(); } catch (Exception ignored) {} audio.release(); }
                recorder = null;
            }
        }
    }

    private void fail(String message, boolean retryable) {
        synchronized (this) { if (closed) return; close(); }
        listener.onError(this, message, retryable);
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        earlyAudio.clear(); earlyBytes = 0;
        handler.removeCallbacks(timeout);
        if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} }
        WebSocket ws = socket;
        if (ws == null) return;
        try {
            if (started) {
                ws.send(ParaformerProtocol.finish());
                handler.postDelayed(ws::cancel, 2000);
            } else ws.cancel();
        } catch (Exception ignored) { ws.cancel(); }
    }
}
