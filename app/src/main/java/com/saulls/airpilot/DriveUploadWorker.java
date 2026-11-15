package com.saulls.airpilot;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Background job that works through the Drive upload queue, one file at a time. */
public class DriveUploadWorker extends Worker {

    private static final String CHANNEL = "drive_sync";
    private static final int NOTIFICATION_ID = 4711;
    private static final int CHUNK = 8 * 1024 * 1024; // multiple of 256 KB, as Drive requires
    private static final String API = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable";

    public DriveUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        GoogleSignInAccount acct = DriveSync.account(c);
        if (acct == null) {
            DriveSync.setStatus(c, "Not signed in to Google");
            return Result.failure();
        }
        String token;
        try {
            token = GoogleAuthUtil.getToken(c, acct.getAccount(), "oauth2:" + DriveSync.SCOPE);
        } catch (UserRecoverableAuthException e) {
            DriveSync.setStatus(c, "Google needs you to sign in again (Settings → Google Drive)");
            return Result.failure();
        } catch (Exception e) {
            DriveSync.setStatus(c, "Couldn't sign in: " + e.getMessage());
            return Result.retry();
        }

        int done = 0;
        JSONObject item;
        while ((item = DriveSync.peek(c)) != null) {
            if (isStopped()) return Result.retry();
            int left = DriveSync.pendingCount(c);
            String name = item.optString("name");
            foreground("Uploading " + name + " (" + left + " left)");
            try {
                String path = DriveSync.folder(c);
                String sub = item.optString("sub", "");
                if (!sub.isEmpty()) path = path + "/" + sub;
                String folderId = folderId(c, token, path);
                upload(c, token, item, folderId);
                DriveSync.pop(c);
                done++;
                DriveSync.setStatus(c, "Uploaded " + name);
            } catch (FileNotFoundException e) {
                DriveSync.pop(c); // file was deleted from the phone; skip it
            } catch (AuthException e) {
                try {
                    GoogleAuthUtil.clearToken(c, token);
                } catch (Exception ignored) {
                }
                DriveSync.setStatus(c, "Google sign-in expired; retrying");
                return Result.retry();
            } catch (FolderGoneException e) {
                Prefs.raw(c).edit().remove("drive_folder_ids").apply();
                return Result.retry();
            } catch (Exception e) {
                DriveSync.setStatus(c, "Upload paused: " + e.getMessage() + " (will retry)");
                return Result.retry();
            }
        }
        if (done > 0) DriveSync.setStatus(c, "All uploads finished (" + done + " just now)");
        return Result.success();
    }

    // ------------------------------------------------------------ Drive calls

    private static final class AuthException extends IOException {
        AuthException() {
            super("auth");
        }
    }

    private static final class FolderGoneException extends IOException {
        FolderGoneException() {
            super("folder gone");
        }
    }

    /** Finds (or creates) a folder path, e.g. "Drone/Air Pilot/2026-11-14". Remembers the ids. */
    private String folderId(Context c, String token, String path) throws IOException {
        JSONObject cache;
        try {
            cache = new JSONObject(Prefs.raw(c).getString("drive_folder_ids", "{}"));
        } catch (Exception e) {
            cache = new JSONObject();
        }
        String cached = cache.optString(path, null);
        if (cached != null && !cached.isEmpty()) return cached;

        String parent = "root";
        for (String part : path.split("/")) {
            if (part.trim().isEmpty()) continue;
            String q = "name = '" + part.replace("\\", "\\\\").replace("'", "\\'") + "' and mimeType = "
                    + "'application/vnd.google-apps.folder' and trashed = false and '" + parent + "' in parents";
            JSONObject res = json(request("GET", API + "?spaces=drive&fields=files(id)&q="
                    + URLEncoder.encode(q, "UTF-8"), token, null, null));
            JSONArray found = res.optJSONArray("files");
            if (found != null && found.length() > 0) {
                parent = found.optJSONObject(0).optString("id");
            } else {
                JSONObject meta = new JSONObject();
                try {
                    meta.put("name", part.trim());
                    meta.put("mimeType", "application/vnd.google-apps.folder");
                    meta.put("parents", new JSONArray().put(parent));
                } catch (Exception ignored) {
                }
                parent = json(request("POST", API + "?fields=id", token, "application/json; charset=UTF-8",
                        meta.toString().getBytes(StandardCharsets.UTF_8))).optString("id");
            }
        }
        try {
            cache.put(path, parent);
        } catch (Exception ignored) {
        }
        Prefs.raw(c).edit().putString("drive_folder_ids", cache.toString()).apply();
        return parent;
    }

    private void upload(Context c, String token, JSONObject item, String folderId) throws Exception {
        String kind = item.optString("kind");
        String src = item.optString("src");
        String name = item.optString("name");
        String mime = item.optString("mime", "application/octet-stream");

        long size;
        InputStream in;
        if ("file".equals(kind)) {
            File f = new File(src);
            if (!f.exists()) throw new FileNotFoundException(src);
            size = f.length();
            in = new FileInputStream(f);
        } else {
            Uri uri = Uri.parse(src);
            size = sizeOf(c, uri);
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) throw new FileNotFoundException(src);
        }

        try {
            // 1. Start a resumable session.
            JSONObject meta = new JSONObject();
            meta.put("name", name);
            meta.put("parents", new JSONArray().put(folderId));
            HttpURLConnection start = open("POST", UPLOAD, token);
            start.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            start.setRequestProperty("X-Upload-Content-Type", mime);
            if (size > 0) start.setRequestProperty("X-Upload-Content-Length", String.valueOf(size));
            byte[] body = meta.toString().getBytes(StandardCharsets.UTF_8);
            start.setDoOutput(true);
            start.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = start.getOutputStream()) {
                o.write(body);
            }
            int code = start.getResponseCode();
            if (code == 401) throw new AuthException();
            if (code == 404) throw new FolderGoneException();
            if (code / 100 != 2) throw new IOException("Drive said " + code + " " + errorText(start));
            String session = start.getHeaderField("Location");
            start.disconnect();
            if (session == null) throw new IOException("Drive didn't start the upload");

            // 2. Send the file in chunks.
            byte[] buf = new byte[CHUNK];
            long sent = 0;
            while (true) {
                int n = readFully(in, buf);
                boolean last = n < CHUNK || (size > 0 && sent + n >= size);
                String total = last ? String.valueOf(sent + n) : "*";
                HttpURLConnection put = open("PUT", session, token);
                put.setDoOutput(true);
                put.setFixedLengthStreamingMode(n);
                put.setRequestProperty("Content-Range", n == 0
                        ? "bytes */" + total
                        : "bytes " + sent + "-" + (sent + n - 1) + "/" + total);
                try (OutputStream o = put.getOutputStream()) {
                    o.write(buf, 0, n);
                }
                code = put.getResponseCode();
                put.disconnect();
                if (code == 401) throw new AuthException();
                sent += n;
                if (code == 200 || code == 201) return;
                if (code != 308) throw new IOException("Drive said " + code);
                if (last) throw new IOException("Drive didn't confirm the upload");
                if (size > 0) {
                    foreground("Uploading " + name + " · " + (int) (100 * sent / size) + "%");
                }
            }
        } finally {
            in.close();
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private static long sizeOf(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static HttpURLConnection open(String method, String url, String token) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setRequestProperty("Authorization", "Bearer " + token);
        return h;
    }

    private static String request(String method, String url, String token, String contentType, byte[] body)
            throws IOException {
        HttpURLConnection h = open(method, url, token);
        if (body != null) {
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", contentType);
            h.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = h.getOutputStream()) {
                o.write(body);
            }
        }
        int code = h.getResponseCode();
        if (code == 401) throw new AuthException();
        if (code / 100 != 2) throw new IOException("Drive said " + code + " " + errorText(h));
        try (InputStream in = h.getInputStream()) {
            return readAll(in);
        } finally {
            h.disconnect();
        }
    }

    private static String errorText(HttpURLConnection h) {
        try (InputStream e = h.getErrorStream()) {
            if (e == null) return "";
            String s = readAll(e);
            JSONObject o = new JSONObject(s).optJSONObject("error");
            return o != null ? o.optString("message") : s;
        } catch (Exception ex) {
            return "";
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toString("UTF-8");
    }

    private static JSONObject json(String s) throws IOException {
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            throw new IOException("Unexpected reply from Drive");
        }
    }

    // ------------------------------------------------------------ notification

    /** Runs as a foreground job (with a notification) so long video uploads aren't cut off. */
    private void foreground(String text) {
        Context c = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Google Drive uploads",
                        NotificationManager.IMPORTANCE_LOW));
            }
        }
        android.app.Notification n = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Air Pilot → Google Drive")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        try {
            setForegroundAsync(new ForegroundInfo(NOTIFICATION_ID, n));
        } catch (Exception ignored) {
        }
    }
}
