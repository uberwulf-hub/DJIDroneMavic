package com.saulls.airpilot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.LocationCoordinate3D;
import dji.common.model.LocationCoordinate2D;
import dji.sdk.base.BaseProduct;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Single listener on the drone's flight-controller state. Feeds the on-screen
 * readout and the automatic flight recorder.
 */
public final class Telemetry {

    /** A snapshot of the numbers shown to the pilot. */
    public static final class Snapshot {
        public double altitudeM = Double.NaN;
        public double distanceM = Double.NaN;
        public double horizontalSpeedMps = Double.NaN;
        public double verticalSpeedMps = Double.NaN;
        public int flightTimeS;
        public int satellites;
        public boolean flying;
        public boolean goingHome;
        /** Aircraft position (NaN until GPS has a fix). */
        public double lat = Double.NaN, lng = Double.NaN;
        /** Home point (NaN until recorded). */
        public double homeLat = Double.NaN, homeLng = Double.NaN;
        /** Heading in degrees, 0 = north, clockwise. */
        public double yaw = Double.NaN;
        public double pitch = Double.NaN, roll = Double.NaN;
        public String flightMode = "";
        public int gpsLevel = -1;
        public double vpsHeightM = Double.NaN;
        public boolean compassError;
        public boolean maxHeightReached, maxRadiusReached;
        public boolean lowBattery, criticalBattery;
        public int batteryPct = -1;
        public double batteryVolts = Double.NaN;
        public double batteryTempC = Double.NaN;
        public int batteryCycles = -1, batteryFullMah = -1, batteryDesignMah = -1;
        public String batterySerial;
        /** SD card: -1 = unknown. */
        public int sdRemainingMB = -1, sdRecordSecondsLeft = -1;
        public boolean sdInserted = true, sdFull;
        /** Return-home estimate: direction relative to the drone's nose (deg), seconds, battery % needed. */
        public double homeRelBearing = Double.NaN;
        public int returnSeconds = -1, returnBatteryNeeded = -1;
        public boolean connected;
        /** When this snapshot was taken (phone clock). */
        public long time = System.currentTimeMillis();
    }

    public interface Listener {
        void onTelemetry(Snapshot s);
    }

    private static Telemetry instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final FlightRecorder recorder;
    private FlightController attachedTo;
    private dji.sdk.battery.Battery batteryAttached;
    private volatile int batteryPct = -1;
    private volatile double batteryVolts = Double.NaN, batteryTempC = Double.NaN;
    private volatile int batteryCycles = -1, batteryFull = -1, batteryDesign = -1;
    private volatile String batterySerial;
    private volatile int sdRemaining = -1, sdRecordSec = -1;
    private volatile boolean sdInserted = true, sdFull;
    private dji.sdk.camera.Camera cameraAttached;
    private final Context appContext;
    // battery drain tracking for the return estimate
    private long flightStartMs;
    private int flightStartPct = -1;
    private volatile Snapshot last = new Snapshot();

    private Telemetry(Context context) {
        appContext = context.getApplicationContext();
        recorder = new FlightRecorder(context.getApplicationContext());
    }

    public static synchronized Telemetry get(Context context) {
        if (instance == null) instance = new Telemetry(context);
        return instance;
    }

    public Snapshot last() {
        return last;
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
        l.onTelemetry(last);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Hooks onto the connected aircraft's flight controller. Safe to call repeatedly. */
    public synchronized void attach() {
        FlightController fc = flightController();
        if (fc != null && fc != attachedTo) {
            attachedTo = fc;
            fc.setStateCallback(this::onState);
        }
        BaseProduct product = DJISDKManager.getInstance().getProduct();
        dji.sdk.battery.Battery battery = product != null && product.isConnected() ? product.getBattery() : null;
        if (battery != null && battery != batteryAttached) {
            batteryAttached = battery;
            batterySerial = null;
            battery.setStateCallback(b -> {
                batteryPct = b.getChargeRemainingInPercent();
                batteryVolts = b.getVoltage() / 1000.0;
                batteryTempC = b.getTemperature();
                batteryCycles = b.getNumberOfDischarges();
                batteryFull = b.getFullChargeCapacity();
                batteryDesign = b.getDesignCapacity();
            });
            battery.getSerialNumber(new dji.common.util.CommonCallbacks.CompletionCallbackWith<String>() {
                @Override
                public void onSuccess(String serial) {
                    batterySerial = serial;
                    BatteryBook.seen(appContext, serial, batteryCycles, batteryFull, batteryDesign);
                }

                @Override
                public void onFailure(dji.common.error.DJIError error) {
                }
            });
        }
        dji.sdk.camera.Camera camera = product != null && product.isConnected() ? product.getCamera() : null;
        if (camera != null && camera != cameraAttached) {
            cameraAttached = camera;
            camera.setStorageStateCallBack(st -> {
                sdInserted = st.isInserted();
                sdFull = st.isFull();
                sdRemaining = st.getRemainingSpaceInMB();
                sdRecordSec = st.getAvailableRecordingTimeInSeconds();
            });
        }
    }

    public static FlightController flightController() {
        BaseProduct product = DJISDKManager.getInstance().getProduct();
        if (product instanceof Aircraft && product.isConnected()) {
            return ((Aircraft) product).getFlightController();
        }
        return null;
    }

    private void onState(FlightControllerState state) {
        Snapshot s = new Snapshot();
        LocationCoordinate3D loc = state.getAircraftLocation();
        if (loc != null) {
            s.altitudeM = loc.getAltitude();
            if (valid(loc.getLatitude(), loc.getLongitude())) {
                s.lat = loc.getLatitude();
                s.lng = loc.getLongitude();
            }
            LocationCoordinate2D home = state.getHomeLocation();
            if (state.isHomeLocationSet() && home != null && valid(home.getLatitude(), home.getLongitude())) {
                s.homeLat = home.getLatitude();
                s.homeLng = home.getLongitude();
            }
            if (state.isHomeLocationSet() && home != null
                    && valid(loc.getLatitude(), loc.getLongitude())
                    && valid(home.getLatitude(), home.getLongitude())) {
                s.distanceM = distanceMeters(home.getLatitude(), home.getLongitude(),
                        loc.getLatitude(), loc.getLongitude());
            }
        }
        float vx = state.getVelocityX();
        float vy = state.getVelocityY();
        s.horizontalSpeedMps = Math.sqrt(vx * vx + vy * vy);
        s.verticalSpeedMps = -state.getVelocityZ(); // DJI reports +Z as down
        s.flightTimeS = state.getFlightTimeInSeconds();
        s.satellites = state.getSatelliteCount();
        s.flying = state.isFlying();
        s.goingHome = state.isGoingHome();
        if (state.getAttitude() != null) {
            s.yaw = state.getAttitude().yaw;
            s.pitch = state.getAttitude().pitch;
            s.roll = state.getAttitude().roll;
        }
        s.flightMode = state.getFlightModeString() == null ? "" : state.getFlightModeString();
        s.gpsLevel = gpsLevel(state.getGPSSignalLevel());
        if (state.isUltrasonicBeingUsed()) s.vpsHeightM = state.getUltrasonicHeightInMeters();
        s.maxHeightReached = state.hasReachedMaxFlightHeight();
        s.maxRadiusReached = state.hasReachedMaxFlightRadius();
        s.lowBattery = state.isLowerThanBatteryWarningThreshold();
        s.criticalBattery = state.isLowerThanSeriousBatteryWarningThreshold();
        FlightController fc = attachedTo;
        if (fc != null && fc.getCompass() != null) s.compassError = fc.getCompass().hasError();
        s.batteryPct = batteryPct;
        s.batteryVolts = batteryVolts;
        s.batteryTempC = batteryTempC;
        s.batteryCycles = batteryCycles;
        s.batteryFullMah = batteryFull;
        s.batteryDesignMah = batteryDesign;
        s.batterySerial = batterySerial;
        s.sdInserted = sdInserted;
        s.sdFull = sdFull;
        s.sdRemainingMB = sdRemaining;
        s.sdRecordSecondsLeft = sdRecordSec;
        s.connected = true;
        estimateReturn(s);

        LocationCoordinate2D home = state.getHomeLocation();
        recorder.update(s, state.isHomeLocationSet() && home != null ? home.getLatitude() : Double.NaN,
                state.isHomeLocationSet() && home != null ? home.getLongitude() : Double.NaN);

        last = s;
        main.post(() -> {
            VoiceAlerts.get(appContext).onTelemetry(s);
            for (Listener l : listeners) l.onTelemetry(s);
        });
    }

    /** Direction home (relative to the nose), time to get back and battery it will take. */
    private void estimateReturn(Snapshot s) {
        if (s.flying) {
            if (flightStartMs == 0) {
                flightStartMs = System.currentTimeMillis();
                flightStartPct = s.batteryPct;
            }
        } else {
            flightStartMs = 0;
            flightStartPct = -1;
        }
        if (Double.isNaN(s.lat) || Double.isNaN(s.homeLat) || Double.isNaN(s.distanceM)) return;
        double dE = (s.homeLng - s.lng) * Math.cos(Math.toRadians(s.lat)) * 111320.0;
        double dN = (s.homeLat - s.lat) * 110574.0;
        double bearingHome = Math.toDegrees(Math.atan2(dE, dN));
        if (!Double.isNaN(s.yaw)) {
            double rel = (bearingHome - s.yaw) % 360;
            if (rel > 180) rel -= 360;
            if (rel < -180) rel += 360;
            s.homeRelBearing = rel;
        }
        double alt = Double.isNaN(s.altitudeM) ? 0 : Math.max(0, s.altitudeM);
        int seconds = (int) Math.round(s.distanceM / 8.0 + alt / 3.0 + 10); // ~8 m/s home, ~3 m/s down
        s.returnSeconds = seconds;
        // % per second: measured this flight, or a typical Mavic Air rate (~20 min per pack).
        double rate = 0.09;
        long elapsed = (System.currentTimeMillis() - flightStartMs) / 1000;
        if (flightStartMs > 0 && elapsed > 90 && flightStartPct > 0 && s.batteryPct >= 0
                && flightStartPct > s.batteryPct) {
            rate = Math.max(0.05, (flightStartPct - s.batteryPct) / (double) elapsed);
        }
        s.returnBatteryNeeded = (int) Math.ceil(seconds * rate * 1.3 + 3);
    }

    /** 0-5 like DJI's bars, or -1 when there's no GPS at all. */
    private static int gpsLevel(dji.common.flightcontroller.GPSSignalLevel level) {
        if (level == null) return -1;
        String n = level.name();
        if (n.startsWith("LEVEL_")) {
            try {
                return Integer.parseInt(n.substring(6));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static boolean valid(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng)
                && Math.abs(lat) <= 90 && Math.abs(lng) <= 180
                && !(lat == 0 && lng == 0);
    }

    static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
