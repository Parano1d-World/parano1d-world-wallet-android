package org.parano1d.courier;

import android.app.WallpaperManager;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Tokens and NFTs owned by this wallet, read from the phone's own verified Native State
 * (Paramo1d's native_holdings / native_markets / native_listings, the same library iOS uses).
 * Names and artwork come from tradedata.parano1d.org and are accepted only when their
 * BLAKE3 content hashes match the hashes recorded on-chain, like iOS AssetMedia.
 */
final class AssetIndex {
    static final Set<String> OPS = new HashSet<>(Arrays.asList("assets", "asset_media", "asset_image", "asset_save", "asset_wallpaper", "asset_share", "usd_quote", "contact_holdings"));
    private static final String MEDIA = "https://tradedata.parano1d.org/";
    private static final int MAX_MANIFEST = 8192, MAX_IMAGE = 4 * 1024 * 1024;
    private static final okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build();

    private AssetIndex() {}

    /** Entry point from MainActivity's asset worker. `owner` and `protectedRoute` are read on the state worker. */
    static JSONObject handle(Context context, JSONObject request, String owner, boolean protectedRoute) throws Exception {
        File artifacts = new File(context.getFilesDir(), "artifacts"), chain = new File(context.getFilesDir(), "chain");
        switch (request.getString("op")) {
            case "assets": return assets(artifacts, chain, owner);
            case "contact_holdings": {
                // iOS ContactNFTGallery: an explicit look-up of one address in this phone's verified State. No explorer.
                String address = request.optString("address");
                if (!WalletKeys.validAddress(address)) throw new Exception("Choose a contact with a valid address.");
                JSONObject result = assets(artifacts, chain, address);
                if (!result.optBoolean("installed")) throw new Exception("Set up tokens and NFTs on this phone first (NFTs tab).");
                JSONObject holdings = result.getJSONObject("holdings");
                return new JSONObject().put("height", holdings.optLong("height")).put("items", holdings.optJSONArray("items") == null ? new JSONArray() : holdings.getJSONArray("items")).put("collections", holdings.optJSONArray("collections") == null ? new JSONArray() : holdings.getJSONArray("collections"));
            }
            case "asset_media": return media(context, request.getString("hash"), protectedRoute);
            case "asset_image": return new JSONObject().put("data_url", "data:image/png;base64," + Base64.encodeToString(fullImage(context, request.getString("hash"), protectedRoute), Base64.NO_WRAP));
            case "asset_save": return save(context, request.getString("hash"), request.optString("name", "NFT"), protectedRoute);
            case "asset_wallpaper": return wallpaper(context, request.getString("hash"), protectedRoute);
            case "asset_share": return share(context, request.getString("hash"), request.optString("name", "NFT"), protectedRoute);
            case "usd_quote": {
                // The source is kept internally only: the app never shows where the price came from.
                JSONObject quote = new JSONObject(usdQuote(context, protectedRoute, request.optBoolean("paranoid_mode")).toString());
                quote.remove("source");
                return quote;
            }
            default: throw new Exception("Unknown asset request");
        }
    }

    private static JSONObject call(JSONObject request) throws Exception {
        JSONObject result = new JSONObject(NativeCore.call(request.toString()));
        if (!result.optBoolean("ok")) throw new Exception(result.optString("error", "Native operation failed"));
        return result.getJSONObject("result");
    }

    /** Holdings, token prices and NFT floors, all at verified heights on this phone. */
    static JSONObject assets(File artifacts, File chain, String owner) throws Exception {
        if (owner == null || owner.isEmpty()) throw new Exception("Create or restore your wallet first.");
        JSONObject holdings;
        try {
            holdings = call(new JSONObject().put("op", "native_holdings").put("artifacts", artifacts.getAbsolutePath()).put("data_dir", chain.getAbsolutePath()).put("owner", owner));
        } catch (Exception error) {
            String message = String.valueOf(error.getMessage());
            if (message.contains("Native State") || message.contains("accepted") || message.contains("No such file") || message.contains("verified mobile chain")) {
                return new JSONObject().put("installed", false).put("reason", message);
            }
            throw error;
        }
        JSONObject result = new JSONObject().put("installed", true).put("holdings", holdings);
        // Prices and floors are optional extras: a failure here never hides owned assets.
        try { result.put("markets", call(new JSONObject().put("op", "native_markets").put("artifacts", artifacts.getAbsolutePath()).put("data_dir", chain.getAbsolutePath()))); } catch (Exception ignored) { }
        try { result.put("listings", call(new JSONObject().put("op", "native_listings").put("artifacts", artifacts.getAbsolutePath()).put("data_dir", chain.getAbsolutePath()))); } catch (Exception ignored) { }
        return result;
    }

    // ------------------------------------------------------------------ metadata and artwork

    static JSONObject media(Context context, String hash, boolean protectedRoute) throws Exception {
        JSONObject manifest = manifest(context, hash, protectedRoute);
        JSONObject out = new JSONObject().put("hash", hash).put("kind", manifest.getString("kind")).put("name", manifest.getString("name"))
            .put("symbol", manifest.optString("symbol")).put("description", manifest.optString("description"));
        JSONObject image = manifest.optJSONObject("image");
        if (image != null) {
            byte[] png = image(context, image, manifest.getString("kind"), protectedRoute);
            out.put("thumbnail", thumbnail(png, 512)).put("width", image.getInt("width")).put("height", image.getInt("height"));
        }
        return out;
    }

    private static JSONObject manifest(Context context, String hash, boolean protectedRoute) throws Exception {
        if (!hash.matches("[0-9a-f]{64}")) throw new Exception("Invalid metadata identity.");
        byte[] bytes = fetch(context, "metadata/" + hash + ".json", MAX_MANIFEST, protectedRoute);
        JSONObject manifest = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        byte[] canonical = canonical(manifest);
        if (!Blake3.domainHex("Parano1d/native-metadata/v1", canonical).equals(hash)) {
            cacheFile(context, "metadata/" + hash + ".json").delete();
            throw new Exception("Metadata hash mismatch.");
        }
        return manifest;
    }

    private static byte[] image(Context context, JSONObject spec, String kind, boolean protectedRoute) throws Exception {
        String imageHash = spec.getString("hash");
        if (!imageHash.matches("[0-9a-f]{64}")) throw new Exception("Invalid image identity.");
        String path = "images/" + imageHash + ".png";
        byte[] png = fetch(context, path, MAX_IMAGE, protectedRoute);
        boolean ok = png.length == spec.getInt("bytes") && png.length > 8 && (png[0] & 255) == 137 && png[1] == 80 && png[2] == 78 && png[3] == 71
            && Blake3.domainHex("Parano1d/native-image/v1", png).equals(imageHash);
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        if (ok) BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        int limit = "nft".equals(kind) ? 2048 : 512;
        ok = ok && bounds.outWidth == spec.getInt("width") && bounds.outHeight == spec.getInt("height") && bounds.outWidth <= limit && bounds.outHeight <= limit;
        if (!ok) { cacheFile(context, path).delete(); throw new Exception("Artwork failed its hash or size check."); }
        return png;
    }

    private static byte[] fullImage(Context context, String metadataHash, boolean protectedRoute) throws Exception {
        JSONObject manifest = manifest(context, metadataHash, protectedRoute);
        JSONObject spec = manifest.optJSONObject("image");
        if (spec == null) throw new Exception("This asset has no artwork.");
        return image(context, spec, manifest.getString("kind"), protectedRoute);
    }

    private static String thumbnail(byte[] png, int max) {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= max) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length, options);
        if (bitmap == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle();
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    /** A small JPEG (base64) of an already hash-checked PNG, for the watch (Data Layer messages stay small). */
    static String shrink(String pngBase64, int size) {
        byte[] png = Base64.decode(pngBase64, Base64.NO_WRAP);
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bitmap == null) return "";
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, size, size, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out);
        if (scaled != bitmap) scaled.recycle(); bitmap.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    // ------------------------------------------------------------------ Photos and wallpaper

    private static JSONObject save(Context context, String hash, String name, boolean protectedRoute) throws Exception {
        if (Build.VERSION.SDK_INT < 29) throw new Exception("Saving to Photos needs Android 10 or later.");
        byte[] png = fullImage(context, hash, protectedRoute);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name.replaceAll("[^A-Za-z0-9 #._-]", "").trim() + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Parano1d");
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("Photos is unavailable.");
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) { out.write(png); }
        return new JSONObject().put("saved", true);
    }

    private static JSONObject wallpaper(Context context, String hash, boolean protectedRoute) throws Exception {
        byte[] png = fullImage(context, hash, protectedRoute);
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bitmap == null) throw new Exception("Image decoding failed.");
        Bitmap wallpaper = wallpaperCanvas(context, bitmap);
        bitmap.recycle();
        // Visible crop hint = the whole canvas, so launchers don't crop or parallax-shift it.
        WallpaperManager.getInstance(context).setBitmap(wallpaper, new android.graphics.Rect(0, 0, wallpaper.getWidth(), wallpaper.getHeight()), true);
        wallpaper.recycle();
        return new JSONObject().put("wallpaper", true);
    }

    /**
     * Same composition as iOS NFTWallpaper.render: a portrait canvas at the screen's real resolution,
     * a heavily blurred and darkened wash of the art's own colours, and the whole picture, uncropped,
     * at most 88% of the width and 62% of the height, a little below centre so it clears the lock-screen clock.
     */
    /**
     * The art resampled to exactly the pixels it will cover (like iOS 170's Lanczos step), so it is never scaled again
     * when drawn. Enlarging 3x or more keeps hard pixel edges. Between 1.5x and 3x, a whole-number nearest-neighbour
     * step comes first and only the small remainder is smoothed, so edges and text stay sharp instead of going soft.
     * Shrinking is filtered.
     */
    static Bitmap sharpScaled(Bitmap art, int width, int height, float scale) {
        if (scale >= 3) return Bitmap.createScaledBitmap(art, width, height, false);
        if (scale >= 1.5f) {
            int whole = (int) Math.floor(scale);
            Bitmap step = Bitmap.createScaledBitmap(art, art.getWidth() * whole, art.getHeight() * whole, false);
            Bitmap out = Bitmap.createScaledBitmap(step, width, height, true);
            if (step != out && step != art) step.recycle();
            return out;
        }
        return Bitmap.createScaledBitmap(art, width, height, true);
    }

    static Bitmap wallpaperCanvas(Context context, Bitmap art) {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        android.hardware.display.DisplayManager displays = context.getSystemService(android.hardware.display.DisplayManager.class);
        displays.getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(metrics);
        int width = Math.min(metrics.widthPixels, metrics.heightPixels), height = Math.max(metrics.widthPixels, metrics.heightPixels);
        if (width < 320 || height < 480) { width = 1080; height = 2340; }
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawColor(android.graphics.Color.BLACK);
        // Wash: cover-fit the art into a tiny bitmap, shrink again, then stretch with filtering — no shapes remain.
        int smallW = 32, smallH = Math.max(1, Math.round(32f * height / width));
        Bitmap small = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas smallCanvas = new android.graphics.Canvas(small);
        float fill = Math.max((float) smallW / art.getWidth(), (float) smallH / art.getHeight());
        float fw = art.getWidth() * fill, fh = art.getHeight() * fill;
        android.graphics.Paint smooth = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);
        smallCanvas.drawBitmap(art, null, new android.graphics.RectF((smallW - fw) / 2, (smallH - fh) / 2, (smallW + fw) / 2, (smallH + fh) / 2), smooth);
        Bitmap tiny = Bitmap.createScaledBitmap(small, 6, Math.max(1, Math.round(6f * smallH / smallW)), true);
        small.recycle();
        canvas.drawBitmap(tiny, null, new android.graphics.RectF(0, 0, width, height), smooth);
        tiny.recycle();
        canvas.drawColor(android.graphics.Color.argb(115, 0, 0, 0)); // 45% black, as on iOS
        float scale = Math.min(width * 0.88f / art.getWidth(), height * 0.62f / art.getHeight());
        float w = art.getWidth() * scale, h = art.getHeight() * scale;
        android.graphics.RectF rect = new android.graphics.RectF((width - w) / 2, height * 0.57f - h / 2, (width + w) / 2, height * 0.57f + h / 2);
        // Pixel art stays crisp when enlarged a lot; photos and smooth art are filtered.
        Bitmap placed = sharpScaled(art, Math.max(1, Math.round(w)), Math.max(1, Math.round(h)), scale);
        // Shadow follows the art's own shape (transparent areas cast none), like the iOS shadow.
        android.graphics.Paint blur = new android.graphics.Paint();
        blur.setMaskFilter(new android.graphics.BlurMaskFilter(width * 0.04f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        int[] offset = new int[2];
        Bitmap mask = placed.extractAlpha(blur, offset);
        android.graphics.Paint shade = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        shade.setColor(android.graphics.Color.argb(153, 0, 0, 0));
        canvas.drawBitmap(mask, rect.left + offset[0], rect.top + offset[1], shade);
        mask.recycle();
        canvas.drawBitmap(placed, rect.left, rect.top, null);
        if (placed != art) placed.recycle();
        return out;
    }

    /** Shares the verified PNG through the system share sheet (temporary file, read-only grant). */
    private static JSONObject share(Context context, String hash, String name, boolean protectedRoute) throws Exception {
        byte[] png = fullImage(context, hash, protectedRoute);
        File dir = new File(context.getCacheDir(), "shared"); dir.mkdirs();
        File[] old = dir.listFiles(); if (old != null) for (File f : old) f.delete();
        File file = new File(dir, name.replaceAll("[^A-Za-z0-9 #._-]", "").trim().replace(' ', '-') + ".png");
        java.nio.file.Files.write(file.toPath(), png);
        Uri uri = androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".shared", file);
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/png")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        android.content.Intent chooser = android.content.Intent.createChooser(send, "Share NFT").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> context.startActivity(chooser));
        return new JSONObject().put("sharing", true);
    }

    // ------------------------------------------------------------------ NOID/USD price
    // One price for every screen (chip, balance USD, sends, watch), used while it is at most 30 minutes old.
    // Sources, best first (iOS 193): SafeTrade's NOID/USDT ticker (USDT = $1, at least $100 traded in 24 h),
    // the Parano1d World feed, NOID Scan, then the last saved price for 30 minutes; otherwise USD is hidden.
    // A failed source is skipped for 5 minutes. Parano1d mode uses only the Parano1d World feed and the saved price.
    // The source is never shown in the app.

    static final long MAX_QUOTE_AGE_MS = 30 * 60_000L;
    private static final String FEED = "https://parano1dworld.com/api/explorer/price";
    private static final String SAFETRADE = "https://safe.trade/api/v2/peatio/public/markets/noidusdt/tickers";
    private static final String SCAN = "https://parano1d-scan.com/api/stats";
    private static volatile JSONObject lastQuote;
    private static volatile long lastQuoteAttempt;
    private static final java.util.Map<String, Long> skipUntil = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean fresh(JSONObject quote, long now) { return PriceQuote.fresh(quote, now); }

    /** The current price for the watch, or null when none is fresh. */
    static String freshUsd() {
        JSONObject q = lastQuote;
        try { return fresh(q, System.currentTimeMillis()) ? q.getString("usd") : null; } catch (Exception e) { return null; }
    }

    private static JSONObject saved(Context context) {
        if (lastQuote != null) return lastQuote;
        try {
            String text = context.getSharedPreferences("price", Context.MODE_PRIVATE).getString("quote", null);
            if (text != null) lastQuote = new JSONObject(text);
        } catch (Exception ignored) { }
        return lastQuote;
    }

    static JSONObject usdQuote(Context context, boolean protectedRoute, boolean paranoid) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject cached = saved(context);
        if (fresh(cached, now) && now - lastQuoteAttempt < 60_000) return cached;
        if (protectedRoute) {
            if (fresh(cached, now)) return cached;
            throw new Exception("USD estimates are paused while App VPN is on.");
        }
        lastQuoteAttempt = now;
        String[] order = paranoid ? new String[] { "feed" } : new String[] { "safetrade", "feed", "scan" };
        for (String name : order) {
            Long until = skipUntil.get(name);
            if (until != null && until > now) continue;
            try {
                JSONObject quote = fetchQuote(name);
                if (fresh(quote, System.currentTimeMillis())) {
                    skipUntil.remove(name);
                    lastQuote = quote;
                    context.getSharedPreferences("price", Context.MODE_PRIVATE).edit().putString("quote", quote.toString()).apply();
                    return quote;
                }
            } catch (Exception sourceFailed) { /* next source */ }
            skipUntil.put(name, System.currentTimeMillis() + 5 * 60_000L);
        }
        if (fresh(cached, System.currentTimeMillis())) return cached; // the last saved price, for up to 30 minutes
        throw new Exception("A fresh USD quote is unavailable.");
    }

    private static JSONObject priceJson(String url, int timeoutSeconds) throws Exception {
        // Redirects are refused: a price must come from the source's own address.
        okhttp3.OkHttpClient timed = client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
        okhttp3.Request request = new okhttp3.Request.Builder().url(url).header("User-Agent", "Parano1d-Android").header("Cache-Control", "no-cache").build();
        try (okhttp3.Response response = timed.newCall(request).execute()) {
            if (response.code() != 200 || response.body() == null) throw new Exception("Price source unavailable.");
            return new JSONObject(new String(readLimited(response.body().byteStream(), 256 * 1024), StandardCharsets.UTF_8));
        }
    }

    private static String decimal(Object value) throws Exception { return PriceQuote.decimal(value); }

    private static JSONObject fetchQuote(String name) throws Exception {
        switch (name) {
            case "feed": {
                JSONObject body = priceJson(FEED, 5);
                return new JSONObject().put("usd", decimal(body.get("usd"))).put("updated_ms", java.time.Instant.parse(body.getString("updated")).toEpochMilli()).put("source", "Parano1d World");
            }
            case "safetrade": return PriceQuote.safetrade(priceJson(SAFETRADE, 5));
            default: {
                JSONObject quote = priceJson(SCAN, 10).getJSONObject("price");
                return new JSONObject().put("usd", decimal(quote.get("usd"))).put("updated_ms", java.time.Instant.parse(quote.getString("updated")).toEpochMilli()).put("source", "NOID Scan");
            }
        }
    }

    // ------------------------------------------------------------------ network and cache

    private static File cacheFile(Context context, String path) { return new File(new File(context.getCacheDir(), "asset-media"), path); }

    private static byte[] fetch(Context context, String path, int limit, boolean protectedRoute) throws Exception {
        File cached = cacheFile(context, path);
        if (cached.isFile() && cached.length() <= limit) return java.nio.file.Files.readAllBytes(cached.toPath());
        // Like iOS "waits instead of leaking": with App VPN on, don't reach the media server outside the tunnel.
        if (protectedRoute) throw new Exception("Names and artwork load with App VPN off. Your holdings are still verified.");
        okhttp3.Request request = new okhttp3.Request.Builder().url(MEDIA + path).header("User-Agent", "Parano1d-Android").build();
        try (okhttp3.Response response = client.newCall(request).execute()) {
            if (response.code() != 200 || response.body() == null) throw new Exception("Asset media unavailable.");
            long declared = response.body().contentLength();
            if (declared > limit) throw new Exception("Asset media exceeds its size limit.");
            byte[] bytes = readLimited(response.body().byteStream(), limit);
            cached.getParentFile().mkdirs();
            File temp = new File(cached.getPath() + ".tmp");
            java.nio.file.Files.write(temp.toPath(), bytes);
            if (!temp.renameTo(cached)) temp.delete();
            return bytes;
        }
    }

    private static byte[] readLimited(java.io.InputStream input, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) != -1) { out.write(buffer, 0, count); if (out.size() > limit) throw new Exception("Asset media exceeds its size limit."); }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ canonical manifest (matches parano1d-authorization media.rs)

    private static final Set<String> MANIFEST_KEYS = new HashSet<>(Arrays.asList("schema", "kind", "name", "symbol", "description", "website", "image"));
    private static final Set<String> IMAGE_KEYS = new HashSet<>(Arrays.asList("hash", "media_type", "width", "height", "bytes"));

    /** serde_json bytes of the Manifest struct plus a newline, in its declared field order. */
    static byte[] canonical(JSONObject m) throws Exception {
        for (Iterator<String> it = m.keys(); it.hasNext();) if (!MANIFEST_KEYS.contains(it.next())) throw new Exception("Invalid asset description");
        int schema = m.getInt("schema");
        String kind = m.getString("kind");
        if (schema != 1 || !(kind.equals("token") || kind.equals("collection") || kind.equals("nft"))) throw new Exception("Invalid asset description");
        String name = m.getString("name");
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 64 || !name.trim().equals(name)) throw new Exception("Invalid asset name");
        StringBuilder s = new StringBuilder("{\"schema\":1,\"kind\":").append(quote(kind)).append(",\"name\":").append(quote(name));
        if (m.has("symbol") && !m.isNull("symbol")) s.append(",\"symbol\":").append(quote(m.getString("symbol")));
        String description = m.optString("description", "");
        if (m.has("description") && !m.isNull("description") && !description.isEmpty()) s.append(",\"description\":").append(quote(description));
        if (m.has("website") && !m.isNull("website")) s.append(",\"website\":").append(quote(m.getString("website")));
        if (m.has("image") && !m.isNull("image")) {
            JSONObject image = m.getJSONObject("image");
            for (Iterator<String> it = image.keys(); it.hasNext();) if (!IMAGE_KEYS.contains(it.next())) throw new Exception("Invalid asset image");
            if (!"image/png".equals(image.getString("media_type"))) throw new Exception("Unsupported asset image");
            s.append(",\"image\":{\"hash\":").append(quote(image.getString("hash")))
             .append(",\"media_type\":").append(quote(image.getString("media_type")))
             .append(",\"width\":").append(unsigned(image, "width"))
             .append(",\"height\":").append(unsigned(image, "height"))
             .append(",\"bytes\":").append(unsigned(image, "bytes")).append('}');
        } else if (kind.equals("nft")) throw new Exception("NFT has no image");
        s.append("}\n");
        byte[] bytes = s.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MANIFEST) throw new Exception("Asset description too large");
        return bytes;
    }

    private static long unsigned(JSONObject o, String key) throws Exception {
        long value = o.getLong(key);
        if (value <= 0 || value > 0xffffffffL || o.getDouble(key) != (double) value) throw new Exception("Invalid asset image");
        return value;
    }

    /** JSON string escaping identical to serde_json's compact formatter. */
    static String quote(String value) {
        StringBuilder s = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': s.append("\\\""); break;
                case '\\': s.append("\\\\"); break;
                case '\b': s.append("\\b"); break;
                case '\f': s.append("\\f"); break;
                case '\n': s.append("\\n"); break;
                case '\r': s.append("\\r"); break;
                case '\t': s.append("\\t"); break;
                default:
                    if (c < 0x20) s.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else s.append(c);
            }
        }
        return s.append('"').toString();
    }
}
