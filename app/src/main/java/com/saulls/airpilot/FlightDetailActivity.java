package com.saulls.airpilot;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** One flight in detail: numbers, warnings, charts, path on a map, and export. */
public class FlightDetailActivity extends Activity {

    public static final String EXTRA_START = "start";

    private FlightRecorder.Flight flight;
    private MapView map;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_flight_detail);
        long start = getIntent().getLongExtra(EXTRA_START, -1);
        for (FlightRecorder.Flight f : FlightRecorder.load(this)) {
            if (f.startMillis == start) flight = f;
        }
        if (flight == null) {
            finish();
            return;
        }
        boolean imperial = Prefs.isImperial(this);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.title)).setText(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(flight.startMillis)));

        StringBuilder sb = new StringBuilder();
        sb.append("Flight time: ").append(Units.duration(flight.durationS)).append('\n');
        sb.append("Highest: ").append(Units.distance(flight.maxAltitudeM, imperial)).append('\n');
        sb.append("Farthest from home: ").append(Units.distance(flight.maxDistanceM, imperial)).append('\n');
        sb.append("Top speed: ").append(Units.speed(flight.maxSpeedMps, imperial)).append('\n');
        if (flight.batteryStart >= 0) {
            sb.append("Battery: ").append(flight.batteryStart).append("% → ").append(flight.batteryEnd)
                    .append("%  (used ").append(flight.batteryStart - flight.batteryEnd).append("%)\n");
        }
        if (flight.minSatellites >= 0) sb.append("Fewest GPS satellites: ").append(flight.minSatellites).append('\n');
        if (!flight.modes.isEmpty()) sb.append("Flight modes: ").append(TextUtils.join(", ", flight.modes)).append('\n');
        ((TextView) findViewById(R.id.summary)).setText(sb.toString().trim());

        TextView warn = findViewById(R.id.warnings);
        if (flight.warnings.isEmpty()) {
            warn.setText(flight.track == null ? "" : "No warnings recorded ✓");
            warn.setTextColor(Color.parseColor("#9FE3B0"));
        } else {
            warn.setText("⚠ " + TextUtils.join("\n⚠ ", flight.warnings));
        }

        findViewById(R.id.btn_csv).setOnClickListener(v -> export(false));
        findViewById(R.id.btn_gpx).setOnClickListener(v -> export(true));
        findViewById(R.id.btn_delete).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete this flight?")
                .setPositiveButton("Delete", (d, w) -> {
                    FlightRecorder.delete(this, flight.startMillis);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show());
        boolean hasTrack = flight.trackFile(this) != null && flight.trackFile(this).exists();
        findViewById(R.id.btn_csv).setEnabled(hasTrack);
        findViewById(R.id.btn_gpx).setEnabled(hasTrack);
        findViewById(R.id.btn_csv).setAlpha(hasTrack ? 1f : 0.4f);
        findViewById(R.id.btn_gpx).setAlpha(hasTrack ? 1f : 0.4f);

        Configuration.getInstance().setUserAgentValue(getPackageName());
        map = findViewById(R.id.track_map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);

        if (hasTrack) loadTrack(imperial);
        else if (!Double.isNaN(flight.homeLat)) {
            map.getController().setZoom(17.0);
            map.getController().setCenter(new GeoPoint(flight.homeLat, flight.homeLng));
        }
    }

    private void loadTrack(boolean imperial) {
        List<GeoPoint> pts = new ArrayList<>();
        List<float[]> rows = new ArrayList<>(); // time, alt, speed, battery
        try (BufferedReader r = new BufferedReader(new FileReader(flight.trackFile(this)))) {
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                String[] c = line.split(",", -1);
                if (c.length < 15) continue;
                float t = parse(c[0]);
                rows.add(new float[]{t, parse(c[4]), parse(c[6]), parse(c[14])});
                if (!c[2].isEmpty() && !c[3].isEmpty()) {
                    pts.add(new GeoPoint(Double.parseDouble(c[2]), Double.parseDouble(c[3])));
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't read this flight's track", Toast.LENGTH_SHORT).show();
        }

        int n = rows.size();
        float[] t = new float[n], alt = new float[n], spd = new float[n], bat = new float[n];
        double altK = imperial ? Units.FEET_PER_METER : 1, spdK = imperial ? 2.23694 : 3.6;
        for (int i = 0; i < n; i++) {
            float[] r = rows.get(i);
            t[i] = r[0];
            alt[i] = Float.isNaN(r[1]) ? Float.NaN : (float) (r[1] * altK);
            spd[i] = Float.isNaN(r[2]) ? Float.NaN : (float) (r[2] * spdK);
            bat[i] = r[3];
        }
        ((TrackChart) findViewById(R.id.chart_alt)).setData("Altitude", imperial ? "ft" : "m",
                Color.parseColor("#4FC3F7"), t, alt);
        ((TrackChart) findViewById(R.id.chart_speed)).setData("Speed", imperial ? "mph" : "km/h",
                Color.parseColor("#FFD54F"), t, spd);
        ((TrackChart) findViewById(R.id.chart_batt)).setData("Battery", "%",
                Color.parseColor("#9FE3B0"), t, bat);

        if (!pts.isEmpty()) {
            Polyline line = new Polyline(map);
            line.setPoints(pts);
            line.getOutlinePaint().setColor(Color.parseColor("#FFD54F"));
            line.getOutlinePaint().setStrokeWidth(8f);
            map.getOverlays().add(line);
            map.addOnFirstLayoutListener((v, l, top, r, b) -> {
                if (pts.size() == 1) {
                    map.getController().setZoom(18.0);
                    map.getController().setCenter(pts.get(0));
                } else {
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.4f), false);
                }
            });
        }
    }

    private static float parse(String s) {
        if (s == null || s.isEmpty()) return Float.NaN;
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private void export(boolean gpx) {
        try {
            File f = gpx ? FlightExport.gpx(this, flight) : FlightExport.csv(this, flight);
            FlightExport.share(this, gpx ? "Share GPX" : "Share CSV",
                    gpx ? "application/gpx+xml" : "text/csv", f);
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
    }

    @Override
    protected void onPause() {
        if (map != null) map.onPause();
        super.onPause();
    }
}
