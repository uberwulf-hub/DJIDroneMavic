package com.saulls.airpilot;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Remembers each battery (by serial number): cycles, health, flights and how hot it got. */
public final class BatteryBook {

    private static final String KEY = "battery_book";

    public static final class Pack {
        public String serial;
        public String name;
        public long firstSeen, lastSeen;
        public int cycles = -1, fullMah = -1, designMah = -1;
        public int flights;
        public long flightSeconds;
        public double maxTempC = Double.NaN;      // hottest ever
        public double lastFlightMaxTempC = Double.NaN;
        public int lastFlightUsedPct = -1;

        public int healthPct() {
            if (fullMah <= 0 || designMah <= 0) return -1;
            return (int) Math.min(100, Math.round(100.0 * fullMah / designMah));
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("serial", serial);
            o.put("name", name);
            o.put("first", firstSeen);
            o.put("last", lastSeen);
            o.put("cycles", cycles);
            o.put("full", fullMah);
            o.put("design", designMah);
            o.put("flights", flights);
            o.put("secs", flightSeconds);
            if (!Double.isNaN(maxTempC)) o.put("tmax", maxTempC);
            if (!Double.isNaN(lastFlightMaxTempC)) o.put("tlast", lastFlightMaxTempC);
            o.put("used", lastFlightUsedPct);
            return o;
        }

        static Pack fromJson(JSONObject o) {
            Pack p = new Pack();
            p.serial = o.optString("serial");
            p.name = o.optString("name");
            p.firstSeen = o.optLong("first");
            p.lastSeen = o.optLong("last");
            p.cycles = o.optInt("cycles", -1);
            p.fullMah = o.optInt("full", -1);
            p.designMah = o.optInt("design", -1);
            p.flights = o.optInt("flights");
            p.flightSeconds = o.optLong("secs");
            p.maxTempC = o.optDouble("tmax", Double.NaN);
            p.lastFlightMaxTempC = o.optDouble("tlast", Double.NaN);
            p.lastFlightUsedPct = o.optInt("used", -1);
            return p;
        }
    }

    private BatteryBook() {
    }

    private static synchronized JSONObject all(Context c) {
        try {
            return new JSONObject(Prefs.raw(c).getString(KEY, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static synchronized void save(Context c, Pack p) {
        try {
            JSONObject a = all(c);
            a.put(p.serial, p.toJson());
            Prefs.raw(c).edit().putString(KEY, a.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public static synchronized Pack get(Context c, String serial) {
        if (serial == null || serial.isEmpty()) return null;
        JSONObject o = all(c).optJSONObject(serial);
        return o == null ? null : Pack.fromJson(o);
    }

    public static synchronized List<Pack> list(Context c) {
        List<Pack> out = new ArrayList<>();
        JSONObject a = all(c);
        Iterator<String> it = a.keys();
        while (it.hasNext()) {
            JSONObject o = a.optJSONObject(it.next());
            if (o != null) out.add(Pack.fromJson(o));
        }
        java.util.Collections.sort(out, (x, y) -> Long.compare(y.lastSeen, x.lastSeen));
        return out;
    }

    /** Called when a battery is connected: creates it (as "Battery N") or refreshes its health. */
    public static synchronized Pack seen(Context c, String serial, int cycles, int full, int design) {
        if (serial == null || serial.isEmpty()) return null;
        Pack p = get(c, serial);
        if (p == null) {
            p = new Pack();
            p.serial = serial;
            p.name = "Battery " + (all(c).length() + 1);
            p.firstSeen = System.currentTimeMillis();
        }
        p.lastSeen = System.currentTimeMillis();
        if (cycles >= 0) p.cycles = cycles;
        if (full > 0) p.fullMah = full;
        if (design > 0) p.designMah = design;
        save(c, p);
        return p;
    }

    /** Called after each flight. */
    public static synchronized void flight(Context c, String serial, long seconds, double maxTemp, int usedPct,
                                           int cycles, int full, int design) {
        Pack p = seen(c, serial, cycles, full, design);
        if (p == null) return;
        p.flights++;
        p.flightSeconds += seconds;
        p.lastFlightMaxTempC = maxTemp;
        if (!Double.isNaN(maxTemp)) p.maxTempC = Double.isNaN(p.maxTempC) ? maxTemp : Math.max(p.maxTempC, maxTemp);
        p.lastFlightUsedPct = usedPct;
        save(c, p);
    }

    public static synchronized void rename(Context c, String serial, String name) {
        Pack p = get(c, serial);
        if (p == null || name == null || name.trim().isEmpty()) return;
        p.name = name.trim();
        save(c, p);
    }

    public static String label(Context c, String serial) {
        Pack p = get(c, serial);
        return p == null ? null : p.name;
    }
}
