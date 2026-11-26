package org.parano1d.courier;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Like iOS CrashReports: if the app crashes, a stack trace is saved on this phone only.
 * Nothing is uploaded; the person reviews a report and chooses whether to email it.
 * Reports never contain wallet keys, messages or addresses (only the Java stack trace).
 */
final class CrashReports {
    private static final int MAX_REPORTS = 10, MAX_BYTES = 32 * 1024;
    private CrashReports() {}

    private static File dir(Context context) { File d = new File(context.getFilesDir(), "crash-reports"); d.mkdirs(); return d; }

    static void install(Context context) {
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof Recorder) return;
        Thread.setDefaultUncaughtExceptionHandler(new Recorder(app, previous));
    }

    private static final class Recorder implements Thread.UncaughtExceptionHandler {
        private final Context context; private final Thread.UncaughtExceptionHandler previous;
        Recorder(Context context, Thread.UncaughtExceptionHandler previous) { this.context = context; this.previous = previous; }
        @Override public void uncaughtException(Thread thread, Throwable error) {
            try {
                StringWriter trace = new StringWriter(); error.printStackTrace(new PrintWriter(trace));
                String text = "Parano1d Android " + version(context) + "\nAndroid " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · " + Build.MANUFACTURER + " " + Build.MODEL
                    + "\nThread: " + thread.getName() + "\nTime: " + java.time.Instant.now() + "\n\n" + redact(trace.toString());
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_BYTES) bytes = Arrays.copyOf(bytes, MAX_BYTES);
                java.nio.file.Files.write(new File(dir(context), "crash-" + System.currentTimeMillis() + ".txt").toPath(), bytes);
                prune(context);
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        }
    }

    /** Exception messages can quote input; drop anything that looks like hex keys or addresses. */
    static String redact(String text) {
        return text.replaceAll("[0-9a-fA-F]{32,}", "[hex]").replaceAll("\\bo1[0-9a-z]{20,}\\b", "[address]");
    }

    private static String version(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    private static File[] files(Context context) {
        File[] list = dir(context).listFiles((d, name) -> name.startsWith("crash-") && name.endsWith(".txt"));
        if (list == null) return new File[0];
        Arrays.sort(list, (a, b) -> b.getName().compareTo(a.getName()));
        return list;
    }

    private static void prune(Context context) { File[] list = files(context); for (int i = MAX_REPORTS; i < list.length; i++) list[i].delete(); }

    static JSONObject list(Context context) throws Exception {
        JSONArray out = new JSONArray();
        for (File f : files(context)) {
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            String headline = "";
            for (int i = 0; i < lines.length; i++) if (lines[i].isEmpty() && i + 1 < lines.length) { headline = lines[i + 1]; break; }
            out.put(new JSONObject().put("id", f.getName()).put("created_ms", f.lastModified()).put("headline", headline.length() > 160 ? headline.substring(0, 160) : headline).put("text", text));
        }
        return new JSONObject().put("reports", out);
    }

    static JSONObject delete(Context context, String id) throws Exception {
        if (id.equals("all")) { for (File f : files(context)) f.delete(); }
        else { if (!id.matches("crash-[0-9]+\\.txt")) throw new Exception("Unknown report"); new File(dir(context), id).delete(); }
        return list(context);
    }

    static Intent email(Context context, String id) throws Exception {
        if (!id.matches("crash-[0-9]+\\.txt")) throw new Exception("Unknown report");
        File f = new File(dir(context), id);
        String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        return new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, new String[]{"support@koajent.com"})
            .putExtra(Intent.EXTRA_SUBJECT, "Parano1d Android crash report").putExtra(Intent.EXTRA_TEXT, text);
    }
}
