package com.saulls.airpilot;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** Current wind and weather at the pilot's location (Open-Meteo, free, no account). */
public final class Weather {

    public static final class Now {
        public double windMph, gustMph, tempF, precipMm;
        public int code;
        public long fetchedAt;

        public String summary(boolean imperial) {
            String wind = imperial
                    ? String.format(Locale.US, "Wind %.0f mph, gusts %.0f", windMph, gustMph)
                    : String.format(Locale.US, "Wind %.0f km/h, gusts %.0f", windMph * 1.609, gustMph * 1.609);
            String temp = imperial ? String.format(Locale.US, "%.0f°F", tempF)
                    : String.format(Locale.US, "%.0f°C", (tempF - 32) / 1.8);
            return wind + " · " + temp + (precipMm > 0.1 || code >= 51 ? " · rain" : "");
        }

        /** 0 fine, 1 caution, 2 too windy/wet for a Mavic Air. */
        public int level() {
            if (gustMph >= 24 || windMph >= 20 || precipMm > 0.5 || code >= 61) return 2;
            if (gustMph >= 16 || windMph >= 13 || precipMm > 0.1 || code >= 51) return 1;
            return 0;
        }
    }

    public interface Callback {
        void onWeather(Now now);
    }

    private static Now cached;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Weather() {
    }

    public static Now cached() {
        return cached;
    }

    /** Fetches (at most every 10 minutes) and calls back on the main thread; null if unavailable. */
    public static void fetch(Context c, Callback cb) {
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < 10 * 60 * 1000L) {
            cb.onWeather(cached);
            return;
        }
        Location loc = lastLocation(c);
        if (loc == null) {
            cb.onWeather(null);
            return;
        }
        double lat = loc.getLatitude(), lng = loc.getLongitude();
        new Thread(() -> {
            Now n = null;
            try {
                String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,precipitation,weather_code,wind_speed_10m,wind_gusts_10m"
                        + "&wind_speed_unit=mph&temperature_unit=fahrenheit", lat, lng);
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(8000);
                h.setReadTimeout(8000);
                try (InputStream in = h.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int r;
                    while ((r = in.read(b)) > 0) out.write(b, 0, r);
                    JSONObject cur = new JSONObject(out.toString("UTF-8")).getJSONObject("current");
                    n = new Now();
                    n.windMph = cur.optDouble("wind_speed_10m");
                    n.gustMph = cur.optDouble("wind_gusts_10m");
                    n.tempF = cur.optDouble("temperature_2m");
                    n.precipMm = cur.optDouble("precipitation", 0);
                    n.code = cur.optInt("weather_code");
                    n.fetchedAt = System.currentTimeMillis();
                    cached = n;
                }
            } catch (Exception ignored) {
            }
            Now result = n;
            MAIN.post(() -> cb.onWeather(result));
        }, "weather").start();
    }

    private static Location lastLocation(Context c) {
        if (ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {
        }
        if (best == null) {
            Telemetry.Snapshot s = Telemetry.get(c).last();
            if (!Double.isNaN(s.lat)) {
                best = new Location("drone");
                best.setLatitude(s.lat);
                best.setLongitude(s.lng);
            }
        }
        return best;
    }
}
