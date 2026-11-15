package com.saulls.airpilot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** Remembers when missions ran, so their photos can be filed in a folder named after the mission. */
final class MissionLog {

    private static final String KEY = "mission_runs";
    private static final long SLACK = 3 * 60 * 1000L;

    private MissionLog() {
    }

    static synchronized void started(Context c, String name) {
        try {
            JSONArray a = runs(c);
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("start", System.currentTimeMillis());
            o.put("end", 0);
            a.put(o);
            JSONArray trimmed = new JSONArray();
            for (int i = Math.max(0, a.length() - 50); i < a.length(); i++) trimmed.put(a.get(i));
            Prefs.raw(c).edit().putString(KEY, trimmed.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static synchronized void ended(Context c) {
        try {
            JSONArray a = runs(c);
            if (a.length() == 0) return;
            a.getJSONObject(a.length() - 1).put("end", System.currentTimeMillis());
            Prefs.raw(c).edit().putString(KEY, a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Mission name if a photo taken at this time belongs to a mission, else null. */
    static synchronized String missionAt(Context c, long taken) {
        JSONArray a = runs(c);
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            long s = o.optLong("start"), e = o.optLong("end");
            if (e == 0) e = s + 30 * 60 * 1000L;
            if (taken >= s - SLACK && taken <= e + SLACK) return o.optString("name");
        }
        return null;
    }

    private static JSONArray runs(Context c) {
        try {
            return new JSONArray(Prefs.raw(c).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}
