package com.saulls.airpilot;

import android.content.Context;
import android.content.SharedPreferences;

/** App settings stored on the phone. */
public final class Prefs {
    private static final String FILE = "airpilot";
    private static final String KEY_ADVANCED = "advanced_mode";
    private static final String KEY_IMPERIAL = "imperial_units";
    private static final String KEY_GRID = "grid_lines";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isAdvanced(Context c) {
        return sp(c).getBoolean(KEY_ADVANCED, false);
    }

    public static void setAdvanced(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ADVANCED, v).apply();
    }

    public static boolean isImperial(Context c) {
        return sp(c).getBoolean(KEY_IMPERIAL, true);
    }

    public static void setImperial(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_IMPERIAL, v).apply();
    }

    public static boolean isGrid(Context c) {
        return sp(c).getBoolean(KEY_GRID, false);
    }

    public static void setGrid(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_GRID, v).apply();
    }

    public static boolean isSatellite(Context c) {
        return sp(c).getBoolean("satellite_map", false);
    }

    public static void setSatellite(Context c, boolean v) {
        sp(c).edit().putBoolean("satellite_map", v).apply();
    }

    static SharedPreferences raw(Context c) {
        return sp(c);
    }
}
