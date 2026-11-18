package com.saulls.airpilot;

import android.content.Context;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.Scope;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pulls plans saved by the Air Pilot Planner into the phone's Missions list, from the
 * "Air Pilot/Missions" folder in Google Drive. Plans from Drive get ids "d_<driveFileId>",
 * so re-syncing updates them instead of duplicating.
 */
final class DriveMissions {

    private static final String API = "https://www.googleapis.com/drive/v3/files";
    /** Lets the phone see plan files the PC's Google Drive app put in the folder, not only ones Air Pilot made. */
    static final String READ_SCOPE = "https://www.googleapis.com/auth/drive.readonly";

    static boolean canReadAll(Context c) {
        GoogleSignInAccount a = DriveSync.account(c);
        return a != null && GoogleSignIn.hasPermissions(a, new Scope(READ_SCOPE));
    }

    private DriveMissions() {
    }

    static boolean signedIn(Context c) {
        return DriveSync.account(c) != null;
    }

    /** Run off the main thread. Returns a short summary like "2 new, 1 updated". */
    static String sync(Context c) throws Exception {
        GoogleSignInAccount acct = DriveSync.account(c);
        if (acct == null) throw new Exception("Sign in to Google Drive in Safety & Settings first.");
        String token = GoogleAuthUtil.getToken(c, acct.getAccount(),
                "oauth2:" + DriveSync.SCOPE + (canReadAll(c) ? " " + READ_SCOPE : ""));
        try {
            // Any "Missions" folder inside any "Air Pilot" folder in My Drive.
            List<String> folders = new ArrayList<>();
            for (String root : findFolders(token, "Air Pilot", "root")) folders.addAll(findFolders(token, "Missions", root));
            if (folders.isEmpty()) return "No plans in Drive yet (folder Air Pilot / Missions).";
            StringBuilder inParents = new StringBuilder("(");
            for (int i = 0; i < folders.size(); i++) inParents.append(i > 0 ? " or '" : "'").append(folders.get(i)).append("' in parents");
            inParents.append(")");
            JSONObject res = new JSONObject(get(token, API + "?fields=files(id,name,modifiedTime)&pageSize=200&q="
                    + URLEncoder.encode(inParents + " and trashed = false and name contains '.json'", "UTF-8")));
            JSONArray files = res.optJSONArray("files");
            int added = 0, updated = 0, removed = 0;
            Set<String> present = new HashSet<>();
            for (int i = 0; files != null && i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                String id = "d_" + f.getString("id");
                present.add(id);
                String modified = f.optString("modifiedTime");
                String key = "drive_mission_" + id;
                if (modified.equals(Prefs.raw(c).getString(key, null)) && Mission.load(c, id) != null) continue;
                Mission m;
                try {
                    m = Mission.parse(get(token, API + "/" + f.getString("id") + "?alt=media"));
                } catch (Exception notAPlan) {
                    continue;
                }
                boolean existed = Mission.load(c, id) != null;
                m.id = id;
                m.save(c);
                Prefs.raw(c).edit().putString(key, modified).apply();
                if (existed) updated++;
                else added++;
            }
            // Plans deleted from Drive disappear from the phone too.
            for (Mission m : Mission.all(c)) {
                if (m.id.startsWith("d_") && !present.contains(m.id)) {
                    Mission.delete(c, m.id);
                    Prefs.raw(c).edit().remove("drive_mission_" + m.id).apply();
                    removed++;
                }
            }
            if (added + updated + removed == 0) return "Drive plans are up to date.";
            StringBuilder sb = new StringBuilder("From Drive: ");
            if (added > 0) sb.append(added).append(" new ");
            if (updated > 0) sb.append(updated).append(" updated ");
            if (removed > 0) sb.append(removed).append(" removed");
            return sb.toString().trim();
        } catch (AuthError e) {
            GoogleAuthUtil.clearToken(c, token);
            throw new Exception("Google sign-in expired. Sign in again in Safety & Settings.");
        }
    }

    private static final class AuthError extends Exception {
    }

    private static List<String> findFolders(String token, String name, String parent) throws Exception {
        String q = "name = '" + name.replace("'", "\\'") + "' and mimeType = 'application/vnd.google-apps.folder'"
                + " and trashed = false and '" + parent + "' in parents";
        JSONArray f = new JSONObject(get(token, API + "?spaces=drive&fields=files(id)&q=" + URLEncoder.encode(q, "UTF-8")))
                .optJSONArray("files");
        List<String> ids = new ArrayList<>();
        for (int i = 0; f != null && i < f.length(); i++) ids.add(f.getJSONObject(i).getString("id"));
        return ids;
    }

    private static String get(String token, String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setRequestProperty("Authorization", "Bearer " + token);
        int code = h.getResponseCode();
        if (code == 401) throw new AuthError();
        if (code / 100 != 2) throw new Exception("Drive said " + code);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }
}
