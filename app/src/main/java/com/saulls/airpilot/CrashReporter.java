package com.saulls.airpilot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Saves the error report when the app crashes and offers to share it on the next launch. */
final class CrashReporter {

    private static final String FILE = "crash_reports.txt";
    private static final int MAX_CHARS = 60_000;

    private CrashReporter() {
    }

    static void install(Context c) {
        Context app = c.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                record(app, thread, error);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static void record(Context c, Thread thread, Throwable error) throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("=== Air Pilot crash " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("App " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + "), Android "
                + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("Thread: " + thread.getName());
        error.printStackTrace(pw);
        pw.println();
        pw.flush();
        File f = new File(c.getFilesDir(), FILE);
        String old = f.exists() ? read(f) : "";
        String all = sw + old;
        if (all.length() > MAX_CHARS) all = all.substring(0, MAX_CHARS);
        try (FileWriter w = new FileWriter(f, false)) {
            w.write(all);
        }
        Prefs.raw(c).edit().putBoolean("crash_unseen", true).commit();
    }

    private static String read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), MAX_CHARS)];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8") : "";
        } catch (Exception e) {
            return "";
        }
    }

    static String reports(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        return f.exists() ? read(f) : "";
    }

    static void clear(Context c) {
        new File(c.getFilesDir(), FILE).delete();
        Prefs.raw(c).edit().putBoolean("crash_unseen", false).apply();
    }

    /**
     * Android keeps a record of why the app's process died, including crashes inside DJI's native code,
     * freezes (ANRs) and crashes during startup that the Java handler above can't see. Add any new ones.
     */
    static void collectExitReasons(Context c) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            long seen = Prefs.raw(c).getLong("exit_seen", 0);
            long newest = seen;
            StringBuilder sb = new StringBuilder();
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            for (android.app.ApplicationExitInfo e : am.getHistoricalProcessExitReasons(null, 0, 30)) {
                if (e.getTimestamp() <= seen) continue;
                newest = Math.max(newest, e.getTimestamp());
                int r = e.getReason();
                boolean bad = r == android.app.ApplicationExitInfo.REASON_CRASH
                        || r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
                        || r == android.app.ApplicationExitInfo.REASON_ANR
                        || r == android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                        || r == android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                        || r == android.app.ApplicationExitInfo.REASON_LOW_MEMORY;
                if (!bad) continue;
                sb.append("=== Android exit record ").append(fmt.format(new Date(e.getTimestamp()))).append('\n');
                sb.append("App ").append(BuildConfig.VERSION_NAME).append(", Android ").append(Build.VERSION.RELEASE)
                        .append(", ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
                sb.append("Reason: ").append(reasonName(r)).append(" (status ").append(e.getStatus()).append(")\n");
                sb.append("Process: ").append(e.getProcessName()).append(", was ")
                        .append(e.getImportance() <= 100 ? "on screen" : "in the background").append('\n');
                if (e.getDescription() != null) sb.append("Details: ").append(e.getDescription()).append('\n');
                sb.append("Memory: ").append(e.getPss() / 1024).append(" MB pss, ").append(e.getRss() / 1024).append(" MB rss\n");
                if (r == android.app.ApplicationExitInfo.REASON_ANR) {
                    try (java.io.InputStream in = e.getTraceInputStream()) {
                        if (in != null) {
                            byte[] b = new byte[12_000];
                            int n = 0, k;
                            while (n < b.length && (k = in.read(b, n, b.length - n)) > 0) n += k;
                            sb.append("Trace (start):\n").append(new String(b, 0, n, "UTF-8")).append('\n');
                        }
                    } catch (Exception ignored) {
                    }
                }
                sb.append('\n');
            }
            Prefs.raw(c).edit().putLong("exit_seen", newest).apply();
            if (sb.length() == 0) return;
            File f = new File(c.getFilesDir(), FILE);
            String all = sb + (f.exists() ? read(f) : "");
            if (all.length() > MAX_CHARS) all = all.substring(0, MAX_CHARS);
            try (FileWriter w = new FileWriter(f, false)) {
                w.write(all);
            }
            Prefs.raw(c).edit().putBoolean("crash_unseen", true).apply();
        } catch (Throwable ignored) {
        }
    }

    private static String reasonName(int r) {
        switch (r) {
            case android.app.ApplicationExitInfo.REASON_CRASH: return "Java crash";
            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash (inside DJI/system code)";
            case android.app.ApplicationExitInfo.REASON_ANR: return "app froze (ANR)";
            case android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "failed while starting";
            case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "used too many resources";
            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: return "killed for low memory";
            default: return "reason " + r;
        }
    }

    /** On launch: if there's a new crash report, offer to share it. */
    static void offerIfNew(Activity a) {
        collectExitReasons(a);
        if (!Prefs.raw(a).getBoolean("crash_unseen", false)) return;
        Prefs.raw(a).edit().putBoolean("crash_unseen", false).apply();
        show(a);
    }

    static void show(Activity a) {
        collectExitReasons(a);
        String text = reports(a);
        if (text.isEmpty()) {
            new AlertDialog.Builder(a).setTitle("Crash reports").setMessage("No crashes recorded. 👍")
                    .setPositiveButton("OK", null).show();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Air Pilot crashed")
                .setMessage("Sorry about that. A crash report was saved. Share it so it can be fixed "
                        + "(it only has app and error details, nothing personal).")
                .setPositiveButton("Share report", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, "Air Pilot crash report");
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    a.startActivity(Intent.createChooser(send, "Share crash report"));
                })
                .setNeutralButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Air Pilot crash", text));
                })
                .setNegativeButton("Not now", null)
                .show();
    }
}
