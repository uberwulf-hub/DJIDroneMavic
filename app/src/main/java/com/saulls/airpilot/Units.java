package com.saulls.airpilot;

import java.util.Locale;

/** Formats distances and speeds in the units the pilot picked. */
public final class Units {
    public static final double FEET_PER_METER = 3.28084;
    private static final double MPH_PER_MPS = 2.23694;
    private static final double KMH_PER_MPS = 3.6;

    private Units() {
    }

    public static String distance(double meters, boolean imperial) {
        if (Double.isNaN(meters)) return "--";
        if (imperial) {
            double ft = meters * FEET_PER_METER;
            if (ft >= 5280) return String.format(Locale.US, "%.2f mi", ft / 5280.0);
            return String.format(Locale.US, "%.0f ft", ft);
        }
        if (meters >= 1000) return String.format(Locale.US, "%.2f km", meters / 1000.0);
        return String.format(Locale.US, "%.0f m", meters);
    }

    public static String speed(double mps, boolean imperial) {
        if (Double.isNaN(mps)) return "--";
        if (imperial) return String.format(Locale.US, "%.1f mph", mps * MPH_PER_MPS);
        return String.format(Locale.US, "%.1f km/h", mps * KMH_PER_MPS);
    }

    public static String duration(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    /** Converts a value typed by the pilot (ft or m) to whole meters. */
    public static int toMeters(double value, boolean imperial) {
        return (int) Math.round(imperial ? value / FEET_PER_METER : value);
    }

    /** Converts meters to the unit shown to the pilot (ft or m), rounded. */
    public static int fromMeters(double meters, boolean imperial) {
        return (int) Math.round(imperial ? meters * FEET_PER_METER : meters);
    }

    public static String lengthUnit(boolean imperial) {
        return imperial ? "ft" : "m";
    }
}
