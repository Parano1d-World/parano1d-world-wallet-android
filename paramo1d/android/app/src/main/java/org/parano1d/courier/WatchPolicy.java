package org.parano1d.courier;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Watch rules: sends to saved contacts, no amount limits (owner's decision),
 * one-time reviews that expire after 90 s, PIN confirmation checked by the phone,
 * PIN sessions that idle out after 2 min and end after 15 min at most.
 */
final class WatchPolicy {
    static final long REVIEW_MS = 90_000;
    static final long SESSION_IDLE_MS = 2 * 60_000;
    static final long SESSION_MAX_MS = 30 * 60_000; // iOS: 2-minute idle, 30-minute cap, survives wrist-down
    static final long CONFIRM_MS = 60_000;
    private static final String PREFS = "watch-policy";

    private WatchPolicy() {}

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean sendsEnabled(Context c) { return prefs(c).getBoolean("sends_enabled", false); }
    static boolean confirmPhoneSends(Context c) { return prefs(c).getBoolean("confirm_phone_sends", false); }
    static boolean hideBalances(Context c) { return prefs(c).getBoolean("hide_balances", false); }
    static boolean showArtwork(Context c) { return prefs(c).getBoolean("show_artwork", false); }

    static JSONObject settings(Context c) throws Exception {
        return new JSONObject()
            .put("sends_enabled", sendsEnabled(c))
            .put("confirm_phone_sends", confirmPhoneSends(c))
            .put("hide_balances", hideBalances(c))
            .put("show_artwork", showArtwork(c))
            .put("pin", WalletPin.state(c));
    }

    /** Applies settings from the phone UI. The phone UI is already behind App lock. */
    static void update(Context c, JSONObject request) throws Exception {
        SharedPreferences.Editor edit = prefs(c).edit();
        if (request.has("sends_enabled")) {
            boolean on = request.getBoolean("sends_enabled");
            if (on && !WalletPin.isSet(c)) throw new Exception("Set a wallet PIN before turning on watch sends.");
            edit.putBoolean("sends_enabled", on);
        }
        if (request.has("confirm_phone_sends")) edit.putBoolean("confirm_phone_sends", request.getBoolean("confirm_phone_sends"));
        if (request.has("hide_balances")) edit.putBoolean("hide_balances", request.getBoolean("hide_balances"));
        if (request.has("show_artwork")) edit.putBoolean("show_artwork", request.getBoolean("show_artwork"));
        edit.apply();
    }

    static BigDecimal amount(String text) throws Exception {
        String value = text == null ? "" : text.trim().replace(',', '.');
        if (!value.matches("\\d+(\\.\\d{1,6})?") || !value.matches(".*[1-9].*")) throw new Exception("Enter an amount greater than zero, with up to six decimal places.");
        return new BigDecimal(value);
    }

    // ---- Watch send history total for today (informational only; never blocks a send) ----

    private static String today() { return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date()); }

    static BigDecimal spentToday(Context c) {
        SharedPreferences p = prefs(c);
        if (!today().equals(p.getString("ledger_day", ""))) return BigDecimal.ZERO;
        return new BigDecimal(p.getString("ledger_amount", "0"));
    }

    static synchronized void recordSigned(Context c, BigDecimal total) {
        BigDecimal next = spentToday(c).add(total);
        prefs(c).edit().putString("ledger_day", today()).putString("ledger_amount", next.toPlainString()).apply();
    }


    // ---- Contact address pinning ("Address changed" hand-off) ----

    static String pinnedAddress(Context c, String contactId) { return prefs(c).getString("address:" + contactId, null); }
    static void pinAddress(Context c, String contactId, String address) { prefs(c).edit().putString("address:" + contactId, address).apply(); }

    // ---- Declined requests ----

    static boolean declined(Context c, String messageId) { return prefs(c).getBoolean("declined:" + messageId, false); }
    static void markDeclined(Context c, String messageId) { prefs(c).edit().putBoolean("declined:" + messageId, true).apply(); }

    // ---- Receipt notification bookkeeping ----

    static boolean receiptAnnounced(Context c, String key) { return prefs(c).getBoolean("receipt:" + key, false); }
    static void markReceiptAnnounced(Context c, String key) { prefs(c).edit().putBoolean("receipt:" + key, true).apply(); }
    static String lastBalance(Context c) { return prefs(c).getString("last_balance", null); }
    static long lastBalanceHeight(Context c) { return prefs(c).getLong("last_balance_height", 0); }
    static void recordBalance(Context c, String balance, long height) { prefs(c).edit().putString("last_balance", balance).putLong("last_balance_height", height).apply(); }

    // ---- PIN sessions ----

    private static final class Session { final long started; long touched; Session(long now) { started = now; touched = now; } }
    private static final Map<String, Session> sessions = new HashMap<>();

    static synchronized String openSession() {
        long now = SystemClock.elapsedRealtime();
        sessions.clear(); // one watch session at a time
        String token = randomHex(16);
        sessions.put(token, new Session(now));
        return token;
    }

    static synchronized boolean touchSession(String token) {
        if (token == null) return false;
        Session s = sessions.get(token);
        long now = SystemClock.elapsedRealtime();
        if (s == null) return false;
        if (now - s.touched > SESSION_IDLE_MS || now - s.started > SESSION_MAX_MS) { sessions.remove(token); return false; }
        s.touched = now;
        return true;
    }

    static synchronized void endSessions() { sessions.clear(); }

    // ---- One-time reviews ----

    static final class Review {
        final String reviewId, contactId, to, amount, fee, total, invoiceMessageId; final long createdAt; boolean used;
        Review(String reviewId, String contactId, String to, String amount, String fee, String total, String invoiceMessageId) {
            this.reviewId = reviewId; this.contactId = contactId; this.to = to; this.amount = amount; this.fee = fee; this.total = total;
            this.invoiceMessageId = invoiceMessageId; this.createdAt = SystemClock.elapsedRealtime();
        }
        boolean expired() { return SystemClock.elapsedRealtime() - createdAt > REVIEW_MS; }
    }
    private static final Map<String, Review> reviews = new HashMap<>();

    static synchronized void putReview(Review review) {
        for (Iterator<Map.Entry<String, Review>> it = reviews.entrySet().iterator(); it.hasNext();) if (it.next().getValue().expired()) it.remove();
        reviews.put(review.reviewId, review);
    }

    /** Takes a review for approval. Returns null if unknown, used or expired. */
    static synchronized Review claimReview(String reviewId) throws Exception {
        Review review = reviews.get(reviewId);
        if (review == null || review.used) throw new Exception("This review was already used. Review the payment again.");
        if (review.expired()) { reviews.remove(reviewId); throw new Exception("The review expired after 90 seconds. Review the payment again."); }
        return review;
    }

    static synchronized void markUsed(Review review) { review.used = true; reviews.remove(review.reviewId); }

    static String randomHex(int count) {
        byte[] bytes = new byte[count]; new SecureRandom().nextBytes(bytes);
        StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }
}
