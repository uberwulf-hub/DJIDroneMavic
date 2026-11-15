package com.saulls.airpilot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Records every flight (takeoff to landing): a summary for the Flight Log list and a
 * detailed track file (two rows a second) that can be exported as CSV or GPX.
 */
public final class FlightRecorder {

    /** File names for exported/uploaded flights. */
    static final class FlightExportName {
        static String of(Flight f) {
            return "AirPilot_flight_" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date(f.startMillis));
        }
    }

    private static final String KEY = "flight_log";
    private static final int MAX_FLIGHTS = 300;
    private static final long SAMPLE_MS = 500;

    static final String CSV_HEADER = "time_s,utc,lat,lng,alt_m,dist_home_m,h_speed_mps,v_speed_mps,"
            + "heading_deg,pitch_deg,roll_deg,satellites,gps_level,flight_mode,battery_pct,battery_v,"
            + "battery_temp_c,vps_height_m,compass_error,going_home,low_battery,max_height_reached,max_radius_reached";

    /** One saved flight. */
    public static final class Flight {
        public long startMillis;
        public long durationS;
        public double maxAltitudeM;
        public double maxDistanceM;
        public double maxSpeedMps;
        public double homeLat = Double.NaN;
        public double homeLng = Double.NaN;
        public int batteryStart = -1;
        public int batteryEnd = -1;
        public int minSatellites = -1;
        public String track; // file name in files/flights, or null for old flights
        public String batterySerial;
        public double batteryMaxTempC = Double.NaN;
        public List<String> modes = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();

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
            o.put("b0", batteryStart);
            o.put("b1", batteryEnd);
            o.put("sats", minSatellites);
            if (track != null) o.put("track", track);
            if (batterySerial != null) o.put("bserial", batterySerial);
            if (!Double.isNaN(batteryMaxTempC)) o.put("btmax", batteryMaxTempC);
            o.put("modes", new JSONArray(modes));
            o.put("warn", new JSONArray(warnings));
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
            f.batteryStart = o.optInt("b0", -1);
            f.batteryEnd = o.optInt("b1", -1);
            f.minSatellites = o.optInt("sats", -1);
            f.track = o.has("track") ? o.optString("track") : null;
            f.batterySerial = o.has("bserial") ? o.optString("bserial") : null;
            f.batteryMaxTempC = o.optDouble("btmax", Double.NaN);
            JSONArray m = o.optJSONArray("modes");
            if (m != null) for (int i = 0; i < m.length(); i++) f.modes.add(m.optString(i));
            JSONArray w = o.optJSONArray("warn");
            if (w != null) for (int i = 0; i < w.length(); i++) f.warnings.add(w.optString(i));
            return f;
        }

        public File trackFile(Context c) {
            return track == null ? null : new File(flightsDir(c), track);
        }
    }

    private final Context context;
    private Flight current;
    private BufferedWriter writer;
    private long lastSample;
    private int lastCycles = -1, lastFull = -1, lastDesign = -1;
    private final Set<String> modes = new LinkedHashSet<>();
    private final Set<String> warnings = new LinkedHashSet<>();
    private static final SimpleDateFormat UTC = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);

    static {
        UTC.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    FlightRecorder(Context context) {
        this.context = context;
    }

    static File flightsDir(Context c) {
        File d = new File(c.getFilesDir(), "flights");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    synchronized void update(Telemetry.Snapshot s, double homeLat, double homeLng) {
        if (s.flying) {
            if (current == null) begin(s);
            Flight f = current;
            if (!Double.isNaN(s.altitudeM)) f.maxAltitudeM = Math.max(f.maxAltitudeM, s.altitudeM);
            if (!Double.isNaN(s.distanceM)) f.maxDistanceM = Math.max(f.maxDistanceM, s.distanceM);
            if (!Double.isNaN(s.horizontalSpeedMps)) f.maxSpeedMps = Math.max(f.maxSpeedMps, s.horizontalSpeedMps);
            if (!Double.isNaN(homeLat)) {
                f.homeLat = homeLat;
                f.homeLng = homeLng;
            }
            if (s.batterySerial != null) f.batterySerial = s.batterySerial;
            if (!Double.isNaN(s.batteryTempC)) {
                f.batteryMaxTempC = Double.isNaN(f.batteryMaxTempC) ? s.batteryTempC : Math.max(f.batteryMaxTempC, s.batteryTempC);
            }
            lastCycles = s.batteryCycles;
            lastFull = s.batteryFullMah;
            lastDesign = s.batteryDesignMah;
            if (s.batteryPct >= 0) {
                if (f.batteryStart < 0) f.batteryStart = s.batteryPct;
                f.batteryEnd = s.batteryPct;
            }
            if (s.satellites > 0 || f.minSatellites >= 0) {
                f.minSatellites = f.minSatellites < 0 ? s.satellites : Math.min(f.minSatellites, s.satellites);
            }
            if (s.flightMode != null && !s.flightMode.isEmpty()) modes.add(s.flightMode);
            if (s.compassError) warnings.add("Compass error / magnetic interference");
            if (s.gpsLevel >= 0 && s.gpsLevel <= 1) warnings.add("Weak GPS (drone may drift)");
            if (s.flightMode != null && s.flightMode.toUpperCase(Locale.US).contains("ATTI"))
                warnings.add("ATTI mode: no position hold, drone drifts with wind");
            if (s.maxHeightReached) warnings.add("Reached max altitude limit");
            if (s.maxRadiusReached) warnings.add("Reached max distance limit");
            if (s.lowBattery) warnings.add("Low battery warning");
            if (s.criticalBattery) warnings.add("Critically low battery");
            if (s.goingHome) warnings.add("Return to home was triggered");

            long now = System.currentTimeMillis();
            if (now - lastSample >= SAMPLE_MS) {
                lastSample = now;
                writeRow(s, now);
            }
        } else if (current != null) {
            end();
        }
    }

    private void begin(Telemetry.Snapshot s) {
        current = new Flight();
        current.startMillis = System.currentTimeMillis();
        current.track = current.startMillis + ".csv";
        modes.clear();
        warnings.clear();
        lastSample = 0;
        try {
            writer = new BufferedWriter(new FileWriter(new File(flightsDir(context), current.track)));
            writer.write(CSV_HEADER);
            writer.newLine();
        } catch (IOException e) {
            writer = null;
            current.track = null;
        }
    }

    private void writeRow(Telemetry.Snapshot s, long now) {
        if (writer == null) return;
        StringBuilder b = new StringBuilder(220);
        b.append(String.format(Locale.US, "%.1f", (now - current.startMillis) / 1000.0)).append(',');
        b.append(UTC.format(new Date(now))).append(',');
        num(b, s.lat, "%.7f");
        num(b, s.lng, "%.7f");
        num(b, s.altitudeM, "%.1f");
        num(b, s.distanceM, "%.1f");
        num(b, s.horizontalSpeedMps, "%.2f");
        num(b, s.verticalSpeedMps, "%.2f");
        num(b, s.yaw, "%.1f");
        num(b, s.pitch, "%.1f");
        num(b, s.roll, "%.1f");
        b.append(s.satellites).append(',');
        b.append(s.gpsLevel).append(',');
        b.append(s.flightMode == null ? "" : s.flightMode.replace(',', ' ')).append(',');
        b.append(s.batteryPct >= 0 ? String.valueOf(s.batteryPct) : "").append(',');
        num(b, s.batteryVolts, "%.2f");
        num(b, s.batteryTempC, "%.1f");
        num(b, s.vpsHeightM, "%.1f");
        b.append(s.compassError ? 1 : 0).append(',');
        b.append(s.goingHome ? 1 : 0).append(',');
        b.append(s.lowBattery ? 1 : 0).append(',');
        b.append(s.maxHeightReached ? 1 : 0).append(',');
        b.append(s.maxRadiusReached ? 1 : 0);
        try {
            writer.write(b.toString());
            writer.newLine();
        } catch (IOException ignored) {
        }
    }

    private static void num(StringBuilder b, double v, String fmt) {
        if (!Double.isNaN(v)) b.append(String.format(Locale.US, fmt, v));
        b.append(',');
    }

    private void end() {
        Flight f = current;
        current = null;
        f.durationS = (System.currentTimeMillis() - f.startMillis) / 1000;
        f.modes.addAll(modes);
        f.warnings.addAll(warnings);
        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {
        }
        writer = null;
        if (f.durationS >= 5) {
            if (f.batteryMaxTempC >= 60) f.warnings.add("Battery got hot (" + Math.round(f.batteryMaxTempC) + "°C). Let it cool before charging or reusing.");
            save(f);
            if (f.batterySerial != null) {
                BatteryBook.flight(context, f.batterySerial, f.durationS, f.batteryMaxTempC,
                        f.batteryStart >= 0 && f.batteryEnd >= 0 ? f.batteryStart - f.batteryEnd : -1,
                        lastCycles, lastFull, lastDesign);
            }
            File t = f.trackFile(context);
            if (t != null && t.exists()) DriveSync.enqueueFile(context, t, FlightExportName.of(f) + ".csv");
        } else if (f.track != null) {
            new File(flightsDir(context), f.track).delete(); // motor blip, not a flight
        }
    }

    private void save(Flight f) {
        List<Flight> all = load(context);
        all.add(0, f);
        while (all.size() > MAX_FLIGHTS) {
            Flight old = all.remove(all.size() - 1);
            File t = old.trackFile(context);
            if (t != null) t.delete();
        }
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

    public static void delete(Context context, long startMillis) {
        List<Flight> all = load(context);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).startMillis == startMillis) {
                File t = all.get(i).trackFile(context);
                if (t != null) t.delete();
                all.remove(i);
                break;
            }
        }
        write(context, all);
    }

    public static void clear(Context context) {
        for (Flight f : load(context)) {
            File t = f.trackFile(context);
            if (t != null) t.delete();
        }
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
