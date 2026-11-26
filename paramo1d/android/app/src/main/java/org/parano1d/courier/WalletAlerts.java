package org.parano1d.courier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Wallet activity alerts, ported from iOS WalletActivityNotifications: local alerts from state this phone verified
 * itself. No key or address goes to a notification server, and an alert never shows an address or an amount.
 * - Verified NOID balance increases, verified token and NFT additions, NFT ownership changes to review, and
 *   finalized outgoing payments: each off by default, as on iOS.
 * - New private messages: on by default on Android (Android has always alerted for them; iOS asks first).
 * - "Show alerts while the app is open": on by default, as on iOS.
 */
final class WalletAlerts {
    private WalletAlerts() {}
    static final String PREFS = "wallet-alerts";
    static final String BALANCE = "balance", ASSETS = "assets", NFT_CHANGES = "nft_changes", SENDS = "sends", MESSAGES = "messages", FOREGROUND = "foreground";
    private static final String CHANNEL = "wallet-activity";

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static boolean on(Context context, String key) {
        boolean fallback = key.equals(MESSAGES) || key.equals(FOREGROUND);
        return prefs(context).getBoolean(key, fallback);
    }
    static JSONObject state(Context context) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : new String[]{BALANCE, ASSETS, NFT_CHANGES, SENDS, MESSAGES, FOREGROUND}) result.put(key, on(context, key));
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return result.put("allowed", manager != null && manager.areNotificationsEnabled());
    }
    static void set(Context context, JSONObject request) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (String key : new String[]{BALANCE, ASSETS, NFT_CHANGES, SENDS, MESSAGES, FOREGROUND}) if (request.has(key)) editor.putBoolean(key, request.optBoolean(key));
        editor.apply();
    }

    private static void post(Context context, String id, String title, String body, boolean appVisible) {
        if (appVisible && !on(context, FOREGROUND)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Wallet activity", NotificationManager.IMPORTANCE_DEFAULT));
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(Math.floorMod(id.hashCode(), 1_000_000) + 2_000_000, new Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_message).setContentTitle(title).setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true)
            .setLocalOnly(true).build());
    }

    /** After an on-device verification: alert when the verified NOID balance went up (never the amount). */
    static void observeBalance(Context context, String owner, String balanceNoid, long height, boolean appVisible) {
        if (owner == null || !WalletKeys.validAddress(owner) || height <= 0 || balanceNoid == null || balanceNoid.isEmpty()) return;
        String current;
        try { current = WalletKeys.micronoid(balanceNoid); } catch (Exception bad) { return; }
        String key = "verified-balance-" + owner;
        String previous = prefs(context).getString(key, null);
        if (current.equals(previous)) return;
        prefs(context).edit().putString(key, current).apply();
        if (previous != null && new BigInteger(current).compareTo(new BigInteger(previous)) > 0 && on(context, BALANCE))
            post(context, "balance-" + owner + "-" + height, "NOID balance increased", "A newer on-device verification shows a higher NOID balance. Open the wallet for details.", appVisible);
    }

    /** After the phone's verified holdings load: token increases, NFTs added, and NFTs that are no longer held. */
    static void observeAssets(Context context, String owner, JSONObject holdings, boolean appVisible) {
        if (owner == null || !WalletKeys.validAddress(owner) || holdings == null) return;
        long height = holdings.optLong("height");
        JSONArray items = holdings.optJSONArray("items");
        if (height <= 0 || items == null) return;
        Map<String, BigInteger> tokens = new HashMap<>();
        Set<String> nfts = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i); if (item == null || !owner.equals(item.optString("owner"))) continue;
            if ("token".equals(item.optString("type")) && item.optString("asset_id").matches("[0-9a-f]{64}")) {
                try { tokens.merge(item.getString("asset_id"), new BigInteger(item.optString("units", "0")), BigInteger::add); } catch (Exception bad) { return; }
            } else if ("nft".equals(item.optString("type")) && item.optString("collection_id").matches("[0-9a-f]{64}") && !item.optString("serial").isEmpty()) {
                nfts.add(item.optString("collection_id") + "#" + item.optString("serial"));
            }
        }
        String key = "verified-assets-" + owner;
        String saved = prefs(context).getString(key, null);
        JSONObject snapshot = new JSONObject();
        try {
            JSONObject t = new JSONObject(); for (Map.Entry<String, BigInteger> e : tokens.entrySet()) t.put(e.getKey(), e.getValue().toString());
            snapshot.put("tokens", t).put("nfts", new JSONArray(new java.util.TreeSet<>(nfts)));
        } catch (Exception ignored) { return; }
        prefs(context).edit().putString(key, snapshot.toString()).apply();
        if (saved == null) return;
        Map<String, BigInteger> formerTokens = new HashMap<>(); Set<String> formerNfts = new HashSet<>();
        try {
            JSONObject old = new JSONObject(saved), t = old.optJSONObject("tokens");
            if (t != null) for (java.util.Iterator<String> k = t.keys(); k.hasNext(); ) { String id = k.next(); formerTokens.put(id, new BigInteger(t.getString(id))); }
            JSONArray n = old.optJSONArray("nfts"); if (n != null) for (int i = 0; i < n.length(); i++) formerNfts.add(n.getString(i));
        } catch (Exception damaged) { return; }
        if (on(context, ASSETS)) {
            boolean more = false; for (Map.Entry<String, BigInteger> e : tokens.entrySet()) if (e.getValue().compareTo(formerTokens.getOrDefault(e.getKey(), BigInteger.ZERO)) > 0) more = true;
            if (more) post(context, "token-increase-" + owner + "-" + height, "Verified token balance increased", "The on-device node found more tokens in your wallet. Open the NFTs tab to review.", appVisible);
            Set<String> added = new HashSet<>(nfts); added.removeAll(formerNfts);
            if (!added.isEmpty()) post(context, "nft-added-" + owner + "-" + height, "NFT added to your wallet", "The on-device node verified an NFT in your wallet. Open the gallery to review.", appVisible);
        }
        Set<String> gone = new HashSet<>(formerNfts); gone.removeAll(nfts);
        if (!gone.isEmpty() && on(context, NFT_CHANGES))
            post(context, "nft-changed-" + owner + "-" + height, "NFT ownership changed", "A previously held NFT is absent from the newly verified holdings. Review its transaction and listing status before treating it as sold.", appVisible);
    }

    /** A payment this phone sent was verified as included. */
    static void finalizedSend(Context context, String txid, boolean appVisible) {
        if (!on(context, SENDS) || txid == null) return;
        post(context, "send-final-" + txid, "Payment confirmed", "Your phone verified the saved payment's receipt. Open transaction history for details.", appVisible);
    }
}
