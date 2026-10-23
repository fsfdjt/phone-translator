package com.example.facetofacetranslator;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** English recordings stored in app-private storage; only the index is persisted in prefs. */
final class PreRecordedAudio {
    static final int MAX_COUNT = 20;
    static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final String PREFS = "prerecorded";
    private static final String KEY = "items";

    static final class Item {
        final String id;
        final String title;
        final long durationMs;

        Item(String id, String title, long durationMs) {
            this.id = id;
            this.title = title;
            this.durationMs = durationMs;
        }
    }

    private PreRecordedAudio() {}

    static File dir(Context context) {
        File directory = new File(context.getFilesDir(), "prerecorded");
        directory.mkdirs();
        return directory;
    }

    static File file(Context context, String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{1,64}")) throw new IllegalArgumentException("录音标识无效");
        return new File(dir(context), id + ".wav");
    }

    static List<Item> list(Context context) {
        List<Item> result = new ArrayList<>();
        for (Item item : readAll(context)) {
            if (file(context, item.id).isFile()) result.add(item);
        }
        return result;
    }

    static Item save(Context context, File sourceWav, String title, long durationMs) throws Exception {
        if (sourceWav == null || !sourceWav.isFile() || sourceWav.length() == 0) {
            throw new IllegalArgumentException("录音文件不可用");
        }
        if (sourceWav.length() > MAX_BYTES) throw new IllegalArgumentException("录音不能超过 8MB");
        String id = UUID.randomUUID().toString();
        File target = file(context, id);
        InputStream input = new FileInputStream(sourceWav);
        OutputStream output = new FileOutputStream(target);
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        input.close();
        output.close();
        List<Item> items = readAll(context);
        items.add(new Item(id, normalizeTitle(title), Math.max(0, durationMs)));
        while (items.size() > MAX_COUNT) {
            Item removed = items.remove(0);
            file(context, removed.id).delete();
        }
        persist(context, items);
        return new Item(id, normalizeTitle(title), Math.max(0, durationMs));
    }

    static void delete(Context context, String id) {
        try {
            file(context, id).delete();
        } catch (Exception ignored) {
        }
        List<Item> remaining = new ArrayList<>();
        for (Item item : readAll(context)) {
            if (!item.id.equals(id)) remaining.add(item);
        }
        try {
            persist(context, remaining);
        } catch (Exception ignored) {
        }
    }

    private static List<Item> readAll(Context context) {
        List<Item> items = new ArrayList<>();
        String raw = prefs(context).getString(KEY, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                items.add(new Item(object.getString("id"), object.getString("title"), object.getLong("durationMs")));
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    private static void persist(Context context, List<Item> items) throws Exception {
        JSONArray array = new JSONArray();
        for (Item item : items) {
            JSONObject object = new JSONObject();
            object.put("id", item.id);
            object.put("title", item.title);
            object.put("durationMs", item.durationMs);
            array.put(object);
        }
        if (!prefs(context).edit().putString(KEY, array.toString()).commit()) {
            throw new IllegalStateException("无法保存录音信息");
        }
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.trim().isEmpty()) return "录音";
        String normalized = title.trim();
        return normalized.length() > 100 ? normalized.substring(0, 100) : normalized;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
