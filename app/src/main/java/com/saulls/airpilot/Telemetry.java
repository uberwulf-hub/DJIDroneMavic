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
    private volatile Snapshot last = new Snapshot();

    private Telemetry(Context context) {
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
            battery.setStateCallback(b -> {
                batteryPct = b.getChargeRemainingInPercent();
                batteryVolts = b.getVoltage() / 1000.0;
                batteryTempC = b.getTemperature();
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

        LocationCoordinate2D home = state.getHomeLocation();
        recorder.update(s, state.isHomeLocationSet() && home != null ? home.getLatitude() : Double.NaN,
                state.isHomeLocationSet() && home != null ? home.getLongitude() : Double.NaN);

        last = s;
        main.post(() -> {
            for (Listener l : listeners) l.onTelemetry(s);
        });
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
