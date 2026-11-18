package com.saulls.airpilot;

import android.content.Context;

import java.util.Calendar;
import java.util.TimeZone;

import dji.common.camera.ResolutionAndFrameRate;
import dji.common.camera.SettingsDefinitions;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Low-light camera preset for sunset/dusk: brighter exposure (+0.7 EV) and video at 30 fps or
 * less (so each frame gets more light). Can switch itself on when the sun gets low.
 */
public final class LowLight {

    public static final String OFF = "off", ON = "on", AUTO = "auto";

    private LowLight() {
    }

    public static String mode(Context c) {
        return Prefs.raw(c).getString("low_light", AUTO);
    }

    public static void setMode(Context c, String m) {
        Prefs.raw(c).edit().putString("low_light", m).apply();
    }

    public static boolean active(Context c) {
        return Prefs.raw(c).getBoolean("low_light_active", false);
    }

    /** Sun's elevation above the horizon in degrees (NOAA approximation). */
    public static double sunElevation(double lat, double lng, long millis) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(millis);
        double day = cal.get(Calendar.DAY_OF_YEAR);
        double hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0 + cal.get(Calendar.SECOND) / 3600.0;
        double g = 2 * Math.PI / 365.0 * (day - 1 + (hour - 12) / 24.0);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double trueSolarMin = hour * 60 + eqTime + 4 * lng;
        double ha = Math.toRadians(trueSolarMin / 4 - 180);
        double latR = Math.toRadians(lat);
        double cosZen = Math.sin(latR) * Math.sin(decl) + Math.cos(latR) * Math.cos(decl) * Math.cos(ha);
        return 90 - Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosZen))));
    }

    /** In Auto: decide from the sun (with some hysteresis). Returns the new desired state, or null for no change. */
    public static Boolean autoDecision(Context c, double lat, double lng) {
        if (!AUTO.equals(mode(c)) || Double.isNaN(lat)) return null;
        double el = sunElevation(lat, lng, System.currentTimeMillis());
        boolean on = active(c);
        if (!on && el < 6) return true;   // golden hour onwards
        if (on && el > 9) return false;
        return null;
    }

    /** Frames per second from DJI's enum name (e.g. FRAME_RATE_60_FPS -> 60), or 0 if unknown. */
    private static int fps(SettingsDefinitions.VideoFrameRate f) {
        if (f == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("FRAME_RATE_(\\d+)").matcher(f.name());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static Camera camera() {
        BaseProduct p = DJISDKManager.getInstance().getProduct();
        return p != null && p.isConnected() ? p.getCamera() : null;
    }

    /** Apply or undo the preset on the drone's camera. */
    public static void apply(Context c, boolean on, Runnable done) {
        Camera cam = camera();
        if (cam == null) {
            if (done != null) done.run();
            return;
        }
        Prefs.raw(c).edit().putBoolean("low_light_active", on).apply();
        cam.setExposureMode(SettingsDefinitions.ExposureMode.PROGRAM, e0 ->
                cam.setExposureCompensation(on ? SettingsDefinitions.ExposureCompensation.P_0_7
                        : SettingsDefinitions.ExposureCompensation.N_0_0, e1 -> setFrameRate(c, cam, on, done)));
    }

    private static void setFrameRate(Context c, Camera cam, boolean on, Runnable done) {
        cam.getVideoResolutionAndFrameRate(new dji.common.util.CommonCallbacks.CompletionCallbackWith<ResolutionAndFrameRate>() {
            @Override
            public void onSuccess(ResolutionAndFrameRate cur) {
                SettingsDefinitions.VideoFrameRate want = null;
                if (on) {
                    SettingsDefinitions.VideoFrameRate f = cur.getFrameRate();
                    if (fps(f) > 30) {
                        Prefs.raw(c).edit().putString("low_light_prev_fps", f.name()).apply();
                        want = SettingsDefinitions.VideoFrameRate.FRAME_RATE_30_FPS;
                    }
                } else {
                    String prev = Prefs.raw(c).getString("low_light_prev_fps", null);
                    if (prev != null) {
                        try {
                            want = SettingsDefinitions.VideoFrameRate.valueOf(prev);
                        } catch (Exception ignored) {
                        }
                        Prefs.raw(c).edit().remove("low_light_prev_fps").apply();
                    }
                }
                if (want == null) {
                    if (done != null) done.run();
                    return;
                }
                cam.setVideoResolutionAndFrameRate(new ResolutionAndFrameRate(cur.getResolution(), want), e -> {
                    if (done != null) done.run();
                });
            }

            @Override
            public void onFailure(dji.common.error.DJIError error) {
                if (done != null) done.run();
            }
        });
    }
}
