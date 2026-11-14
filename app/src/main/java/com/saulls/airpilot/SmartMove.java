package com.saulls.airpilot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Timer;
import java.util.TimerTask;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.remotecontroller.HardwareState;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.sdk.remotecontroller.RemoteController;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Automated camera moves (Orbit and Dronie) flown with DJI virtual sticks.
 *
 * Safety:
 *  - Needs GPS (8+ satellites), enough height and battery before it starts.
 *  - Any stick movement, or the Pause / Return-to-Home button, stops it immediately (drone hovers).
 *  - Stops itself if the drone moves somewhere unexpected, telemetry goes stale,
 *    the battery gets low, or it would climb past the altitude ceiling.
 */
public final class SmartMove {

    public enum Type { ORBIT, DRONIE }

    public interface Listener {
        /** Progress 0..1 (or -1 for continuous). */
        void onMoveProgress(Type type, float progress, String text);

        void onMoveEnded(Type type, String reason, boolean completed);
    }

    /** Settings for a move. Distances in meters, speeds in m/s. */
    public static final class Params {
        public Type type = Type.ORBIT;
        public double radiusM = 15;      // orbit
        public boolean clockwise = true; // orbit, seen from above
        public boolean continuous = false;
        public double distanceM = 50;    // dronie
        public double speedMps = 3;
        public boolean recordVideo = true;
    }

    private static final long TICK_MS = 100;           // 10 Hz, DJI needs at least 5 Hz
    private static final int STICK_DEADBAND = 80;      // of ±660
    private static final double ALTITUDE_CEILING_M = 115; // stay under the 400 ft legal limit

    private static SmartMove instance;

    public static synchronized SmartMove get(Context c) {
        if (instance == null) instance = new SmartMove(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;

    private volatile boolean running;
    private Params params;
    private FlightController fc;
    private RemoteController rc;
    private Camera camera;
    private Timer timer;
    private boolean weStartedRecording;
    private long startedAt;

    // geometry (local east/north meters around the start point)
    private double originLat, originLng, startYaw, startAlt;
    private double centerE, centerN;       // orbit center
    private double lastAngle = Double.NaN;  // orbit
    private double swept;                   // orbit, degrees
    private boolean directionChecked;       // dronie

    private SmartMove(Context c) {
        context = c;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isRunning() {
        return running;
    }

    /** Returns null if it started, otherwise a reason the move can't start. */
    public String start(Params p) {
        if (running) return "A move is already running.";
        BaseProduct product = DJISDKManager.getInstance().getProduct();
        if (!(product instanceof Aircraft) || !product.isConnected()) return "Drone not connected.";
        Aircraft aircraft = (Aircraft) product;
        fc = aircraft.getFlightController();
        rc = aircraft.getRemoteController();
        camera = aircraft.getCamera();
        if (fc == null) return "Flight controller not available.";

        Telemetry.Snapshot s = Telemetry.get(context).last();
        if (!s.flying) return "Take off first.";
        if (Double.isNaN(s.lat) || s.satellites < 8 || s.gpsLevel < 3)
            return "Needs a strong GPS signal (P-GPS, 8+ satellites). Right now: " + s.satellites + " satellites.";
        if (s.compassError) return "Compass error. Land and calibrate the compass away from metal first.";
        if (s.goingHome) return "The drone is returning home.";
        if (s.lowBattery || (s.batteryPct >= 0 && s.batteryPct < 25)) return "Battery too low for a move.";
        double minAlt = p.type == Type.ORBIT ? 5 : 3;
        if (Double.isNaN(s.altitudeM) || s.altitudeM < minAlt)
            return "Fly higher first (at least " + Units.distance(minAlt, Prefs.isImperial(context)) + ").";
        if (Double.isNaN(s.yaw)) return "Heading not available yet.";
        if (p.type == Type.DRONIE && s.altitudeM + p.distanceM * 0.45 > ALTITUDE_CEILING_M)
            return "Not enough room to climb. Lower the drone or pick a shorter Dronie.";

        params = p;
        originLat = s.lat;
        originLng = s.lng;
        startYaw = s.yaw;
        startAlt = s.altitudeM;
        if (p.type == Type.ORBIT) {
            // Orbit the spot the camera is facing, radius meters ahead.
            double yawRad = Math.toRadians(startYaw);
            centerE = p.radiusM * Math.sin(yawRad);
            centerN = p.radiusM * Math.cos(yawRad);
        }
        lastAngle = Double.NaN;
        swept = 0;
        directionChecked = false;

        fc.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        fc.setYawControlMode(YawControlMode.ANGLE);
        fc.setVerticalControlMode(VerticalControlMode.VELOCITY);
        fc.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);

        running = true;
        startedAt = System.currentTimeMillis();
        if (rc != null) rc.setHardwareStateCallback(this::onRemote);

        fc.setVirtualStickModeEnabled(true, error -> {
            if (error != null) {
                finish("Couldn't take control: " + error.getDescription(), false);
                return;
            }
            if (p.recordVideo) startRecording();
            timer = new Timer("smart-move");
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    tick();
                }
            }, 0, TICK_MS);
        });
        progress(0, p.type == Type.ORBIT ? "Orbit starting…" : "Dronie starting…");
        return null;
    }

    /** Stop now and hover. */
    public void stop(String reason) {
        if (running) finish(reason, false);
    }

    // ------------------------------------------------------------ control loop

    private void tick() {
        if (!running) return;
        Telemetry.Snapshot s = Telemetry.get(context).last();
        if (System.currentTimeMillis() - s.time > 1500) {
            finish("Lost contact with the drone's sensors.", false);
            return;
        }
        if (!s.flying) {
            finish("Drone landed.", false);
            return;
        }
        if (s.goingHome) {
            finish("Return to home started.", false);
            return;
        }
        if (s.criticalBattery || s.lowBattery) {
            finish("Low battery.", false);
            return;
        }
        if (Double.isNaN(s.lat) || s.satellites < 6) {
            finish("Lost GPS.", false);
            return;
        }
        if (s.altitudeM > ALTITUDE_CEILING_M) {
            finish("Altitude ceiling reached.", false);
            return;
        }

        double e = east(s.lng, s.lat), n = north(s.lat);
        if (params.type == Type.ORBIT) orbitTick(s, e, n);
        else dronieTick(s, e, n);
    }

    private void orbitTick(Telemetry.Snapshot s, double e, double n) {
        double dE = centerE - e, dN = centerN - n;
        double dist = Math.hypot(dE, dN);
        double r = params.radiusM;
        if (dist < r * 0.5 || dist > r * 1.6 + 3) {
            finish("Drone left the circle unexpectedly; stopped for safety.", false);
            return;
        }
        // Face the center; slide sideways for the circle, correct radius with forward/back.
        double bearingToCenter = Math.toDegrees(Math.atan2(dE, dN));
        double forward = clamp(0.8 * (dist - r), -2, 2);
        double right = params.clockwise ? -params.speedMps : params.speedMps;
        send(forward, right, bearingToCenter, 0);

        // Track how far around we've gone.
        double angle = Math.toDegrees(Math.atan2(e - centerE, n - centerN));
        if (!Double.isNaN(lastAngle)) swept += Math.abs(wrap(angle - lastAngle));
        lastAngle = angle;
        if (!params.continuous && swept >= 360) {
            finish("Orbit complete.", true);
            return;
        }
        float prog = params.continuous ? -1f : (float) (swept / 360.0);
        progress(prog, "Orbit · " + (params.continuous ? Math.round(swept) + "°" : Math.round(prog * 100) + "%"));
    }

    private void dronieTick(Telemetry.Snapshot s, double e, double n) {
        double travelled = Math.hypot(e, n);
        // After the first couple of meters, make sure we're really flying backwards.
        if (!directionChecked && travelled > 3) {
            double moveBearing = Math.toDegrees(Math.atan2(e, n));
            double expected = startYaw + 180;
            if (Math.abs(wrap(moveBearing - expected)) > 60) {
                finish("Drone moved in an unexpected direction; stopped for safety.", false);
                return;
            }
            directionChecked = true;
        }
        if (System.currentTimeMillis() - startedAt > 4000 && travelled < 1) {
            finish("Drone isn't moving; stopped.", false);
            return;
        }
        if (travelled >= params.distanceM) {
            finish("Dronie complete.", true);
            return;
        }
        double climb = params.speedMps * 0.45; // about 25° up
        if (s.altitudeM + 2 > ALTITUDE_CEILING_M) climb = 0;
        send(-params.speedMps, 0, startYaw, climb);
        float prog = (float) (travelled / params.distanceM);
        progress(prog, "Dronie · " + Math.round(prog * 100) + "%");
    }

    /**
     * DJI quirk: in VELOCITY mode the "pitch" field is sideways (right +) and the "roll"
     * field is forward (+).
     */
    private void send(double forward, double right, double yawDeg, double up) {
        FlightController f = fc;
        if (f == null || !running) return;
        f.sendVirtualStickFlightControlData(
                new FlightControlData((float) right, (float) forward, (float) wrap(yawDeg), (float) up), null);
    }

    // ------------------------------------------------------------ stick override

    private void onRemote(HardwareState h) {
        if (!running) return;
        boolean sticks = moved(h.getLeftStick()) || moved(h.getRightStick());
        boolean pause = h.getPauseButton() != null && h.getPauseButton().isClicked();
        boolean home = h.getGoHomeButton() != null && h.getGoHomeButton().isClicked();
        if (sticks || pause || home) finish("Stopped: you took control.", false);
    }

    private static boolean moved(dji.common.Stick s) {
        return s != null && (Math.abs(s.getHorizontalPosition()) > STICK_DEADBAND
                || Math.abs(s.getVerticalPosition()) > STICK_DEADBAND);
    }

    // ------------------------------------------------------------ start / end

    private void startRecording() {
        if (camera == null) return;
        CommonCallbacks.CompletionCallback<DJIError> record = err -> {
            if (err != null) return;
            camera.startRecordVideo(e -> weStartedRecording = (e == null));
        };
        if (camera.isFlatCameraModeSupported()) {
            camera.setFlatMode(SettingsDefinitions.FlatCameraMode.VIDEO_NORMAL, record);
        } else {
            camera.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO, record);
        }
    }

    private synchronized void finish(String reason, boolean completed) {
        if (!running) return;
        running = false;
        Type type = params == null ? Type.ORBIT : params.type;
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        FlightController f = fc;
        if (f != null) {
            // Brake, then hand control back to the pilot.
            for (int i = 0; i < 3; i++) {
                f.sendVirtualStickFlightControlData(new FlightControlData(0, 0, (float) wrapSafe(), 0), null);
            }
            f.setVirtualStickModeEnabled(false, null);
        }
        if (rc != null) rc.setHardwareStateCallback(null);
        if (weStartedRecording && camera != null) {
            main.postDelayed(() -> camera.stopRecordVideo(null), completed ? 1500 : 300);
        }
        weStartedRecording = false;
        main.post(() -> {
            if (listener != null) listener.onMoveEnded(type, reason, completed);
        });
    }

    private double wrapSafe() {
        Telemetry.Snapshot s = Telemetry.get(context).last();
        return Double.isNaN(s.yaw) ? startYaw : wrap(s.yaw);
    }

    private void progress(float p, String text) {
        Type t = params.type;
        main.post(() -> {
            if (listener != null && running) listener.onMoveProgress(t, p, text);
        });
    }

    // ------------------------------------------------------------ math

    private double east(double lng, double lat) {
        return (lng - originLng) * Math.cos(Math.toRadians(originLat)) * 111320.0;
    }

    private double north(double lat) {
        return (lat - originLat) * 110574.0;
    }

    private static double wrap(double deg) {
        double d = deg % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
