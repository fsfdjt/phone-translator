package com.example.facetofacetranslator;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;

/** Records a short English clip to a local WAV file, then offers playback and saving. */
public final class RecordingActivity extends Activity {
    private static final int PERMISSION_REQUEST = 1;
    private static final long MAX_DURATION_MS = 60000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            updateTimer();
            handler.postDelayed(this, 250);
        }
    };
    private TextView status, timer;
    private Button recordButton, playButton, saveButton, retryButton;
    private EditText title;
    private LinearLayout resultPanel;
    private AudioRecord audio;
    private MediaPlayer player;
    private File tempFile;
    private volatile boolean recording;
    private long recordStart;
    private int generation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(this);
        heading.setText("录制英文音频");
        heading.setTextSize(22);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(heading, wrap());

        status = new TextView(this);
        status.setText("点开始后对着手机说英文，停止后可以试听并保存。");
        status.setTextSize(15);
        status.setPadding(0, dp(10), 0, dp(6));
        root.addView(status, wrap());

        timer = new TextView(this);
        timer.setText("00:00");
        timer.setTextSize(30);
        timer.setGravity(Gravity.CENTER_HORIZONTAL);
        timer.setPadding(0, dp(10), 0, dp(10));
        root.addView(timer, wrap());

        recordButton = new Button(this);
        recordButton.setText("开始录制");
        root.addView(recordButton, height(dp(52)));

        resultPanel = new LinearLayout(this);
        resultPanel.setOrientation(LinearLayout.VERTICAL);
        resultPanel.setVisibility(LinearLayout.GONE);
        root.addView(resultPanel, wrap());

        title = new EditText(this);
        title.setSingleLine(true);
        title.setHint("给这段录音起个名字（可选）");
        title.setMaxLines(1);
        resultPanel.addView(title, wrap());

        playButton = new Button(this);
        playButton.setText("试听");
        resultPanel.addView(playButton, height(dp(48)));

        saveButton = new Button(this);
        saveButton.setText("保存录音");
        resultPanel.addView(saveButton, height(dp(48)));

        retryButton = new Button(this);
        retryButton.setText("重新录制");
        resultPanel.addView(retryButton, height(dp(48)));

        recordButton.setOnClickListener(view -> {
            if (recording) stopRecording();
            else startRecording();
        });
        playButton.setOnClickListener(view -> play());
        saveButton.setOnClickListener(view -> save());
        retryButton.setOnClickListener(view -> {
            stopPlayback();
            tempFile = null;
            resultPanel.setVisibility(LinearLayout.GONE);
            timer.setText("00:00");
            recordButton.setText("开始录制");
            status.setText("点开始后对着手机说英文，停止后可以试听并保存。");
        });
        setContentView(scroll);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
        }
    }

    private void startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("需要麦克风权限才能录音");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
            return;
        }
        stopPlayback();
        int minimum = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            status.setText("无法初始化麦克风，请检查录音权限");
            return;
        }
        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.MIC, Wav.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 6400));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            status.setText("无法初始化麦克风，请检查录音权限");
            return;
        }
        audio = record;
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.release();
            audio = null;
            status.setText("无法开始录音，请重试");
            return;
        }
        try {
            tempFile = File.createTempFile("prerecorded-", ".wav", getCacheDir());
        } catch (Exception error) {
            record.stop();
            record.release();
            audio = null;
            status.setText("无法创建录音文件");
            return;
        }
        recording = true;
        recordStart = System.currentTimeMillis();
        int token = ++generation;
        resultPanel.setVisibility(LinearLayout.GONE);
        recordButton.setText("停止录制");
        status.setText("正在录音，最长 60 秒");
        handler.post(tick);
        final AudioRecord finalRecord = record;
        new Thread(() -> captureLoop(finalRecord, tempFile, token), "recorder").start();
    }

    private void stopRecording() {
        recording = false;
        handler.removeCallbacks(tick);
        AudioRecord record = audio;
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private void captureLoop(AudioRecord record, File output, int token) {
        long bytes = 0;
        try {
            RandomAccessFile raf = new RandomAccessFile(output, "rw");
            raf.write(Wav.header(0));
            byte[] chunk = new byte[3200];
            long maxBytes = Wav.SAMPLE_RATE * Wav.CHANNELS * (Wav.BITS_PER_SAMPLE / 8) * (MAX_DURATION_MS / 1000);
            while (recording && bytes < maxBytes) {
                int count = record.read(chunk, 0, chunk.length);
                if (count <= 0) break;
                raf.write(chunk, 0, count);
                bytes += count;
            }
            Wav.writeSizes(raf, (int) bytes);
            raf.close();
        } catch (Exception ignored) {
        } finally {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
            record.release();
        }
        final long durationMs = bytes * 1000L / (Wav.SAMPLE_RATE * Wav.CHANNELS * (Wav.BITS_PER_SAMPLE / 8));
        handler.post(() -> {
            if (token != generation) return;
            audio = null;
            recording = false;
            recordButton.setText("开始录制");
            if (output.isFile() && output.length() > Wav.HEADER_SIZE) {
                resultPanel.setVisibility(LinearLayout.VISIBLE);
                timer.setText(format(durationMs));
                status.setText("录制完成，可以试听或保存。");
            } else {
                tempFile = null;
                status.setText("录音为空，请重新录制");
            }
        });
    }

    private void play() {
        if (tempFile == null || !tempFile.isFile()) {
            Toast.makeText(this, "录音不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(tempFile.getAbsolutePath());
            player.setOnCompletionListener(mp -> stopPlayback());
            player.prepare();
            player.start();
        } catch (Exception error) {
            stopPlayback();
            Toast.makeText(this, "无法试听，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void save() {
        if (tempFile == null || !tempFile.isFile()) {
            Toast.makeText(this, "请先录制音频", Toast.LENGTH_SHORT).show();
            return;
        }
        long durationMs = tempFile.length() > Wav.HEADER_SIZE
                ? (tempFile.length() - Wav.HEADER_SIZE) * 1000L / (Wav.SAMPLE_RATE * Wav.CHANNELS * (Wav.BITS_PER_SAMPLE / 8))
                : 0;
        try {
            PreRecordedAudio.save(this, tempFile, title.getText().toString(), durationMs);
            Toast.makeText(this, "录音已保存", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception error) {
            Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_LONG).show();
        }
    }

    private void updateTimer() {
        if (!recording) return;
        long elapsed = System.currentTimeMillis() - recordStart;
        if (elapsed >= MAX_DURATION_MS) {
            stopRecording();
            return;
        }
        timer.setText(format(elapsed));
    }

    private static String format(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void stopPlayback() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams height(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, value);
        params.topMargin = dp(8);
        return params;
    }

    @Override protected void onDestroy() {
        recording = false;
        handler.removeCallbacksAndMessages(null);
        stopPlayback();
        if (audio != null) {
            try {
                audio.stop();
            } catch (Exception ignored) {
            }
            audio.release();
            audio = null;
        }
        if (tempFile != null) tempFile.delete();
        super.onDestroy();
    }
}
