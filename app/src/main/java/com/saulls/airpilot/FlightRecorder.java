package com.saulls.airpilot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Records a summary of every flight (takeoff to landing) on the phone. */
public final class FlightRecorder {

    private static final String KEY = "flight_log";
    private static final int MAX_FLIGHTS = 300;

    /** One saved flight. */
    public static final class Flight {
        public long startMillis;
        public long durationS;
        public double maxAltitudeM;
        public double maxDistanceM;
        public double maxSpeedMps;
        public double homeLat = Double.NaN;
        public double homeLng = Double.NaN;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("start", startMillis);
            o.put("dur", durationS);
            o.put("alt", maxAltitudeM);
            o.put("dist", maxDistanceM);
            o.put("spd", maxSpeedMps);
            if (!Double.isNaN(homeLat)) {
                o.put("lat", homeLat);
                o.put("lng", homeLng);
            }
            return o;
        }

        static Flight fromJson(JSONObject o) {
            Flight f = new Flight();
            f.startMillis = o.optLong("start");
            f.durationS = o.optLong("dur");
            f.maxAltitudeM = o.optDouble("alt", 0);
            f.maxDistanceM = o.optDouble("dist", 0);
            f.maxSpeedMps = o.optDouble("spd", 0);
            f.homeLat = o.optDouble("lat", Double.NaN);
            f.homeLng = o.optDouble("lng", Double.NaN);
            return f;
        }
    }

    private final Context context;
    private Flight current;

    FlightRecorder(Context context) {
        this.context = context;
    }

    synchronized void update(Telemetry.Snapshot s, double homeLat, double homeLng) {
        if (s.flying) {
            if (current == null) {
                current = new Flight();
                current.startMillis = System.currentTimeMillis();
            }
            if (!Double.isNaN(s.altitudeM)) current.maxAltitudeM = Math.max(current.maxAltitudeM, s.altitudeM);
            if (!Double.isNaN(s.distanceM)) current.maxDistanceM = Math.max(current.maxDistanceM, s.distanceM);
            if (!Double.isNaN(s.horizontalSpeedMps)) current.maxSpeedMps = Math.max(current.maxSpeedMps, s.horizontalSpeedMps);
            if (!Double.isNaN(homeLat)) {
                current.homeLat = homeLat;
                current.homeLng = homeLng;
            }
        } else if (current != null) {
            current.durationS = (System.currentTimeMillis() - current.startMillis) / 1000;
            if (current.durationS >= 5) save(current); // ignore motor blips
            current = null;
        }
    }

    private void save(Flight f) {
        List<Flight> all = load(context);
        all.add(0, f);
        while (all.size() > MAX_FLIGHTS) all.remove(all.size() - 1);
        write(context, all);
    }

    public static List<Flight> load(Context context) {
        List<Flight> out = new ArrayList<>();
        String raw = Prefs.raw(context).getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) out.add(Flight.fromJson(arr.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static void clear(Context context) {
        Prefs.raw(context).edit().remove(KEY).apply();
    }

    private static void write(Context context, List<Flight> flights) {
        JSONArray arr = new JSONArray();
        try {
            for (Flight f : flights) arr.put(f.toJson());
        } catch (JSONException ignored) {
        }
        Prefs.raw(context).edit().putString(KEY, arr.toString()).apply();
    }
}
