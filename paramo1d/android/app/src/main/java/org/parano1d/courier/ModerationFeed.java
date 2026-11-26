package org.parano1d.courier;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Owner-signed moderation list for the public #parano1d room. Byte-for-byte the same rules as
 * iOS App/ModerationProtocol.swift + PublicModerationStore.swift, so one server and key serve both:
 * Ed25519 over the exact payload bytes (checked in Rust before parsing), sequence/digest pinning,
 * the last good list keeps applying until each decision expires. Private chats, wallet, payments
 * and receipts never consult it.
 */
final class ModerationFeed {
    static final String SCOPE = "com.koajent.parano1d.mainnet.public-room.v1";
    private static final Set<String> REASONS = new HashSet<>(java.util.Arrays.asList(
        "harassment", "threats", "hateful-content", "sexual-content", "scam", "spam", "privacy"));
    private static final int MAX_BYTES = 512_000, MAX_PAYLOAD = 350_000;
    private static final long REFRESH_MS = 5 * 60_000L;
    private static final okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build();
    private static volatile long lastAttempt;
    private static JSONObject config;

    private ModerationFeed() {}

    /** assets/moderation.json: {"enabled","feedURL","publicKeyBase64"}, copied from the iOS builds at build time. */
    static synchronized JSONObject config(Context context) {
        if (config != null) return config;
        try (InputStream in = context.getAssets().open("moderation.json")) {
            JSONObject c = new JSONObject(new String(readAll(in, 4096), StandardCharsets.UTF_8));
            boolean ok = c.optBoolean("enabled") && c.optString("feedURL").startsWith("https://")
                && Base64.decode(c.optString("publicKeyBase64"), Base64.NO_WRAP).length == 32;
            config = ok ? c : new JSONObject().put("enabled", false);
        } catch (Exception missing) {
            try { config = new JSONObject().put("enabled", false); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
        return config;
    }

    /** What the UI applies: unexpired decisions from the last accepted list (even when stale or offline). */
    static JSONObject view(Context context, JSONObject data) throws Exception {
        JSONObject out = new JSONObject().put("enabled", config(context).optBoolean("enabled")).put("decisions", new JSONArray());
        JSONObject saved = data.optJSONObject("moderation");
        if (!out.optBoolean("enabled") || saved == null || !saved.has("payload")) return out;
        try {
            JSONObject payload = new JSONObject(new String(Base64.decode(saved.getString("payload"), Base64.NO_WRAP), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis() / 1000;
            JSONArray decisions = payload.getJSONArray("decisions"), active = new JSONArray();
            for (int i = 0; i < decisions.length(); i++) {
                JSONObject d = decisions.getJSONObject(i);
                if (d.getLong("expiresAt") > now) active.put(new JSONObject().put("kind", d.getString("kind")).put("target", d.getString("target")).put("reason", d.getString("reason")));
            }
            out.put("decisions", active).put("sequence", payload.getLong("sequence")).put("stale", now >= payload.getLong("validUntil"));
        } catch (Exception broken) { /* keep showing nothing rather than a half-read list */ }
        return out;
    }

    /**
     * Refreshes at most every 5 minutes, only while the public room is open, the app is unlocked and online
     * (the caller checks those). HTTPS only, no redirects, no cookies, no wallet or chat identity sent.
     * Returns true when a newer list was accepted. Throws on a rejected list; the old list stays.
     */
    static boolean refresh(Context context, JSONObject data, boolean protectedRoute) throws Exception {
        JSONObject c = config(context);
        if (!c.optBoolean("enabled") || protectedRoute) return false;
        long now = System.currentTimeMillis();
        if (now - lastAttempt < REFRESH_MS) return false;
        lastAttempt = now;
        okhttp3.Request request = new okhttp3.Request.Builder().url(c.getString("feedURL")).header("User-Agent", "Parano1d").header("Cache-Control", "no-cache").build();
        byte[] body;
        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (response.code() != 200 || response.body() == null) throw new Exception("Moderation list unavailable.");
            if (response.body().contentLength() > MAX_BYTES) throw new Exception("Moderation list exceeds its size limit.");
            body = readAll(response.body().byteStream(), MAX_BYTES);
        }
        JSONObject envelope = new JSONObject(new String(body, StandardCharsets.UTF_8));
        if (envelope.length() != 2 || !envelope.has("payload") || !envelope.has("signature")) throw new Exception("Invalid moderation envelope.");
        String payloadB64 = envelope.getString("payload");
        byte[] payloadBytes = Base64.decode(payloadB64, Base64.NO_WRAP);
        if (payloadBytes.length == 0 || payloadBytes.length > MAX_PAYLOAD) throw new Exception("Invalid moderation payload size.");
        // Signature first, over exactly these bytes (Rust core, ed25519 verify_strict); never re-serialized.
        JSONObject verified = new JSONObject(NativeCore.call(new JSONObject().put("op", "moderation_verify")
            .put("public_key", c.getString("publicKeyBase64")).put("payload", payloadB64).put("signature", envelope.getString("signature")).toString()));
        if (!verified.optBoolean("ok")) throw new Exception(verified.optString("error", "Moderation signature rejected."));
        String digest = verified.getJSONObject("result").getString("digest");
        JSONObject payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
        validate(payload, now / 1000);
        long sequence = payload.getLong("sequence");
        JSONObject saved = data.optJSONObject("moderation");
        if (saved != null && saved.has("sequence")) {
            long accepted = saved.getLong("sequence");
            if (sequence < accepted) throw new Exception("Older moderation snapshot rejected.");
            if (sequence == accepted) {
                if (!digest.equals(saved.getString("digest"))) throw new Exception("Conflicting moderation snapshot rejected.");
                return false;
            }
        }
        // `data` is saved encrypted with the Android Keystore key (SecureStore), like iOS keeps the pin in the keychain.
        data.put("moderation", new JSONObject().put("sequence", sequence).put("digest", digest).put("payload", payloadB64).put("accepted_ms", now));
        return true;
    }

    /** Same checks as ModerationProtocol.validate + verify (time window), rejecting the whole list on any failure. */
    static void validate(JSONObject p, long nowSeconds) throws Exception {
        Set<String> keys = new HashSet<>(java.util.Arrays.asList("version", "scope", "sequence", "issuedAt", "validUntil", "decisions"));
        for (Iterator<String> it = p.keys(); it.hasNext();) if (!keys.contains(it.next())) throw new Exception("Invalid moderation snapshot.");
        long issued = p.getLong("issuedAt"), until = p.getLong("validUntil");
        JSONArray decisions = p.getJSONArray("decisions");
        if (p.getInt("version") != 1 || !SCOPE.equals(p.getString("scope")) || p.getLong("sequence") <= 0 || issued <= 0
            || until <= issued || until - issued > 7 * 86400 || decisions.length() > 1000) throw new Exception("Invalid moderation snapshot.");
        if (issued > nowSeconds + 300) throw new Exception("Moderation snapshot is from the future.");
        Set<String> ids = new HashSet<>(), targets = new HashSet<>();
        for (int i = 0; i < decisions.length(); i++) {
            JSONObject d = decisions.getJSONObject(i);
            String id = d.getString("id"), kind = d.getString("kind"), target = d.getString("target"), reason = d.getString("reason");
            long expires = d.getLong("expiresAt");
            boolean uuid; try { uuid = UUID.fromString(id) != null && id.length() == 36; } catch (IllegalArgumentException bad) { uuid = false; }
            if (!uuid || !ids.add(id.toUpperCase(java.util.Locale.ROOT)) || !(kind.equals("message") || kind.equals("identity"))
                || !target.matches("[0-9a-f]{64}") || !REASONS.contains(reason) || expires <= issued || expires - issued > 30L * 86400
                || !targets.add(kind + ":" + target)) throw new Exception("Invalid or duplicate moderation decision.");
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n;
        while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); if (out.size() > limit) throw new Exception("Moderation list exceeds its size limit."); }
        return out.toByteArray();
    }
}
