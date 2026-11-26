package org.parano1d.courier;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureStore {
    private static final String ALIAS = "paramo1d-local-store-v1";
    private static final byte[] AAD = "Paramo1d/local-store/1".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private final int maxBytes;
    private final byte[] aad;
    static final int ADMISSION_BYTES = 24 * 1024 * 1024;
    private final AtomicFile file;
    private int savedBytes;
    private long reservedBytes;
    /** The file exists but can't be decrypted or decoded (as opposed to a read error). */
    static final class Damaged extends Exception { Damaged(String message) { super(message); } }
    static final class Full extends Exception { Full() { super("App storage is full. Open Settings to clear local chat history."); } }
    void reserveRoom(int bytes) throws Full {
        if (bytes <= 0) return;
        if ((long)savedBytes + reservedBytes + bytes > ADMISSION_BYTES) throw new Full();
        reservedBytes += bytes;
    }
    SecureStore(Context context) { file = new AtomicFile(new File(context.getFilesDir(), "identity.pmc")); maxBytes = MAX_BYTES; aad = AAD; }
    /** A side store (audit fixes: "older.pmc", "requests.pmc"), encrypted the same way with its own context. */
    SecureStore(Context context, String name, int limit) {
        file = new AtomicFile(new File(context.getFilesDir(), name)); maxBytes = limit;
        aad = ("Paramo1d/local-store/1/" + name).getBytes(StandardCharsets.UTF_8);
    }
    /** Moves a damaged side file aside (name.damaged-<time>.json) so the app continues with an empty one. */
    void setAside() {
        File base = file.getBaseFile();
        if (base.exists()) base.renameTo(new File(base.getParentFile(), base.getName() + ".damaged-" + System.currentTimeMillis() + ".json"));
    }
    int savedBytes() { return savedBytes; }
    boolean isFull() { return (long)savedBytes + reservedBytes >= ADMISSION_BYTES; }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    JSONObject read() throws Exception {
        if (!file.getBaseFile().exists()) return new JSONObject();
        if (file.getBaseFile().length() > maxBytes) throw new Damaged("Local file exceeds size limit");
        byte[] bytes = file.readFully();
        if (bytes.length < 29 || bytes.length > maxBytes) throw new Damaged("Local file is invalid");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 0, 12)));
        cipher.updateAAD(aad);
        byte[] plain;
        try { plain = cipher.doFinal(bytes, 12, bytes.length - 12); }
        catch (javax.crypto.AEADBadTagException bad) { throw new Damaged("Local file could not be decrypted"); }
        savedBytes = plain.length; reservedBytes = 0;
        try { return new JSONObject(new String(plain, StandardCharsets.UTF_8)); }
        catch (org.json.JSONException bad) { throw new Damaged("Local file could not be decoded"); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    void save(JSONObject state) throws Exception {
        byte[] plain = state.toString().getBytes(StandardCharsets.UTF_8);
        int encodedBytes = plain.length;
        if (plain.length > maxBytes - 64) throw new Exception("Local storage is full; export a backup");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad);
        byte[] encrypted;
        try { encrypted = cipher.doFinal(plain); } finally { Arrays.fill(plain, (byte) 0); }
        FileOutputStream stream = null;
        try { stream = file.startWrite(); stream.write(cipher.getIV()); stream.write(encrypted); file.finishWrite(stream); savedBytes = encodedBytes; reservedBytes = 0; }
        catch (Exception error) { if (stream != null) file.failWrite(stream); throw error; }
    }
}
