package org.parano1d.courier;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Parano1d wallet PIN used to confirm watch actions.
 *
 * Separate from the phone screen lock. The phone stores only a salted
 * PBKDF2 verifier and enforces attempt limits; the watch never stores the PIN.
 * Wrong attempts lock the PIN for an increasing time. Nothing is erased.
 */
final class WalletPin {
    private static final String PREFS = "wallet-pin";
    private static final int ITERATIONS = 210_000;
    private static final int MIN_LENGTH = 4, MAX_LENGTH = 8;

    private WalletPin() {}

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean isSet(Context context) { return prefs(context).contains("verifier"); }

    static JSONObject state(Context context) throws Exception {
        return new JSONObject().put("set", isSet(context)).put("locked_seconds", lockedSeconds(context));
    }

    /** Sets or changes the PIN. Changing requires the current PIN. */
    static synchronized void set(Context context, String current, String next) throws Exception {
        if (!valid(next)) throw new Exception("Use a PIN of 4 to 8 digits.");
        if (isSet(context)) {
            JSONObject check = verify(context, current == null ? "" : current);
            if (!check.optBoolean("ok")) throw new Exception(check.optString("error", "The current PIN is incorrect."));
        }
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        prefs(context).edit()
            .putString("salt", hex(salt))
            .putString("verifier", hex(derive(next, salt)))
            .putInt("failures", 0).putLong("locked_until", 0).putLong("locked_wall", 0)
            .apply();
    }

    /** Returns {ok:true} or {ok:false,error,retry_after_seconds}. */
    static synchronized JSONObject verify(Context context, String pin) throws Exception {
        SharedPreferences p = prefs(context);
        if (!isSet(context)) return new JSONObject().put("ok", false).put("error", "Set up a wallet PIN on your phone first (Settings → Wear OS watch).");
        long wait = lockedSeconds(context);
        if (wait > 0) return new JSONObject().put("ok", false).put("error", "Too many wrong PINs. Try again in " + wait + " s.").put("retry_after_seconds", wait);
        if (!valid(pin)) return failed(context, p);
        byte[] expected = unhex(p.getString("verifier", ""));
        byte[] actual = derive(pin, unhex(p.getString("salt", "")));
        if (!MessageDigest.isEqual(expected, actual)) return failed(context, p);
        p.edit().putInt("failures", 0).putLong("locked_until", 0).putLong("locked_wall", 0).apply();
        return new JSONObject().put("ok", true);
    }

    private static JSONObject failed(Context context, SharedPreferences p) throws Exception {
        int failures = p.getInt("failures", 0) + 1;
        long lockMs = failures >= 10 ? 15 * 60_000L : failures >= 5 ? 60_000L : 0;
        SharedPreferences.Editor edit = p.edit().putInt("failures", failures);
        if (lockMs > 0) edit.putLong("locked_until", SystemClock.elapsedRealtime() + lockMs).putLong("locked_wall", System.currentTimeMillis() + lockMs);
        edit.apply();
        JSONObject result = new JSONObject().put("ok", false).put("error", lockMs > 0 ? "Too many wrong PINs. Try again in " + (lockMs / 1000) + " s." : "Wrong PIN. Try again.");
        if (lockMs > 0) result.put("retry_after_seconds", lockMs / 1000);
        return result;
    }

    static long lockedSeconds(Context context) {
        SharedPreferences p = prefs(context);
        long until = p.getLong("locked_until", 0), wall = p.getLong("locked_wall", 0);
        long remaining = Math.max(until - SystemClock.elapsedRealtime(), 0);
        // elapsedRealtime resets on reboot; the wall-clock copy keeps the lock across a restart.
        if (remaining == 0 && wall > System.currentTimeMillis()) remaining = wall - System.currentTimeMillis();
        return (remaining + 999) / 1000;
    }

    private static boolean valid(String pin) { return pin != null && pin.length() >= MIN_LENGTH && pin.length() <= MAX_LENGTH && pin.matches("[0-9]+"); }

    private static byte[] derive(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    private static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return s.toString(); }
    private static byte[] unhex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
