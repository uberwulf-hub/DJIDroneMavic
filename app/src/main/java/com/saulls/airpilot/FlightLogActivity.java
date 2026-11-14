package com.saulls.airpilot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Lists every recorded flight with totals at the top. Tap a flight for details and export. */
public class FlightLogActivity extends Activity {

    private ListView list;
    private TextView totals;
    private TextView empty;
    private boolean imperial;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_flight_log);
        list = findViewById(R.id.list);
        totals = findViewById(R.id.totals);
        empty = findViewById(R.id.empty);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_export).setOnClickListener(v -> exportAll());
        findViewById(R.id.btn_clear).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear flight log?")
                .setMessage("This deletes every saved flight and its detailed track from this phone.")
                .setPositiveButton("Clear", (d, w) -> {
                    FlightRecorder.clear(this);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void exportAll() {
        List<FlightRecorder.Flight> flights = FlightRecorder.load(this);
        if (flights.isEmpty()) return;
        try {
            FlightExport.share(this, "Share flight log", "application/zip", FlightExport.all(this, flights));
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Export failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void refresh() {
        imperial = Prefs.isImperial(this);
        List<FlightRecorder.Flight> flights = FlightRecorder.load(this);

        long totalS = 0;
        double farthest = 0, highest = 0;
        for (FlightRecorder.Flight f : flights) {
            totalS += f.durationS;
            farthest = Math.max(farthest, f.maxDistanceM);
            highest = Math.max(highest, f.maxAltitudeM);
        }
        totals.setText(String.format(Locale.US, "%d flights  ·  %s total  ·  farthest %s  ·  highest %s",
                flights.size(), Units.duration(totalS), Units.distance(farthest, imperial),
                Units.distance(highest, imperial)));

        empty.setVisibility(flights.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(flights.isEmpty() ? View.GONE : View.VISIBLE);

        DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        list.setAdapter(new ArrayAdapter<FlightRecorder.Flight>(this, 0, flights) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = convertView != null ? convertView
                        : LayoutInflater.from(getContext()).inflate(R.layout.item_flight, parent, false);
                FlightRecorder.Flight f = getItem(position);
                ((TextView) row.findViewById(R.id.when)).setText(fmt.format(new Date(f.startMillis)));
                ((TextView) row.findViewById(R.id.stats)).setText(String.format(Locale.US,
                        "%s  ·  up to %s high  ·  %s away  ·  top speed %s",
                        Units.duration(f.durationS), Units.distance(f.maxAltitudeM, imperial),
                        Units.distance(f.maxDistanceM, imperial), Units.speed(f.maxSpeedMps, imperial))
                        + (f.warnings.isEmpty() ? "" : "  \u00B7  \u26A0 " + f.warnings.size()
                        + (f.warnings.size() == 1 ? " warning" : " warnings")));
                return row;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> startActivity(
                new Intent(this, FlightDetailActivity.class)
                        .putExtra(FlightDetailActivity.EXTRA_START, flights.get(position).startMillis)));
    }
}
