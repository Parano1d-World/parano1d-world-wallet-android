package org.parano1d.courier;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Mining pairing with the owner's own rig (iOS 185 MiningView). This phone never mines: it pairs with the rig
 * companion through parano1dworld.com, reads the status the rig reports, and sends signed start/stop commands.
 * The rig alone decides the payout address, pool and miner programs.
 *
 *   POST https://parano1dworld.com/api/rig/pair {code, jwk:{kty,crv,x,y}, name} -> {deviceId, fingerprint, name, rigId?}
 *   POST https://parano1dworld.com/api/rig/cmd  {deviceId, ts, nonce, cmd, args, sig[, rig]}
 *   sig = ECDSA P-256 / SHA-256 over "p1w-rig-v1\n<deviceId>\n<ts>\n<nonce>\n<cmd>\n<JSON args>", raw r||s, base64url.
 *
 * Storage, all on this phone only: the computer list in SharedPreferences "mining" (app backup is off), and one
 * non-exportable Android Keystore key per computer (StrongBox when the phone has it). Locking the app leaves both
 * in place; the keys need the phone to be unlocked to sign, like iOS's WhenUnlockedThisDeviceOnly.
 */
final class Mining {
    static final String ORIGIN = "https://parano1dworld.com";
    private static final String PREFS = "mining", LIST = "rigs.v1", KEYSTORE = "AndroidKeyStore", ALIAS = "parano1d-rig-";
    private static final int MAX_RIGS = 16, MAX_REQUEST = 4096, MAX_RESPONSE = 64_000;
    private static final SecureRandom random = new SecureRandom();
    private static final okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build();

    /** Sends a request through App VPN's protected channel while it is on (MainActivity supplies it). */
    interface Tunnel { JSONObject post(String url, String body, JSONObject headers) throws Exception; }

    static final class Failure extends Exception {
        final int status; final boolean offline; final String code;
        Failure(String message, int status, boolean offline) { this(message, status, offline, ""); }
        Failure(String message, int status, boolean offline, String code) { super(message); this.status = status; this.offline = offline; this.code = code == null ? "" : code; }
        /** The computer's owner switched "Allow control from a phone" off (HTTP 423). The pairing stays valid. */
        boolean phoneControlOff() { return "phone-control-off".equals(code); }
    }

    private Mining() {}

    // ------------------------------------------------------------------ pairing codes

    /** The rig's pairing QR (https://parano1dworld.com/#Mining/pair/CODE or /mining/pair/CODE), paramo1d://mining/pair/CODE,
     *  or the 12-character code itself (dashes optional). Anything else, including other sites, returns null. */
    static String parseCode(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 300) return null;
        String candidate = text;
        int colon = text.indexOf("://");
        if (colon > 0) {
            try {
                java.net.URI uri = new java.net.URI(text);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
                String path = uri.getRawPath() == null ? "" : uri.getRawPath();
                String fragment = uri.getRawFragment() == null ? "" : uri.getRawFragment();
                if (scheme.equals("https")) {
                    if (!(host.equals("parano1dworld.com") || host.equals("www.parano1dworld.com")) || uri.getRawUserInfo() != null || uri.getPort() != -1) return null;
                    if (fragment.toLowerCase(java.util.Locale.ROOT).startsWith("mining/pair/")) candidate = fragment.substring("mining/pair/".length());
                    else if (path.toLowerCase(java.util.Locale.ROOT).startsWith("/mining/pair/")) candidate = path.substring("/mining/pair/".length());
                    else return null;
                } else if (scheme.equals("paramo1d")) {
                    if (!host.equals("mining") || !path.toLowerCase(java.util.Locale.ROOT).startsWith("/pair/")) return null;
                    candidate = path.substring("/pair/".length());
                } else return null;
            } catch (Exception bad) { return null; }
        }
        String code = candidate.replace("-", "").toUpperCase(java.util.Locale.ROOT);
        return code.matches("[A-Z0-9]{12}") ? code : null;
    }
    static String display(String code) { return code.length() == 12 ? code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8) : code; }

    // ------------------------------------------------------------------ saved computers

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static synchronized JSONArray list(Context context) {
        try { return new JSONArray(prefs(context).getString(LIST, "[]")); } catch (Exception damaged) { return new JSONArray(); }
    }
    private static synchronized void save(Context context, JSONArray rigs) {
        if (!prefs(context).edit().putString(LIST, rigs.toString()).commit()) throw new IllegalStateException("Couldn't save the mining computers on this phone.");
    }
    static JSONObject find(Context context, String id) throws Exception {
        JSONArray rigs = list(context);
        for (int i = 0; i < rigs.length(); i++) if (rigs.getJSONObject(i).getString("id").equals(id)) return rigs.getJSONObject(i);
        throw new Exception("That mining computer isn't paired with this phone.");
    }
    static synchronized void rename(Context context, String id, String label) throws Exception {
        String clean = cleanLabel(label);
        if (clean.isEmpty()) return;
        JSONArray rigs = list(context);
        for (int i = 0; i < rigs.length(); i++) if (rigs.getJSONObject(i).getString("id").equals(id)) rigs.getJSONObject(i).put("label", clean);
        save(context, rigs);
    }
    /** Removes the computer and deletes its key from this phone. Mining on the computer is not affected. */
    static synchronized void remove(Context context, String id) throws Exception {
        JSONArray rigs = list(context), kept = new JSONArray();
        for (int i = 0; i < rigs.length(); i++) if (!rigs.getJSONObject(i).getString("id").equals(id)) kept.put(rigs.getJSONObject(i));
        save(context, kept);
        deleteKey(id);
    }
    /** iOS 200 "Reorder computers": saves a new order; it must name every paired computer exactly once. */
    static synchronized void reorder(Context context, JSONArray ids) throws Exception {
        JSONArray rigs = list(context), next = new JSONArray(); java.util.Set<String> seen = new java.util.HashSet<>();
        if (ids == null || ids.length() != rigs.length()) throw new Exception("The list of computers changed. Try again.");
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i); JSONObject rig = null;
            if (!seen.add(id)) throw new Exception("The list of computers changed. Try again.");
            for (int j = 0; j < rigs.length(); j++) if (rigs.getJSONObject(j).getString("id").equals(id)) rig = rigs.getJSONObject(j);
            if (rig == null) throw new Exception("The list of computers changed. Try again.");
            next.put(rig);
        }
        save(context, next);
    }
    static String cleanLabel(String label) {
        String text = label == null ? "" : label.replaceAll("[\\p{Cntrl}]", " ").trim();
        return text.length() > 40 ? text.substring(0, 40) : text;
    }

    // ------------------------------------------------------------------ keys (Android Keystore, never exported)

    private static boolean createKey(String id) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) {
            try { generate(id, true); return true; }
            catch (Exception noStrongBox) { deleteKey(id); }
        }
        generate(id, false); return false;
    }
    private static void generate(String id, boolean strongBox) throws Exception {
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(ALIAS + id, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256);
        if (Build.VERSION.SDK_INT >= 28) { spec.setUnlockedDeviceRequired(true); if (strongBox) spec.setIsStrongBoxBacked(true); }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        generator.initialize(spec.build());
        generator.generateKeyPair();
    }
    private static KeyStore keystore() throws Exception { KeyStore store = KeyStore.getInstance(KEYSTORE); store.load(null); return store; }
    private static void deleteKey(String id) { try { keystore().deleteEntry(ALIAS + id); } catch (Exception ignored) { } }
    private static JSONObject jwk(String id) throws Exception {
        ECPublicKey key = (ECPublicKey) keystore().getCertificate(ALIAS + id).getPublicKey();
        return new JSONObject().put("kty", "EC").put("crv", "P-256")
            .put("x", base64url(fixed(key.getW().getAffineX()))).put("y", base64url(fixed(key.getW().getAffineY())));
    }
    private static byte[] sign(String id, byte[] message) throws Exception {
        KeyStore.Entry entry = keystore().getEntry(ALIAS + id, null);
        if (!(entry instanceof KeyStore.PrivateKeyEntry)) throw new Exception("This phone's mining key is missing. Unpair and pair again.");
        PrivateKey key = ((KeyStore.PrivateKeyEntry) entry).getPrivateKey();
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key); signer.update(message);
        return derToRaw(signer.sign());
    }
    /** Keystore signatures are DER; the rig expects IEEE P1363 r||s (64 bytes), the same as WebCrypto and iOS. */
    static byte[] derToRaw(byte[] der) throws Exception {
        int i = 0;
        if (der[i++] != 0x30) throw new Exception("Unexpected signature format.");
        int length = der[i++] & 0xff; if ((length & 0x80) != 0) i += length & 0x7f;
        byte[][] parts = new byte[2][];
        for (int p = 0; p < 2; p++) {
            if (der[i++] != 0x02) throw new Exception("Unexpected signature format.");
            int size = der[i++] & 0xff;
            byte[] value = new byte[size]; System.arraycopy(der, i, value, 0, size); i += size;
            parts[p] = fixed(new BigInteger(1, value));
        }
        byte[] raw = new byte[64]; System.arraycopy(parts[0], 0, raw, 0, 32); System.arraycopy(parts[1], 0, raw, 32, 32);
        return raw;
    }
    private static byte[] fixed(BigInteger value) {
        byte[] bytes = value.toByteArray(), out = new byte[32];
        int copy = Math.min(32, bytes.length);
        System.arraycopy(bytes, bytes.length - copy, out, 32 - copy, copy);
        return out;
    }
    static String base64url(byte[] bytes) { return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); }

    // ------------------------------------------------------------------ requests

    private static JSONObject post(String name, JSONObject body, Tunnel tunnel) throws Exception {
        String url = ORIGIN + "/api/rig/" + name, text = body.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST) throw new Failure("Request too large.", 413, false);
        JSONObject json; int status;
        if (tunnel != null) {
            JSONObject headers = new JSONObject().put("Content-Type", "application/json").put("Accept", "application/json").put("X-P1W-Client", "parano1d-wallet-android");
            try { json = tunnel.post(url, text, headers); status = 200; }
            catch (Failure failure) { throw failure; }
            catch (Exception failed) { throw new Failure("Couldn't reach parano1dworld.com through App VPN. " + String.valueOf(failed.getMessage()), 0, false); }
        } else {
            okhttp3.Request request = new okhttp3.Request.Builder().url(url)
                .header("Accept", "application/json").header("X-P1W-Client", "parano1d-wallet-android")
                .post(okhttp3.RequestBody.create(text.getBytes(StandardCharsets.UTF_8), okhttp3.MediaType.get("application/json"))).build();
            try (okhttp3.Response response = client.newCall(request).execute()) {
                status = response.code();
                if (response.body() == null) throw new Failure("The mining service returned an unreadable answer.", status, false);
                try (InputStream input = response.body().byteStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) >= 0) { out.write(buffer, 0, count); if (out.size() > MAX_RESPONSE) throw new Failure("The mining service returned an unreadable answer.", status, false); }
                    try { json = new JSONObject(out.toString(StandardCharsets.UTF_8.name())); }
                    catch (Exception unreadable) { throw new Failure("The mining service returned an unreadable answer.", status, false); }
                }
            } catch (Failure failure) { throw failure; }
            catch (java.io.IOException unreachable) { throw new Failure("Couldn't reach parano1dworld.com. Check your connection.", 0, false); }
        }
        if (status != 200 || !json.optBoolean("ok", true)) {
            String message = json.optString("error", "Mining request failed.");
            if (message.length() > 200) message = message.substring(0, 200);
            throw new Failure(message, status, "offline".equals(json.optString("rig")), json.optString("code"));
        }
        return json;
    }

    /** Pairs with the rig: makes this computer's key, registers its public half, and saves the computer. */
    static JSONObject pair(Context context, String code, String label, Tunnel tunnel) throws Exception {
        if (list(context).length() >= MAX_RIGS) throw new Exception("Unpair a computer first: this phone holds up to " + MAX_RIGS + ".");
        String id = UUID.randomUUID().toString();
        boolean strongBox = createKey(id);
        try {
            String name = "Android phone";
            JSONObject result = post("pair", new JSONObject().put("code", display(code)).put("jwk", jwk(id)).put("name", name), tunnel);
            String deviceId = result.optString("deviceId");
            if (deviceId.isEmpty() || deviceId.length() > 128) throw new Failure("The rig did not confirm the pairing.", 502, false);
            String rigId = result.optString("rigId", "");
            JSONObject pairing = new JSONObject().put("id", id).put("label", cleanLabel(label).isEmpty() ? "Mining computer" : cleanLabel(label))
                .put("deviceId", deviceId).put("fingerprint", result.optString("fingerprint", "")).put("deviceName", result.optString("name", name))
                .put("strongBox", strongBox).put("pairedAt", System.currentTimeMillis());
            if (!rigId.isEmpty() && rigId.length() <= 128) pairing.put("rigId", rigId);
            synchronized (Mining.class) { JSONArray rigs = list(context); rigs.put(pairing); save(context, rigs); }
            return pairing;
        } catch (Exception failed) { deleteKey(id); throw failed; }
    }

    static String canonical(String deviceId, long ts, String nonce, String cmd, String argsJson) {
        return "p1w-rig-v1\n" + deviceId + "\n" + ts + "\n" + nonce + "\n" + cmd + "\n" + argsJson;
    }

    /** Sends a signed command. Only status, start and stop exist (the rig rejects anything else). */
    static JSONObject command(Context context, String id, String cmd, Tunnel tunnel) throws Exception {
        if (!(cmd.equals("status") || cmd.equals("start") || cmd.equals("stop"))) throw new Exception("Unknown mining command.");
        JSONObject pairing = find(context, id);
        // Matches JavaScript's JSON.stringify for the one flat argument the app sends.
        JSONObject args = new JSONObject(); String argsJson = "{}";
        if (cmd.equals("start")) { args.put("mode", "preferred"); argsJson = "{\"mode\":\"preferred\"}"; }
        long ts = System.currentTimeMillis();
        byte[] nonceBytes = new byte[18]; random.nextBytes(nonceBytes);
        String nonce = base64url(nonceBytes), deviceId = pairing.getString("deviceId");
        byte[] signature = sign(id, canonical(deviceId, ts, nonce, cmd, argsJson).getBytes(StandardCharsets.UTF_8));
        JSONObject body = new JSONObject().put("deviceId", deviceId).put("ts", ts).put("nonce", nonce).put("cmd", cmd).put("args", args).put("sig", base64url(signature));
        if (pairing.has("rigId")) body.put("rig", pairing.getString("rigId"));
        return post("cmd", body, tunnel);
    }
}
