package org.parano1d.courier;

import java.util.Locale;

/** Master key and address checks for wallet setup (iOS WalletSetupView / AddressValidator). No Android dependencies. */
final class WalletKeys {
    static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(bytes.length * 2); for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString(); }
    static byte[] unhex(String text) {
        if (text == null || text.length() % 2 != 0 || !text.matches("[0-9a-f]*")) throw new IllegalArgumentException("Invalid hexadecimal");
        byte[] out = new byte[text.length() / 2]; for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16); return out;
    }
    private WalletKeys() {}

    /** A 64-character hexadecimal master key, as exported by the official Parano1d wallet (iOS recover_key). */
    static String masterKey(String raw) throws Exception {
        String key = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f]", "");
        if (!key.matches("[0-9a-f]{64}")) throw new Exception("Enter all 64 characters of the master key (letters A–F and digits 0–9).");
        return key;
    }

    /** Bech32m NOID address check, ported from iOS AddressValidator. */
    static boolean validAddress(String s) {
        if (s == null || s.length() != 60 || !s.startsWith("o1") || !s.equals(s.toLowerCase(Locale.ROOT))) return false;
        String alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
        int[] values = new int[58];
        for (int i = 0; i < 58; i++) { int v = alphabet.indexOf(s.charAt(i + 2)); if (v < 0) return false; values[i] = v; }
        long chk = 1;
        int[] gen = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        int[] prefix = {3, 0, 15};
        for (int k = 0; k < 61; k++) {
            int v = k < 3 ? prefix[k] : values[k - 3];
            long top = chk >> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ v;
            for (int i = 0; i < 5; i++) if (((top >> i) & 1) != 0) chk ^= gen[i];
        }
        return chk == 0x2bc830a3L && (values[51] & 15) == 0;
    }

    /**
     * Audit 176: every payment authorization states where it was approved; the core refuses anything else.
     * "watch" (set only by WatchBridge) carries exactly what the Wear OS screen showed; "phone" carries none of it.
     */
    static org.json.JSONObject approval(org.json.JSONObject args, org.json.JSONObject request) throws Exception {
        if (!request.optBoolean("watch")) return args.put("approval_source", "phone");
        return args.put("approval_source", "watch")
            .put("confirmed_to", request.getString("confirmed_to"))
            .put("confirmed_amount_micronoid", request.getString("confirmed_amount_micronoid"))
            .put("confirmed_review_id", request.getString("confirmed_review_id"));
    }

    /** "1.250000" NOID to "1250000" micronoid, exactly (refuses anything finer than a micronoid). */
    static String micronoid(String noid) { return new java.math.BigDecimal(noid).movePointRight(6).toBigIntegerExact().toString(); }
}
