package org.parano1d.courier;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * App PIN or password (iOS AppLock.swift / AppCodeSetupView), separate from the phone's screen lock.
 * - 6-digit PIN, 4-digit PIN or a 12–128 character password.
 * - The code is first keyed with a non-exportable Android Keystore HMAC key, then stretched with PBKDF2, so a copied
 *   app folder can't be used to guess a PIN on another device. Only the salt and digest are stored, never the code.
 * - Each attempt is saved before it is checked, so force-quitting can't reset the count. From the 5th wrong entry
 *   there is a wait (1, 2, 4 … minutes). After 10 wrong entries the code stops working until it is reset with the
 *   phone's screen lock ("Forgot app code?").
 * - Optional, off by default: "Erase local wallet key after 10 wrong app-code entries" (iOS). Only wrong app-code
 *   entries count, never fingerprint or phone-PIN failures. The erase is committed (erase_pending) before it runs, and
 *   finished at the next start if the app stopped in between.
 */
final class AppCode {
    private AppCode() {}
    private static final String PREFS = "app-code", ALIAS = "paramo1d-app-code-v1";
    private static final int ITERATIONS = 120_000, MAX_ATTEMPTS = 10;
    private static final String[] PREDICTABLE = {"1234", "4321", "0123", "9876", "123456", "654321", "012345", "987654", "1212", "121212"};

    interface Eraser { void eraseLocalWalletKey() throws Exception; }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static boolean isSet(Context c) { return prefs(c).contains("digest"); }
    static String kind(Context c) { return prefs(c).getString("kind", ""); }
    static String label(String kind) { return kind.equals("four") ? "4-digit PIN" : kind.equals("password") ? "Password" : "6-digit PIN"; }
    static int digits(String kind) { return kind.equals("four") ? 4 : kind.equals("six") ? 6 : 0; }

    static JSONObject state(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        return new JSONObject().put("set", isSet(c)).put("kind", kind(c)).put("erase_on_ten", p.getBoolean("erase_on_ten", false))
            .put("attempts", p.getInt("attempts", 0)).put("erased", p.getBoolean("erase_completed", false));
    }

    static void validate(String kind, String code) throws Exception {
        int digits = digits(kind);
        if (digits > 0) {
            if (code == null || code.length() != digits || !code.matches("[0-9]+")) throw new Exception("Enter exactly " + digits + " digits.");
            if (code.chars().distinct().count() < 2 || java.util.Arrays.asList(PREDICTABLE).contains(code)) throw new Exception("Choose a less predictable PIN.");
        } else if (kind.equals("password")) {
            if (code == null || code.length() < 12 || code.length() > 128 || code.trim().isEmpty()) throw new Exception("Use a password of 12–128 characters.");
        } else throw new Exception("Choose a PIN or password.");
    }

    private static SecretKey deviceKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    private static byte[] digest(String code, byte[] salt) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(deviceKey());
        byte[] keyed = mac.doFinal(("Parano1d/app-code/1\0" + code).getBytes(StandardCharsets.UTF_8));
        char[] material = WalletKeys.hex(keyed).toCharArray();
        PBEKeySpec spec = new PBEKeySpec(material, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); java.util.Arrays.fill(material, '\0'); java.util.Arrays.fill(keyed, (byte) 0); }
    }

    static synchronized void configure(Context c, String kind, String code) throws Exception {
        validate(kind, code);
        SharedPreferences p = prefs(c);
        if (p.getBoolean("erase_pending", false)) throw new Exception("Finish the pending local-key erase before changing the app code.");
        byte[] salt = new byte[32]; new SecureRandom().nextBytes(salt);
        byte[] d = digest(code, salt);
        boolean erase = !p.getBoolean("erase_completed", false) && p.getBoolean("erase_on_ten", false);
        boolean saved = p.edit().clear().putString("kind", kind).putString("salt", WalletKeys.hex(salt)).putString("digest", WalletKeys.hex(d))
            .putInt("attempts", 0).putLong("next_ms", 0).putBoolean("erase_on_ten", erase).commit();
        if (!saved || !WalletKeys.hex(d).equals(p.getString("digest", ""))) throw new Exception("App lock couldn't be saved. Try again.");
    }

    static synchronized void setErase(Context c, boolean enabled) throws Exception {
        SharedPreferences p = prefs(c);
        if (!isSet(c) || p.getInt("attempts", 0) >= MAX_ATTEMPTS || p.getBoolean("erase_pending", false)) throw new Exception("Set an app code and unlock with it before changing this setting.");
        if (!p.edit().putBoolean("erase_on_ten", enabled).commit()) throw new Exception("The setting couldn't be saved.");
    }

    /** Checks a code. Throws with the message to show; returns normally only for the right code. */
    static synchronized void verify(Context c, String code, Eraser eraser) throws Exception {
        SharedPreferences p = prefs(c);
        if (!isSet(c)) return;
        if (p.getBoolean("erase_pending", false)) { completePendingErase(c, eraser); throw new Exception("Ten wrong app-code entries erased the local wallet key. Restore only with your original master key."); }
        int attempts = p.getInt("attempts", 0);
        if (attempts >= MAX_ATTEMPTS) throw new Exception(p.getBoolean("erase_completed", false)
            ? "The local wallet key was erased. Reset the app code with your phone's screen lock, then restore your original master key."
            : "Too many attempts. Tap Forgot app code? and confirm with your phone's screen lock to reset it.");
        long now = System.currentTimeMillis(), next = p.getLong("next_ms", 0);
        if (now < next) throw new Exception("Try again in " + Math.max(1, (next - now + 999) / 1000) + " seconds.");
        // Save the attempt before checking it, so stopping the app can't reset the count.
        attempts++;
        long wait = attempts >= 5 ? 60_000L << Math.min(attempts - 5, 10) : 0;
        if (!p.edit().putInt("attempts", attempts).putLong("next_ms", wait == 0 ? 0 : now + wait).commit()) throw new Exception("App lock couldn't be updated. Try again.");
        byte[] expected = WalletKeys.unhex(p.getString("digest", "")), actual = digest(code, WalletKeys.unhex(p.getString("salt", "")));
        if (!MessageDigest.isEqual(expected, actual)) {
            if (attempts >= MAX_ATTEMPTS && p.getBoolean("erase_on_ten", false)) {
                p.edit().putBoolean("erase_pending", true).commit();
                completePendingErase(c, eraser);
                throw new Exception("Ten wrong app-code entries erased the local wallet key. Restore only with your original master key.");
            }
            String what = p.getString("kind", "").equals("password") ? "password" : "PIN";
            int left = MAX_ATTEMPTS - attempts;
            throw new Exception("Incorrect " + what + "." + (left <= 5 && left > 0 ? " " + left + (left == 1 ? " attempt" : " attempts") + " left" + (p.getBoolean("erase_on_ten", false) ? " before the local wallet key is erased." : ".") : ""));
        }
        p.edit().putInt("attempts", 0).putLong("next_ms", 0).commit();
    }

    /** Finishes a committed erase (also called at start-up). */
    static synchronized void completePendingErase(Context c, Eraser eraser) throws Exception {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("erase_pending", false)) return;
        eraser.eraseLocalWalletKey();
        p.edit().putBoolean("erase_pending", false).putBoolean("erase_completed", true).commit();
    }
    static boolean erasePending(Context c) { return prefs(c).getBoolean("erase_pending", false); }
    /** The tenth wrong entry erased the wallet key: there is nothing left to unlock until a wallet is restored. */
    static boolean erased(Context c) { return prefs(c).getBoolean("erase_completed", false); }

    /** Only after the phone's screen lock was confirmed ("Forgot app code?"). */
    static synchronized void reset(Context c) {
        boolean erased = prefs(c).getBoolean("erase_completed", false);
        prefs(c).edit().clear().commit();
        if (erased) prefs(c).edit().putBoolean("erase_completed_notice", true).commit();
    }
}
