package org.parano1d.courier;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * iOS "Wallets and accounts" (AccountsView / WalletVault): several master keys on this phone, each with accounts
 * derived from it (spend_secret(master, index)). Stored inside the encrypted wallet data:
 *   data.wallets = [{root, origin?, seed_hex?, name, hidden?, accounts:[{index,name,address,used?}], selected}]
 *   data.selected_root, data.default_root
 * The first ("origin") wallet is the one whose key is data.seed_hex. That key also seals this phone's chat identity,
 * so chat stays the same whichever wallet or account is selected; only the wallet side (balance, sending, receiving,
 * NFTs) follows the selection. Separate keys never leave the encrypted store and are never shown in the list.
 */
final class WalletAccounts {
    private WalletAccounts() {}
    static final int MAX_WALLETS = 16, MAX_ACCOUNTS = 100;

    interface Deriver { String address(String seedHex, int index) throws Exception; }

    /** Creates the list for a phone that has one wallet (every install before this feature). */
    static void ensure(JSONObject data) throws Exception {
        if (!data.has("seed_hex") || data.optJSONArray("wallets") != null && data.getJSONArray("wallets").length() > 0) return;
        JSONObject profile = data.optJSONObject("profile"); if (profile == null) return;
        String root = profile.getString("address");
        JSONObject origin = new JSONObject().put("root", root).put("origin", true).put("name", data.optString("wallet_name", "Main wallet").isEmpty() ? "Main wallet" : data.optString("wallet_name", "Main wallet"))
            .put("accounts", new JSONArray().put(new JSONObject().put("index", 0).put("name", "Account 1").put("address", root))).put("selected", 0);
        data.put("wallets", new JSONArray().put(origin)).put("selected_root", root);
    }
    static JSONArray wallets(JSONObject data) { JSONArray list = data.optJSONArray("wallets"); return list == null ? new JSONArray() : list; }
    static JSONObject find(JSONObject data, String root) throws Exception {
        JSONArray list = wallets(data);
        for (int i = 0; i < list.length(); i++) if (list.getJSONObject(i).getString("root").equals(root)) return list.getJSONObject(i);
        throw new Exception("That wallet is no longer on this phone.");
    }
    static JSONObject active(JSONObject data) {
        try { return find(data, data.getString("selected_root")); } catch (Exception none) { return null; }
    }
    static String seed(JSONObject data, JSONObject wallet) throws Exception { return wallet.optBoolean("origin") ? data.getString("seed_hex") : wallet.getString("seed_hex"); }
    static String activeSeed(JSONObject data) throws Exception { JSONObject wallet = active(data); return wallet == null ? data.getString("seed_hex") : seed(data, wallet); }
    static int activeIndex(JSONObject data) { JSONObject wallet = active(data); return wallet == null ? 0 : wallet.optInt("selected"); }
    static JSONObject account(JSONObject wallet, int index) throws Exception {
        JSONArray accounts = wallet.getJSONArray("accounts");
        for (int i = 0; i < accounts.length(); i++) if (accounts.getJSONObject(i).getInt("index") == index) return accounts.getJSONObject(i);
        throw new Exception("That account is no longer in the list.");
    }
    /** The address Home, Receive, NFTs and alerts use: the selected account's. */
    static String activeAddress(JSONObject data) throws Exception {
        JSONObject wallet = active(data);
        if (wallet == null) return data.getJSONObject("profile").getString("address");
        return account(wallet, wallet.optInt("selected")).getString("address");
    }
    static String activeName(JSONObject data) {
        JSONObject wallet = active(data); if (wallet == null) return data.optString("wallet_name", "");
        try { String account = account(wallet, wallet.optInt("selected")).optString("name"); return wallets(data).length() > 1 || wallet.getJSONArray("accounts").length() > 1 ? wallet.optString("name") + " · " + account : wallet.optString("name"); }
        catch (Exception e) { return wallet.optString("name"); }
    }

    /** What the page may see: names, addresses and flags, never a key. */
    static JSONObject summary(JSONObject data) throws Exception {
        JSONArray out = new JSONArray(), list = wallets(data);
        for (int i = 0; i < list.length(); i++) {
            JSONObject w = list.getJSONObject(i);
            out.put(new JSONObject().put("root", w.getString("root")).put("name", w.optString("name")).put("hidden", w.optBoolean("hidden")).put("origin", w.optBoolean("origin"))
                .put("accounts", new JSONArray(w.getJSONArray("accounts").toString())).put("selected", w.optInt("selected")).put("scan_next", w.optInt("scan_next", 0)));
        }
        return new JSONObject().put("wallets", out).put("selected_root", data.optString("selected_root")).put("default_root", data.optString("default_root", data.optString("selected_root")));
    }

    static String validName(String name) throws Exception {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 40 || clean.codePoints().anyMatch(Character::isISOControl)) throw new Exception("Use a name of 1 to 40 characters.");
        return clean;
    }
    static void select(JSONObject data, String root, int index) throws Exception {
        JSONObject wallet = find(data, root); account(wallet, index);
        wallet.put("selected", index); data.put("selected_root", root);
    }
    static void addAccount(JSONObject data, Deriver derive) throws Exception {
        JSONObject wallet = active(data); if (wallet == null) throw new Exception("Create or restore your wallet first.");
        JSONArray accounts = wallet.getJSONArray("accounts");
        if (accounts.length() >= MAX_ACCOUNTS) throw new Exception("This key already has " + MAX_ACCOUNTS + " accounts in the list.");
        int next = 0; for (int i = 0; i < accounts.length(); i++) next = Math.max(next, accounts.getJSONObject(i).getInt("index") + 1);
        if (next > 100000) throw new Exception("Account index outside wallet range");
        accounts.put(new JSONObject().put("index", next).put("name", "Account " + (next + 1)).put("address", derive.address(seed(data, wallet), next)));
        wallet.put("selected", next);
    }
    static void renameRoot(JSONObject data, String root, String name) throws Exception { find(data, root).put("name", validName(name)); }
    static void renameAccount(JSONObject data, String root, int index, String name) throws Exception { account(find(data, root), index).put("name", validName(name)); }
    static void setDefault(JSONObject data, String root) throws Exception { find(data, root); data.put("default_root", root); }
    static void move(JSONObject data, String root, int by) throws Exception {
        JSONArray list = wallets(data); int at = -1;
        for (int i = 0; i < list.length(); i++) if (list.getJSONObject(i).getString("root").equals(root)) at = i;
        int to = at + by; if (at < 0 || to < 0 || to >= list.length()) return;
        JSONObject a = list.getJSONObject(at); list.put(at, list.getJSONObject(to)); list.put(to, a);
    }
    /** iOS 200 "Reorder wallets": the new order must name every wallet on this phone exactly once. */
    static void reorder(JSONObject data, JSONArray roots) throws Exception {
        JSONArray list = wallets(data);
        if (roots == null || roots.length() != list.length()) throw new Exception("The wallet list changed. Try again.");
        JSONArray next = new JSONArray(); java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < roots.length(); i++) {
            String root = roots.optString(i);
            if (!seen.add(root)) throw new Exception("The wallet list changed. Try again.");
            JSONObject wallet = null;
            for (int j = 0; j < list.length(); j++) if (list.getJSONObject(j).getString("root").equals(root)) wallet = list.getJSONObject(j);
            if (wallet == null) throw new Exception("The wallet list changed. Try again.");
            next.put(wallet);
        }
        for (int i = 0; i < next.length(); i++) list.put(i, next.get(i));
    }
    static void setHidden(JSONObject data, String root, boolean hidden) throws Exception {
        JSONObject wallet = find(data, root);
        if (hidden) { int visible = 0; JSONArray list = wallets(data); for (int i = 0; i < list.length(); i++) if (!list.getJSONObject(i).optBoolean("hidden")) visible++; if (visible <= 1) throw new Exception("Keep at least one wallet in the list."); }
        wallet.put("hidden", hidden);
    }
    static void removeAccount(JSONObject data, String root, int index) throws Exception {
        JSONObject wallet = find(data, root); JSONArray accounts = wallet.getJSONArray("accounts");
        if (accounts.length() <= 1) throw new Exception("A wallet keeps at least one account. Remove the wallet instead.");
        JSONArray kept = new JSONArray(); for (int i = 0; i < accounts.length(); i++) if (accounts.getJSONObject(i).getInt("index") != index) kept.put(accounts.getJSONObject(i));
        wallet.put("accounts", kept);
        if (wallet.optInt("selected") == index) wallet.put("selected", kept.getJSONObject(0).getInt("index"));
    }
    /** Removes a separate key and its list. The first wallet holds this phone's chat identity, so it stays. */
    static void removeRoot(JSONObject data, String root) throws Exception {
        JSONObject wallet = find(data, root);
        if (wallet.optBoolean("origin")) throw new Exception("The first wallet also holds this phone's chat identity, so it can't be removed here. Use Delete wallet from this phone to remove everything.");
        JSONArray list = wallets(data), kept = new JSONArray();
        for (int i = 0; i < list.length(); i++) if (!list.getJSONObject(i).getString("root").equals(root)) kept.put(list.getJSONObject(i));
        data.put("wallets", kept);
        String first = kept.getJSONObject(0).getString("root");
        if (root.equals(data.optString("selected_root"))) data.put("selected_root", first);
        if (root.equals(data.optString("default_root"))) data.remove("default_root");
    }
    /** Adds a separate master key (imported or newly created and verified). */
    static void addKey(JSONObject data, String seedHex, String name, Deriver derive) throws Exception {
        JSONArray list = wallets(data);
        if (list.length() >= MAX_WALLETS) throw new Exception("This phone already holds " + MAX_WALLETS + " wallets.");
        String root = derive.address(seedHex, 0);
        for (int i = 0; i < list.length(); i++) if (list.getJSONObject(i).getString("root").equals(root)) throw new Exception("That master key is already on this phone.");
        String label = name == null || name.trim().isEmpty() ? "Wallet " + (list.length() + 1) : validName(name);
        list.put(new JSONObject().put("root", root).put("seed_hex", seedHex).put("name", label)
            .put("accounts", new JSONArray().put(new JSONObject().put("index", 0).put("name", "Account 1").put("address", root))).put("selected", 0));
        data.put("wallets", list).put("selected_root", root);
    }
    /** The opening wallet after an unlock (iOS "Your default opens at the next unlock"). */
    static boolean openDefault(JSONObject data) {
        String root = data.optString("default_root", "");
        if (root.isEmpty() || root.equals(data.optString("selected_root"))) return false;
        try { find(data, root); data.put("selected_root", root); return true; } catch (Exception gone) { data.remove("default_root"); return false; }
    }
}
