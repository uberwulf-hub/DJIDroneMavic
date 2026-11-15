package com.saulls.airpilot;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;

/**
 * Uploads downloaded photos/videos (and optionally flight logs) to a folder in the pilot's
 * Google Drive, in the background. Uses the "drive.file" permission, so Air Pilot can only
 * see and change files and folders it created itself.
 */
public final class DriveSync {

    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final String WORK_NAME = "drive-sync";
    private static final String KEY_PENDING = "drive_pending";

    private DriveSync() {
    }

    // ------------------------------------------------------------ settings

    public static boolean isEnabled(Context c) {
        return Prefs.raw(c).getBoolean("drive_enabled", false) && account(c) != null;
    }

    public static boolean isSwitchOn(Context c) {
        return Prefs.raw(c).getBoolean("drive_enabled", false);
    }

    public static void setEnabled(Context c, boolean on) {
        Prefs.raw(c).edit().putBoolean("drive_enabled", on).apply();
        if (on) schedule(c);
    }

    public static String folder(Context c) {
        return Prefs.raw(c).getString("drive_folder", "Air Pilot");
    }

    public static void setFolder(Context c, String path) {
        String clean = path == null ? "" : path.trim().replaceAll("/+", "/").replaceAll("^/|/$", "");
        if (clean.isEmpty()) clean = "Air Pilot";
        Prefs.raw(c).edit().putString("drive_folder", clean).remove("drive_folder_ids").apply();
    }

    public static boolean wifiOnly(Context c) {
        return Prefs.raw(c).getBoolean("drive_wifi_only", true);
    }

    public static void setWifiOnly(Context c, boolean v) {
        Prefs.raw(c).edit().putBoolean("drive_wifi_only", v).apply();
        schedule(c);
    }

    public static boolean uploadLogs(Context c) {
        return Prefs.raw(c).getBoolean("drive_logs", true);
    }

    public static void setUploadLogs(Context c, boolean v) {
        Prefs.raw(c).edit().putBoolean("drive_logs", v).apply();
    }

    // ------------------------------------------------------------ account

    static GoogleSignInClient client(Context c) {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(SCOPE))
                .build();
        return GoogleSignIn.getClient(c, gso);
    }

    public static Intent signInIntent(Context c) {
        return client(c).getSignInIntent();
    }

    public static GoogleSignInAccount account(Context c) {
        GoogleSignInAccount a = GoogleSignIn.getLastSignedInAccount(c);
        if (a == null || a.getAccount() == null || !GoogleSignIn.hasPermissions(a, new Scope(SCOPE))) return null;
        return a;
    }

    public static void signOut(Context c) {
        client(c).signOut();
        Prefs.raw(c).edit().remove("drive_folder_ids").apply();
    }

    // ------------------------------------------------------------ queue

    /** Queue a gallery item (from Photos & Videos) for upload. */
    public static void enqueue(Context c, Uri uri, String name, String subFolder) {
        if (!isEnabled(c)) return;
        add(c, "uri", uri.toString(), name, GallerySaver.mimeType(name), subFolder);
    }

    /** Queue a file on the phone (flight log CSV) for upload. */
    public static void enqueueFile(Context c, File file, String name) {
        if (!isEnabled(c) || !uploadLogs(c)) return;
        add(c, "file", file.getAbsolutePath(), name, "text/csv", "Flight logs");
    }

    private static synchronized void add(Context c, String kind, String src, String name, String mime, String sub) {
        try {
            JSONArray arr = pending(c);
            JSONObject o = new JSONObject();
            o.put("kind", kind);
            o.put("src", src);
            o.put("name", name);
            o.put("mime", mime);
            if (sub != null && !sub.isEmpty()) o.put("sub", sub);
            arr.put(o);
            Prefs.raw(c).edit().putString(KEY_PENDING, arr.toString()).apply();
        } catch (JSONException ignored) {
        }
        schedule(c);
    }

    static synchronized JSONArray pending(Context c) {
        try {
            return new JSONArray(Prefs.raw(c).getString(KEY_PENDING, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static synchronized JSONObject peek(Context c) {
        JSONArray arr = pending(c);
        return arr.length() == 0 ? null : arr.optJSONObject(0);
    }

    /** Removes the first queued item (after a successful or permanently failed upload). */
    static synchronized void pop(Context c) {
        JSONArray arr = pending(c);
        JSONArray rest = new JSONArray();
        for (int i = 1; i < arr.length(); i++) rest.put(arr.opt(i));
        Prefs.raw(c).edit().putString(KEY_PENDING, rest.toString()).apply();
    }

    public static int pendingCount(Context c) {
        return pending(c).length();
    }

    public static void clearQueue(Context c) {
        Prefs.raw(c).edit().putString(KEY_PENDING, "[]").apply();
    }

    public static void schedule(Context c) {
        if (!isEnabled(c) || pendingCount(c) == 0) return;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(wifiOnly(c) ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(DriveUploadWorker.class)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(c).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req);
    }

    // ------------------------------------------------------------ status

    static void setStatus(Context c, String text) {
        Prefs.raw(c).edit().putString("drive_status", text
                + "  (" + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()) + ")").apply();
    }

    public static String status(Context c) {
        return Prefs.raw(c).getString("drive_status", "");
    }
}
