package com.saulls.airpilot;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Turns recorded flights into shareable files (CSV for spreadsheets, GPX for Google Earth). */
final class FlightExport {

    private FlightExport() {
    }

    static String baseName(FlightRecorder.Flight f) {
        return "AirPilot_flight_" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date(f.startMillis));
    }

    private static File exportDir(Activity a) {
        File d = new File(a.getCacheDir(), "exports");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File csv(Activity a, FlightRecorder.Flight f) throws IOException {
        File src = f.trackFile(a);
        if (src == null || !src.exists()) throw new IOException("No detailed track for this flight");
        File out = new File(exportDir(a), baseName(f) + ".csv");
        copy(src, out);
        return out;
    }

    static File gpx(Activity a, FlightRecorder.Flight f) throws IOException {
        File src = f.trackFile(a);
        if (src == null || !src.exists()) throw new IOException("No detailed track for this flight");
        File out = new File(exportDir(a), baseName(f) + ".gpx");
        try (BufferedReader r = new BufferedReader(new FileReader(src));
             PrintWriter w = new PrintWriter(new FileWriter(out))) {
            w.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            w.println("<gpx version=\"1.1\" creator=\"Air Pilot\" xmlns=\"http://www.topografix.com/GPX/1/1\">");
            w.println("<trk><name>" + baseName(f) + "</name><trkseg>");
            String line = r.readLine(); // header
            while ((line = r.readLine()) != null) {
                String[] c = line.split(",", -1);
                if (c.length < 5 || c[2].isEmpty() || c[3].isEmpty()) continue;
                w.print("<trkpt lat=\"" + c[2] + "\" lon=\"" + c[3] + "\">");
                if (!c[4].isEmpty()) w.print("<ele>" + c[4] + "</ele>");
                w.println("<time>" + c[1] + "</time></trkpt>");
            }
            w.println("</trkseg></trk></gpx>");
        }
        return out;
    }

    /** A zip with a summary of every flight plus each flight's CSV and GPX. */
    static File all(Activity a, List<FlightRecorder.Flight> flights) throws IOException {
        File out = new File(exportDir(a), "AirPilot_flight_log_"
                + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".zip");
        File summary = new File(exportDir(a), "summary.csv");
        try (PrintWriter w = new PrintWriter(new FileWriter(summary))) {
            w.println("start_local,duration_s,max_alt_m,max_dist_m,max_speed_mps,battery_start_pct,battery_end_pct,"
                    + "battery_used_pct,battery_name,battery_serial,battery_cycles,battery_health_pct,battery_start_v,"
                    + "battery_min_v,battery_max_temp_c,min_satellites,home_lat,home_lng,flight_modes,warnings,events");
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            for (FlightRecorder.Flight f : flights) {
                String bname = f.batterySerial == null ? "" : String.valueOf(BatteryBook.label(a, f.batterySerial));
                w.println(String.format(Locale.US, "%s,%d,%.1f,%.1f,%.2f,%d,%d,%s,\"%s\",%s,%s,%s,%s,%s,%s,%d,%s,%s,\"%s\",\"%s\",\"%s\"",
                        fmt.format(new Date(f.startMillis)), f.durationS, f.maxAltitudeM, f.maxDistanceM,
                        f.maxSpeedMps, f.batteryStart, f.batteryEnd,
                        f.batteryStart >= 0 && f.batteryEnd >= 0 ? String.valueOf(f.batteryStart - f.batteryEnd) : "",
                        bname.equals("null") ? "" : bname.replace('"', '\''),
                        f.batterySerial == null ? "" : f.batterySerial,
                        f.batteryCycles >= 0 ? String.valueOf(f.batteryCycles) : "",
                        f.batteryHealthPct >= 0 ? String.valueOf(f.batteryHealthPct) : "",
                        Double.isNaN(f.batteryStartVolts) ? "" : String.format(Locale.US, "%.2f", f.batteryStartVolts),
                        Double.isNaN(f.batteryMinVolts) ? "" : String.format(Locale.US, "%.2f", f.batteryMinVolts),
                        Double.isNaN(f.batteryMaxTempC) ? "" : String.format(Locale.US, "%.1f", f.batteryMaxTempC),
                        f.minSatellites,
                        Double.isNaN(f.homeLat) ? "" : String.valueOf(f.homeLat),
                        Double.isNaN(f.homeLng) ? "" : String.valueOf(f.homeLng),
                        android.text.TextUtils.join("; ", f.modes), android.text.TextUtils.join("; ", f.warnings),
                        android.text.TextUtils.join("; ", f.events).replace('"', '\'')));
            }
        }
        File batteries = new File(exportDir(a), "batteries.csv");
        try (PrintWriter w = new PrintWriter(new FileWriter(batteries))) {
            w.println("name,serial,health_pct,full_charge_mah,design_mah,cycles,flights,flight_time_s,hottest_c,"
                    + "last_flight_hottest_c,last_flight_used_pct,first_seen,last_used");
            SimpleDateFormat d = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            for (BatteryBook.Pack p : BatteryBook.list(a)) {
                w.println(String.format(Locale.US, "\"%s\",%s,%s,%s,%s,%s,%d,%d,%s,%s,%s,%s,%s",
                        p.name.replace('"', '\''), p.serial,
                        p.healthPct() >= 0 ? String.valueOf(p.healthPct()) : "",
                        p.fullMah > 0 ? String.valueOf(p.fullMah) : "", p.designMah > 0 ? String.valueOf(p.designMah) : "",
                        p.cycles >= 0 ? String.valueOf(p.cycles) : "", p.flights, p.flightSeconds,
                        Double.isNaN(p.maxTempC) ? "" : String.format(Locale.US, "%.1f", p.maxTempC),
                        Double.isNaN(p.lastFlightMaxTempC) ? "" : String.format(Locale.US, "%.1f", p.lastFlightMaxTempC),
                        p.lastFlightUsedPct >= 0 ? String.valueOf(p.lastFlightUsedPct) : "",
                        d.format(new Date(p.firstSeen)), d.format(new Date(p.lastSeen))));
            }
        }
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            addToZip(zip, summary, "summary.csv");
            addToZip(zip, batteries, "batteries.csv");
            for (FlightRecorder.Flight f : flights) {
                File t = f.trackFile(a);
                if (t == null || !t.exists()) continue;
                addToZip(zip, csv(a, f), baseName(f) + ".csv");
                addToZip(zip, gpx(a, f), baseName(f) + ".gpx");
                if (!f.events.isEmpty()) {
                    File ev = new File(exportDir(a), baseName(f) + "_events.txt");
                    try (PrintWriter pw = new PrintWriter(new FileWriter(ev))) {
                        for (String e : f.events) pw.println(e);
                    }
                    addToZip(zip, ev, baseName(f) + "_events.txt");
                }
            }
        }
        return out;
    }

    private static void addToZip(ZipOutputStream zip, File f, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
        }
        zip.closeEntry();
    }

    private static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** Opens Android's share sheet (Drive, Gmail, Files, …) for exported files. */
    static void share(Activity a, String title, String mime, File... files) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) uris.add(FileProvider.getUriForFile(a, a.getPackageName() + ".files", f));
        shareUris(a, title, mime, uris);
    }

    static void shareUris(Activity a, String title, String mime, ArrayList<Uri> uris) {
        if (uris.isEmpty()) return;
        Intent send;
        if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND);
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        send.setType(mime);
        ClipData clip = ClipData.newRawUri(title, uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(send, title));
    }
}
