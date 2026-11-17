package com.saulls.airpilot;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import androidx.core.content.ContextCompat;

/** The phone's own GPS (the pilot's position) for Follow me / Orbit me. */
public final class PhoneLocation implements LocationListener {

    private static PhoneLocation instance;

    public static synchronized PhoneLocation get(Context c) {
        if (instance == null) instance = new PhoneLocation(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private volatile Location last;
    private int users;

    private PhoneLocation(Context c) {
        context = c;
    }

    /** Start fast GPS updates (call stop() when done). Returns false without location permission. */
    public synchronized boolean start() {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return false;
        users++;
        if (users > 1) return true;
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        try {
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l != null) last = l;
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 300, 0f, this, Looper.getMainLooper());
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public synchronized void stop() {
        if (users == 0) return;
        users--;
        if (users > 0) return;
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm != null) lm.removeUpdates(this);
    }

    /** Latest fix if it's fresh (under maxAgeMs) and reasonably accurate, else null. */
    public Location fresh(long maxAgeMs, float maxAccuracyM) {
        Location l = last;
        if (l == null) return null;
        long age = System.currentTimeMillis() - l.getTime();
        if (age > maxAgeMs) return null;
        if (l.hasAccuracy() && l.getAccuracy() > maxAccuracyM) return null;
        return l;
    }

    public Location last() {
        return last;
    }

    @Override
    public void onLocationChanged(Location location) {
        last = location;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }
}
