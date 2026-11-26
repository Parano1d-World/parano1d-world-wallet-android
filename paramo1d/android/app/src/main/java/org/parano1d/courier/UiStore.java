package org.parano1d.courier;

import android.content.Context;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The page's small settings (send layout, favourite contacts, your avatar and the avatars others shared, the Mining
 * tab, gallery layout …). Android's WebView runs with DOM storage off, so the page's localStorage is backed by this
 * encrypted side file instead (ui.pmc, its own AES-GCM context, at most 2 MB). Wallet keys are never stored here.
 */
final class UiStore {
    private UiStore() {}
    private static final int LIMIT = 2 * 1024 * 1024, MAX_KEY = 200, MAX_VALUE = 256 * 1024;
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "parano1d-ui-store"));
    private static SecureStore file;
    private static JSONObject cache;
    private static boolean dirty, scheduled;

    private static synchronized JSONObject load(Context context) {
        if (cache != null) return cache;
        file = new SecureStore(context.getApplicationContext(), "ui.pmc", LIMIT);
        try { cache = file.read(); }
        catch (SecureStore.Damaged damaged) { file.setAside(); cache = new JSONObject(); }
        catch (Exception unreadable) { cache = new JSONObject(); }
        return cache;
    }
    static synchronized String all(Context context) { return load(context).toString(); }
    static synchronized void set(Context context, String key, String value) {
        if (key == null || value == null || key.isEmpty() || key.length() > MAX_KEY || value.length() > MAX_VALUE) return;
        JSONObject data = load(context);
        try {
            String previous = data.optString(key, null);
            if (value.equals(previous)) return;
            data.put(key, value);
            if (data.toString().length() > LIMIT - 4096) { if (previous == null) data.remove(key); else data.put(key, previous); return; }
        } catch (Exception ignored) { return; }
        schedule();
    }
    static synchronized void remove(Context context, String key) {
        if (key == null) return;
        if (load(context).remove(key) != null) schedule();
    }
    /** After the wallet key is erased or the wallet is removed: forget everything the page saved. */
    static synchronized void clear(Context context) { load(context); cache = new JSONObject(); schedule(); }
    private static void schedule() {
        dirty = true;
        if (scheduled) return;
        scheduled = true;
        writer.execute(() -> {
            try { Thread.sleep(250); } catch (InterruptedException ignored) { }
            String snapshot;
            synchronized (UiStore.class) { scheduled = false; if (!dirty) return; dirty = false; snapshot = cache.toString(); }
            try { file.save(new JSONObject(snapshot)); } catch (Exception ignored) { }
        });
    }
}
