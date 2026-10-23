package com.example.facetofacetranslator;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CredentialsStore {
    private static final String ALIAS = "face_to_face_credentials";
    private static final String PREFS = "credentials";

    private CredentialsStore() {}

    static void putAll(Context context, Map<String, String> values) throws Exception {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            editor.putString(entry.getKey(), encrypt(entry.getValue()));
        }
        if (!editor.commit()) throw new IllegalStateException("Cannot save settings");
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        return encoded;
    }

    static String get(Context context, String name) throws Exception {
        String encoded = prefs(context).getString(name, null);
        if (encoded == null) return "";
        String[] parts = encoded.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("Stored settings are invalid");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry != null) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
