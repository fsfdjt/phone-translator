package com.example.facetofacetranslator;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 1;
    private static final int REQUEST_IMPORT_AUDIO = 2;
    private static final String[] SETTINGS = {"bailianApiKey", "bailianWorkspaceId", "mtAccessKeyId", "mtAccessKeySecret", "gatewayUrl", "gatewayToken"};
    private final EditText[] settings = new EditText[6];
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };
    private TextView status, transcript, localTranscript;
    private EditText input, preparedScripts, number;
    private LinearLayout preparedPhraseList, prerecordedList;
    private Button listenButton, sendButton;
    private MediaPlayer prerecordedPlayer;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status); transcript = findViewById(R.id.transcript);
        input = findViewById(R.id.input);
        number = findViewById(R.id.phoneNumber);
        localTranscript = findViewById(R.id.localTranscript);
        preparedScripts = findViewById(R.id.preparedScripts);
        preparedPhraseList = findViewById(R.id.preparedPhraseList);
        prerecordedList = findViewById(R.id.prerecordedList);
        settings[0] = findViewById(R.id.bailianApiKey);
        settings[1] = findViewById(R.id.bailianWorkspaceId);
        settings[2] = findViewById(R.id.mtAccessKeyId);
        settings[3] = findViewById(R.id.mtAccessKeySecret);
        settings[4] = findViewById(R.id.gatewayUrl);
        settings[5] = findViewById(R.id.gatewayToken);
        listenButton = findViewById(R.id.listenButton); sendButton = findViewById(R.id.sendButton);
        refresh();
        try { for (int i = 0; i < settings.length; i++) settings[i].setText(CredentialsStore.get(this, SETTINGS[i])); }
        catch (Exception error) { status.setText("读取配置失败，请重新填写并保存"); }
        try {
            String draft = CredentialsStore.get(this, "preparedScripts");
            preparedScripts.setText(draft);
            renderPreparedScripts(PreparedScripts.parse(draft));
        } catch (Exception error) { Toast.makeText(this, "话术读取失败，可以重新编辑并保存", Toast.LENGTH_LONG).show(); }
        findViewById(R.id.saveScriptsButton).setOnClickListener(view -> savePreparedScripts());
        findViewById(R.id.saveButton).setOnClickListener(view -> saveSettings());
        findViewById(R.id.recordAudioButton).setOnClickListener(view -> {
            if (ListeningService.snapshot().active) { Toast.makeText(this, "请先挂断电话再录音", Toast.LENGTH_LONG).show(); return; }
            startActivity(new Intent(this, RecordingActivity.class));
        });
        findViewById(R.id.importAudioButton).setOnClickListener(view -> {
            if (ListeningService.snapshot().active) { Toast.makeText(this, "请先挂断电话再导入", Toast.LENGTH_LONG).show(); return; }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            startActivityForResult(intent, REQUEST_IMPORT_AUDIO);
        });
        listenButton.setOnClickListener(view -> {
            if (ListeningService.snapshot().active) startService(new Intent(this, ListeningService.class).setAction(ListeningService.ACTION_STOP));
            else if (savedSettingsValid()) requestListeningPermission();
        });
        sendButton.setOnClickListener(view -> {
            ListeningService.Snapshot state = ListeningService.snapshot();
            if (!state.connected || state.busy) return;
            String text = input.getText().toString().trim();
            if (text.isEmpty() || text.length() > 5000) { input.setError("请输入 1 至 5000 字中文"); return; }
            startService(new Intent(this, ListeningService.class).setAction(ListeningService.ACTION_SEND)
                    .putExtra(ListeningService.EXTRA_TEXT, text));
            input.setText(""); // 发送后清空；若发送失败可点"恢复文本"找回（localText 保留失败内容）
        });
        findViewById(R.id.voiceButton).setOnClickListener(view -> {
            if (ListeningService.snapshot().connected) startService(new Intent(this, ListeningService.class).setAction(ListeningService.ACTION_VOICE));
        });
        findViewById(R.id.recoverTextButton).setOnClickListener(view -> {
            input.setText(ListeningService.snapshot().localText); input.setSelection(input.length());
        });
    }
    private void savePreparedScripts() {
        String draft = preparedScripts.getText().toString();
        try {
            List<String> phrases = PreparedScripts.parse(draft);
            Map<String, String> value = new LinkedHashMap<>();
            value.put("preparedScripts", draft);
            CredentialsStore.putAll(this, value);
            renderPreparedScripts(phrases);
            Toast.makeText(this, "话术已保存，点选即可使用", Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException error) { preparedScripts.setError(error.getMessage()); }
        catch (Exception error) { Toast.makeText(this, "话术保存失败，请重试", Toast.LENGTH_LONG).show(); }
    }
    private void renderPreparedScripts(List<String> phrases) {
        preparedPhraseList.removeAllViews();
        for (String phrase : phrases) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button fill = new Button(this);
            fill.setText(phrase);
            fill.setAllCaps(false);
            fill.setMaxLines(2);
            fill.setEllipsize(TextUtils.TruncateAt.END);
            fill.setContentDescription("填入话术：" + phrase);
            fill.setOnClickListener(view -> {
                input.setText(phrase); input.setSelection(input.length()); input.requestFocus();
                input.getParent().requestChildFocus(input, input);
            });
            row.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button copy = new Button(this);
            copy.setText("复制");
            copy.setOnClickListener(view -> {
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("准备的话术", phrase));
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            });
            row.addView(copy, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            preparedPhraseList.addView(row);
        }
    }
    private void renderPreRecorded() {
        prerecordedList.removeAllViews();
        List<PreRecordedAudio.Item> items = PreRecordedAudio.list(this);
        ListeningService.Snapshot state = ListeningService.snapshot();
        for (PreRecordedAudio.Item item : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button play = new Button(this);
            play.setText(item.title + " " + formatDuration(item.durationMs));
            play.setAllCaps(false);
            play.setMaxLines(2);
            play.setEllipsize(TextUtils.TruncateAt.END);
            play.setContentDescription("播放预录音频给对方：" + item.title);
            play.setEnabled(state.connected && !state.busy);
            play.setOnClickListener(view -> {
                startService(new Intent(this, ListeningService.class).setAction(ListeningService.ACTION_PLAY_PRERECORDED)
                        .putExtra(ListeningService.EXTRA_PRERECORDED_ID, item.id));
            });
            row.addView(play, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button preview = new Button(this);
            preview.setText("试听");
            preview.setOnClickListener(view -> previewAudio(item.id));
            row.addView(preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            Button delete = new Button(this);
            delete.setText("删除");
            delete.setOnClickListener(view -> { PreRecordedAudio.delete(this, item.id); renderPreRecorded(); });
            row.addView(delete, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            prerecordedList.addView(row);
        }
    }
    private void previewAudio(String id) {
        stopPreview();
        try {
            File file = PreRecordedAudio.file(this, id);
            prerecordedPlayer = new MediaPlayer();
            prerecordedPlayer.setDataSource(file.getAbsolutePath());
            prerecordedPlayer.setOnCompletionListener(mp -> stopPreview());
            prerecordedPlayer.prepare();
            prerecordedPlayer.start();
        } catch (Exception error) {
            stopPreview();
            Toast.makeText(this, "无法试听", Toast.LENGTH_SHORT).show();
        }
    }
    private void stopPreview() {
        if (prerecordedPlayer != null) {
            try { prerecordedPlayer.stop(); } catch (Exception ignored) { }
            prerecordedPlayer.release();
            prerecordedPlayer = null;
        }
    }
    private static String formatDuration(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
    private void importAudio(Uri uri) {
        if (uri == null) return;
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) { }
        String title = queryDisplayName(uri);
        status.setText("正在导入并转换音频：" + title + " …");
        new Thread(() -> {
            try {
                File wav = DecodeUtil.decodeToWav(this, uri);
                PreRecordedAudio.Item item = PreRecordedAudio.save(this, wav, title,
                        durationOf(wav));
                wav.delete();
                runOnUiThread(() -> { renderPreRecorded(); status.setText("已导入：" + item.title); Toast.makeText(this, "音频已导入，通话中点击即可整段连续播放（长音频自动分段）", Toast.LENGTH_LONG).show(); });
            } catch (Exception error) {
                String message = error.getMessage() == null ? "导入失败" : error.getMessage();
                runOnUiThread(() -> status.setText("导入失败：" + message));
            }
        }).start();
    }
    private String queryDisplayName(Uri uri) {
        String name = "导入音频";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && !cursor.isNull(index)) name = cursor.getString(index);
            }
        } catch (Exception ignored) { }
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name;
    }
    private static long durationOf(File wav) {
        try (java.io.DataInputStream input = new java.io.DataInputStream(new java.io.FileInputStream(wav))) {
            input.skipBytes(4);
            byte[] riff = new byte[4]; input.readFully(riff);
            if (!new String(riff, java.nio.charset.StandardCharsets.US_ASCII).equals("WAVE")) return 0;
            input.skipBytes(4); input.readFully(riff); input.skipBytes(4);
            if (!new String(riff, java.nio.charset.StandardCharsets.US_ASCII).equals("fmt ")) return 0;
            int fmtSize = Integer.reverseBytes(input.readInt());
            short format = Short.reverseBytes(input.readShort());
            input.skipBytes(2);
            int sampleRate = Integer.reverseBytes(input.readInt());
            input.skipBytes(fmtSize - 8);
            while (true) {
                input.readFully(riff);
                int size = Integer.reverseBytes(input.readInt());
                if (new String(riff, java.nio.charset.StandardCharsets.US_ASCII).equals("data")) {
                    return size * 8L * 1000 / (sampleRate * 16);
                }
                input.skipBytes(size);
            }
        } catch (Exception ignored) { return 0; }
    }
    private void saveSettings() {
        if (ListeningService.snapshot().active) { Toast.makeText(this, "请先挂断再修改配置", Toast.LENGTH_LONG).show(); return; }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < settings.length; i++) {
            String value = settings[i].getText().toString().trim();
            if (value.isEmpty()) { settings[i].setError("请填写此项"); return; }
            values.put(SETTINGS[i], value);
        }
        try { ParaformerProtocol.endpoint(values.get("bailianWorkspaceId")); }
        catch (Exception error) { settings[1].setError("请填写北京地域的业务空间 ID，仅支持字母、数字和连字符"); return; }
        try { values.put("gatewayUrl", GatewayProtocol.baseUrl(values.get("gatewayUrl"))); }
        catch (Exception error) { settings[4].setError(error.getMessage()); return; }
        if (!values.get("gatewayToken").matches("[A-Za-z0-9_-]{32,256}")) { settings[5].setError("令牌需为 32 至 256 位字母、数字、连字符或下划线"); return; }
        try {
            CredentialsStore.putAll(this, values);
            status.setText("配置已保存，可以填写号码并拨号");
            Toast.makeText(this, "配置已加密保存", Toast.LENGTH_SHORT).show();
        } catch (Exception error) { status.setText("保存配置失败，请重试"); }
    }
    private boolean savedSettingsValid() {
        try {
            for (String name : SETTINGS) if (CredentialsStore.get(this, name).isEmpty()) {
                status.setText("请先填写并保存四项阿里云配置、网关地址和令牌"); return false;
            }
            ParaformerProtocol.endpoint(CredentialsStore.get(this, SETTINGS[1]));
            GatewayProtocol.baseUrl(CredentialsStore.get(this, "gatewayUrl"));
            if (!CredentialsStore.get(this,"gatewayToken").matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException();
            try { GatewayProtocol.number(number.getText().toString()); }
            catch (IllegalArgumentException error) { number.setError(error.getMessage()); return false; }
            return true;
        } catch (Exception error) { status.setText("配置不可用，请重新填写并保存"); return false; }
    }
    private void requestListeningPermission() {
        boolean microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notification = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (microphone && notification) { beginListening(); return; }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST);
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != PERMISSION_REQUEST) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginListening();
        else status.setText("需要麦克风权限才能启动带中文语音输入的通话服务");
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_AUDIO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importAudio(data.getData());
        }
    }
    private void beginListening() {
        if (!savedSettingsValid()) return;
        try { startForegroundService(new Intent(this, ListeningService.class).setAction(ListeningService.ACTION_START)
                .putExtra("number", GatewayProtocol.number(number.getText().toString()))); }
        catch (RuntimeException error) { status.setText("无法启动通话服务，请检查号码和麦克风权限后重试"); }
    }
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ListeningService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updates, filter);
        refresh();
    }
    @Override protected void onStop() { unregisterReceiver(updates); stopPreview(); super.onStop(); }
    private void refresh() {
        ListeningService.Snapshot state = ListeningService.snapshot();
        status.setText(state.status); transcript.setText(TextUtils.join("\n\n", state.history));
        localTranscript.setText(state.localText);
        listenButton.setText(state.active ? "挂断电话" : "通过网关拨号");
        sendButton.setEnabled(state.connected && !state.busy);
        Button voiceButton=findViewById(R.id.voiceButton);
        voiceButton.setText(state.voice ? "关闭中文语音输入" : "开启中文语音输入（自动发送）");
        voiceButton.setEnabled(state.connected);
        number.setEnabled(!state.active);
        for (EditText setting : settings) if (setting != null) setting.setEnabled(!state.active);
        findViewById(R.id.saveButton).setEnabled(!state.active);
        findViewById(R.id.recordAudioButton).setEnabled(!state.active);
        findViewById(R.id.importAudioButton).setEnabled(!state.active);
        renderPreRecorded();
    }
}
