package com.saulls.airpilot;

import android.os.Handler;
import android.os.Looper;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.mission.activetrack.ActiveTrackMission;
import dji.common.mission.activetrack.ActiveTrackMissionEvent;
import dji.common.mission.activetrack.ActiveTrackMode;
import dji.common.mission.activetrack.ActiveTrackState;
import dji.common.mission.activetrack.ActiveTrackTargetState;
import dji.common.mission.activetrack.ActiveTrackTrackingState;
import dji.common.mission.activetrack.QuickShotMode;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.mission.MissionControl;
import dji.sdk.mission.activetrack.ActiveTrackMissionOperatorListener;
import dji.sdk.mission.activetrack.ActiveTrackOperator;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * DJI ActiveTrack: the drone's own vision follows a person you box on screen.
 * Modes: follow behind (Trace), alongside (Profile), Spotlight (camera only), and the
 * built-in QuickShots aimed at the subject.
 */
public final class ActiveTracker implements ActiveTrackMissionOperatorListener {

    public enum Kind {
        FOLLOW("Follow", "Flies behind the person and follows them. Great for a bike ride or a run."),
        PROFILE("Side follow", "Flies alongside the person, filming them from the side."),
        SPOTLIGHT("Spotlight", "Keeps the camera locked on the person while YOU fly the drone."),
        CIRCLE("QuickShot: Circle", "Circles the person once while filming."),
        HELIX("QuickShot: Helix", "Spirals up and away around the person."),
        ROCKET("QuickShot: Rocket", "Rises straight up with the camera pointing down at the person."),
        DRONIE("QuickShot: Dronie", "Flies back and up with the camera locked on the person."),
        BOOMERANG("QuickShot: Boomerang", "Flies an oval around the person, out and back."),
        ASTEROID("QuickShot: Asteroid", "Flies back and up, then makes a 'tiny planet' video.");

        public final String title, help;

        Kind(String title, String help) {
            this.title = title;
            this.help = help;
        }

        boolean isQuickShot() {
            return ordinal() >= CIRCLE.ordinal();
        }
    }

    public interface Listener {
        /** Target box in normalized video coordinates (0..1), or null. */
        void onTrackUpdate(Kind kind, String status, android.graphics.RectF target, int confidence);

        void onTrackEnded(Kind kind, String reason);
    }

    private static ActiveTracker instance;

    public static synchronized ActiveTracker get() {
        if (instance == null) instance = new ActiveTracker();
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private ActiveTrackOperator op;
    private Kind kind;
    private volatile boolean running;
    private boolean confirmed;
    private boolean everTracked;
    private boolean recordVideo;
    private boolean weRecording;

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isRunning() {
        return running;
    }

    public Kind kind() {
        return kind;
    }

    /** Returns null if started, else why it can't. */
    public String start(android.content.Context context, Kind k, android.graphics.RectF normalizedBox, boolean record) {
        if (running) return "Tracking is already running.";
        BaseProduct product = DJISDKManager.getInstance().getProduct();
        if (product == null || !product.isConnected()) return "Drone not connected.";
        Telemetry.Snapshot s = Telemetry.get(context).last();
        if (!s.flying) return "Take off first, then point the camera at the person.";
        if (!Double.isNaN(s.altitudeM) && s.altitudeM < 2) return "Fly a little higher first (2 m / 7 ft or more).";
        MissionControl mc = MissionControl.getInstance();
        op = mc == null ? null : mc.getActiveTrackOperator();
        if (op == null) return "ActiveTrack isn't available on this drone through the SDK.";

        ActiveTrackMission mission = new ActiveTrackMission(normalizedBox, modeFor(k));
        if (k.isQuickShot()) mission.setQuickShotMode(quickShotFor(k));
        DJIError can = op.canStartTracking(mission);
        if (can != null) return "Can't start: " + can.getDescription();

        kind = k;
        running = true;
        confirmed = false;
        everTracked = false;
        recordVideo = record && !k.isQuickShot(); // QuickShots record by themselves
        op.removeListener(this);
        op.addListener(this);
        op.setRecommendedConfiguration(err -> op.startTracking(mission, error -> {
            if (error != null) end("Couldn't start: " + error.getDescription());
        }));
        update("Locking on…", null, 0);
        return null;
    }

    public void stop() {
        if (!running) return;
        if (op != null) {
            op.stopTracking(null);
            op.stopAircraftFollowing(null);
        }
        end("Stopped.");
    }

    @Override
    public void onUpdate(ActiveTrackMissionEvent e) {
        if (!running || e == null) return;
        ActiveTrackState cur = e.getCurrentState();
        ActiveTrackTrackingState ts = e.getTrackingState();
        android.graphics.RectF rect = ts != null ? ts.getTargetRect() : null;
        int conf = 0;
        if (ts != null && ts.getState() != null) {
            ActiveTrackTargetState t = ts.getState();
            conf = t == ActiveTrackTargetState.TRACKING_WITH_HIGH_CONFIDENCE ? 2
                    : t == ActiveTrackTargetState.TRACKING_WITH_LOW_CONFIDENCE ? 1
                    : t == ActiveTrackTargetState.CANNOT_CONFIRM ? -1 : 0;
        }
        if (cur == null) return;
        switch (cur) {
            case WAITING_FOR_CONFIRMATION:
                if (!confirmed) {
                    confirmed = true; // the pilot already chose the subject by drawing the box
                    op.acceptConfirmation(null);
                }
                update("Locking on…", rect, conf);
                break;
            case AIRCRAFT_FOLLOWING:
            case ONLY_CAMERA_FOLLOWING:
                everTracked = true;
                startRecordingOnce();
                update(kind.title + (conf == 1 ? " · subject unclear, stay in view" : " · tracking"), rect, conf);
                break;
            case PERFORMING_QUICK_SHOT:
                everTracked = true;
                int pct = ts != null ? ts.getCompletionPercentage() : 0;
                update(kind.title + " · " + pct + "%", rect, conf);
                break;
            case FINDING_TRACKED_TARGET:
            case RECOVERING:
                update("Lost sight of the subject, searching…", rect, conf);
                break;
            case DETECTING_HUMAN:
            case AUTO_SENSING:
            case AUTO_SENSING_FOR_QUICK_SHOT:
                update("Looking for the person…", rect, conf);
                break;
            case CANNOT_CONFIRM:
                update("Can't lock on. Try a box around the whole person, closer to the drone.", rect, -1);
                break;
            case CANNOT_START:
            case NOT_SUPPORT:
                String why = e.getError() != null ? e.getError().getDescription()
                        : (ts != null && ts.getReason() != null ? ts.getReason().name() : cur.name());
                end((cur == ActiveTrackState.NOT_SUPPORT ? "Not supported here: " : "Can't start: ") + readable(why));
                break;
            case IDLE:
            case WAITING_FOR_SELECT:
                if (everTracked) end(kind.isQuickShot() ? kind.title + " finished." : "Tracking ended.");
                break;
            case DISCONNECTED:
                end("Lost connection to the drone.");
                break;
            default:
                break;
        }
    }

    private static String readable(String s) {
        return s == null ? "" : s.replace('_', ' ').toLowerCase(java.util.Locale.US);
    }

    private void startRecordingOnce() {
        if (!recordVideo || weRecording) return;
        weRecording = true;
        BaseProduct p = DJISDKManager.getInstance().getProduct();
        Camera cam = p != null ? p.getCamera() : null;
        if (cam == null) return;
        cam.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO, err -> cam.startRecordVideo(null));
    }

    private void end(String reason) {
        if (!running) return;
        running = false;
        if (op != null) op.removeListener(this);
        if (weRecording) {
            BaseProduct p = DJISDKManager.getInstance().getProduct();
            Camera cam = p != null ? p.getCamera() : null;
            if (cam != null) cam.stopRecordVideo(null);
        }
        weRecording = false;
        Kind k = kind;
        main.post(() -> {
            if (listener != null) listener.onTrackEnded(k, reason);
        });
    }

    private void update(String status, android.graphics.RectF rect, int conf) {
        Kind k = kind;
        android.graphics.RectF r = rect == null ? null : new android.graphics.RectF(rect);
        main.post(() -> {
            if (listener != null && running) listener.onTrackUpdate(k, status, r, conf);
        });
    }

    private static ActiveTrackMode modeFor(Kind k) {
        switch (k) {
            case PROFILE:
                return ActiveTrackMode.PROFILE;
            case SPOTLIGHT:
                return ActiveTrackMode.SPOTLIGHT;
            case FOLLOW:
                return ActiveTrackMode.TRACE;
            default:
                return ActiveTrackMode.QUICK_SHOT;
        }
    }

    private static QuickShotMode quickShotFor(Kind k) {
        switch (k) {
            case HELIX:
                return QuickShotMode.HELIX;
            case ROCKET:
                return QuickShotMode.ROCKET;
            case DRONIE:
                return QuickShotMode.DRONIE;
            case BOOMERANG:
                return QuickShotMode.BOOMERANG;
            case ASTEROID:
                return QuickShotMode.ASTEROID;
            default:
                return QuickShotMode.CIRCLE;
        }
    }
}
