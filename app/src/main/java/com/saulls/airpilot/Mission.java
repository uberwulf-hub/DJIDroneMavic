package com.saulls.airpilot;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.Inflater;

/** A planned waypoint mission (made in the Air Pilot Planner). Altitudes are meters above takeoff. */
public final class Mission {

    public static final class Wp {
        public double lat, lng, alt;
        public int heading, pitch;
        public boolean photo;
        /** Seconds to hover here (0 = none). */
        public double hoverS;
        /** "start" / "stop" video recording here, or null. */
        public String record;
        /** Speed to the next point in m/s (0 = mission speed). */
        public double speed;
    }

    public String id;
    public String name;
    public String kind;
    public String created;
    public double speed = 2.5;
    public boolean goHome = true;
    public double targetLat = Double.NaN, targetLng = Double.NaN;
    public double eave, ridge;
    public final List<double[]> house = new ArrayList<>();
    public final List<Wp> wps = new ArrayList<>();
    String rawJson;

    // ------------------------------------------------------------ parsing

    public static Mission parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!"airpilot-mission".equals(o.optString("app"))) throw new JSONException("Not an Air Pilot plan");
        Mission m = new Mission();
        m.rawJson = json;
        m.name = o.optString("name", "Mission");
        m.kind = o.optString("kind", "sweep");
        m.created = o.optString("created", "");
        m.speed = o.optDouble("speed", 2.5);
        m.goHome = !"hover".equals(o.optString("finish", "home"));
        JSONObject t = o.optJSONObject("target");
        if (t != null) {
            m.targetLat = t.optDouble("lat", Double.NaN);
            m.targetLng = t.optDouble("lng", Double.NaN);
        }
        JSONObject roof = o.optJSONObject("roof");
        if (roof != null) {
            m.eave = roof.optDouble("eave", 0);
            m.ridge = roof.optDouble("ridge", 0);
        }
        JSONArray house = o.optJSONArray("house");
        if (house != null) for (int i = 0; i < house.length(); i++) {
            JSONArray p = house.getJSONArray(i);
            m.house.add(new double[]{p.getDouble(0), p.getDouble(1)});
        }
        JSONArray wp = o.getJSONArray("wp");
        for (int i = 0; i < wp.length(); i++) {
            JSONArray a = wp.getJSONArray(i);
            Wp w = new Wp();
            w.lat = a.getDouble(0);
            w.lng = a.getDouble(1);
            w.alt = a.getDouble(2);
            w.heading = (int) Math.round(a.getDouble(3));
            w.pitch = (int) Math.round(a.getDouble(4));
            w.photo = a.optInt(5, 1) == 1;
            JSONObject x = a.optJSONObject(6);
            if (x != null) {
                w.hoverS = x.optDouble("stay", 0);
                w.record = x.has("rec") ? x.optString("rec") : null;
                w.speed = x.optDouble("speed", 0);
            }
            m.wps.add(w);
        }
        m.id = Integer.toHexString(json.hashCode());
        return m;
    }

    /** From the planner's link payload: base64url(deflate-raw(JSON)). */
    public static Mission fromLinkPayload(String payload) throws Exception {
        byte[] data = Base64.decode(payload, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        Inflater inf = new Inflater(true);
        inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
            out.write(buf, 0, n);
        }
        inf.end();
        return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    public static Mission fromStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ numbers

    public int photos() {
        int n = 0;
        for (Wp w : wps) if (w.photo) n++;
        return n;
    }

    public double distanceM() {
        double d = 0;
        for (int i = 1; i < wps.size(); i++) d += dist3(wps.get(i - 1), wps.get(i));
        return d;
    }

    public int estimatedSeconds() {
        double hover = 0;
        for (Wp w : wps) hover += w.hoverS;
        return (int) Math.round(distanceM() / Math.max(1, speed) + photos() * 3 + hover + wps.size() * 1.5 + 60);
    }

    public int estimatedBatteryPct() {
        return (int) Math.ceil(estimatedSeconds() * 0.09 * 1.2);
    }

    public double maxAlt() {
        double m = 0;
        for (Wp w : wps) m = Math.max(m, w.alt);
        return m;
    }

    static double dist2(double lat1, double lng1, double lat2, double lng2) {
        double dx = (lng2 - lng1) * Math.cos(Math.toRadians(lat1)) * 111320.0;
        double dy = (lat2 - lat1) * 110574.0;
        return Math.hypot(dx, dy);
    }

    static double dist3(Wp a, Wp b) {
        return Math.hypot(dist2(a.lat, a.lng, b.lat, b.lng), b.alt - a.alt);
    }

    /** Horizontal distance (m) from a point to the house outline (0 if inside). */
    double distanceToHouse(double lat, double lng) {
        if (house.size() < 3) return Double.MAX_VALUE;
        double[] o = house.get(0);
        double cos = Math.cos(Math.toRadians(o[0]));
        double px = (lng - o[1]) * cos * 111320.0, py = (lat - o[0]) * 110574.0;
        int n = house.size();
        double[] xs = new double[n], ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = (house.get(i)[1] - o[1]) * cos * 111320.0;
            ys[i] = (house.get(i)[0] - o[0]) * 110574.0;
        }
        boolean inside = false;
        double best = Double.MAX_VALUE;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            if ((ys[i] > py) != (ys[j] > py)
                    && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) inside = !inside;
            double vx = xs[j] - xs[i], vy = ys[j] - ys[i];
            double len2 = vx * vx + vy * vy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - xs[i]) * vx + (py - ys[i]) * vy) / len2));
            best = Math.min(best, Math.hypot(px - (xs[i] + t * vx), py - (ys[i] + t * vy)));
        }
        return inside ? 0 : best;
    }

    /** Problems that block flying (empty list = OK). */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (wps.size() < 2) out.add("The plan needs at least 2 points.");
        if (wps.size() > 99) out.add("The plan has " + wps.size() + " points; the drone allows 99.");
        for (int i = 0; i < wps.size(); i++) {
            Wp w = wps.get(i);
            if (w.alt < 2 || w.alt > 120) {
                out.add("Point " + (i + 1) + " is at " + Math.round(w.alt) + " m; it must be 2–120 m.");
                break;
            }
            if (house.size() >= 3 && w.alt < ridge + 3 && distanceToHouse(w.lat, w.lng) < 4.0) {
                out.add("Point " + (i + 1) + " is too close to the house.");
                break;
            }
        }
        for (int i = 1; i < wps.size(); i++) {
            if (dist2(wps.get(i - 1).lat, wps.get(i - 1).lng, wps.get(i).lat, wps.get(i).lng) > 500) {
                out.add("Points " + i + " and " + (i + 1) + " are more than 1600 ft apart; keep the path within sight.");
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------ storage

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "missions");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public void save(Context c) throws IOException {
        try (FileOutputStream f = new FileOutputStream(new File(dir(c), id + ".json"))) {
            f.write(rawJson.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static Mission load(Context c, String id) {
        try (FileInputStream in = new FileInputStream(new File(dir(c), id + ".json"))) {
            Mission m = fromStream(in);
            m.id = id;
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<Mission> all(Context c) {
        List<Mission> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            Mission m = load(c, f.getName().substring(0, f.getName().length() - 5));
            if (m != null) out.add(m);
        }
        Collections.sort(out, (a, b) -> b.created.compareTo(a.created));
        return out;
    }

    public static void delete(Context c, String id) {
        new File(dir(c), id + ".json").delete();
    }
}
