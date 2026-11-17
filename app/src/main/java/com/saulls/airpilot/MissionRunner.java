package com.saulls.airpilot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.mission.waypoint.Waypoint;
import dji.common.mission.waypoint.WaypointAction;
import dji.common.mission.waypoint.WaypointActionType;
import dji.common.mission.waypoint.WaypointExecutionProgress;
import dji.common.mission.waypoint.WaypointMission;
import dji.common.mission.waypoint.WaypointMissionDownloadEvent;
import dji.common.mission.waypoint.WaypointMissionExecutionEvent;
import dji.common.mission.waypoint.WaypointMissionFinishedAction;
import dji.common.mission.waypoint.WaypointMissionFlightPathMode;
import dji.common.mission.waypoint.WaypointMissionGotoWaypointMode;
import dji.common.mission.waypoint.WaypointMissionHeadingMode;
import dji.common.mission.waypoint.WaypointMissionState;
import dji.common.mission.waypoint.WaypointMissionUploadEvent;
import dji.common.remotecontroller.HardwareState;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.mission.MissionControl;
import dji.sdk.mission.waypoint.WaypointMissionOperator;
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener;
import dji.sdk.products.Aircraft;
import dji.sdk.remotecontroller.RemoteController;
import dji.sdk.sdkmanager.DJISDKManager;

/** Uploads a planned mission to the drone and flies it, with stick-override to stop. */
public final class MissionRunner implements WaypointMissionOperatorListener {

    public interface Listener {
        void onMissionProgress(Mission m, String text);

        void onMissionEnded(Mission m, String reason, boolean completed);
    }

    private static MissionRunner instance;

    public static synchronized MissionRunner get(Context c) {
        if (instance == null) instance = new MissionRunner(c.getApplicationContext());
        return instance;
    }

    private static final int STICK_DEADBAND = 120;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private WaypointMissionOperator op;
    private RemoteController rc;
    private Mission mission;
    private volatile boolean running;
    private boolean started;
    private int lastIndex = -1;
    private int photosTaken;

    private MissionRunner(Context c) {
        context = c;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isRunning() {
        return running;
    }

    public Mission mission() {
        return mission;
    }

    /** Checks everything a mission needs right now. Returns null if OK. */
    public String preflight(Mission m) {
        List<String> p = m.problems();
        if (!p.isEmpty()) return p.get(0);
        BaseProduct product = DJISDKManager.getInstance().getProduct();
        if (!(product instanceof Aircraft) || !product.isConnected()) return "Drone not connected.";
        Telemetry.Snapshot s = Telemetry.get(context).last();
        if (!s.flying || Double.isNaN(s.altitudeM) || s.altitudeM < 2)
            return "Take off and hover first (at least 7 ft up), near where you took off.";
        if (Double.isNaN(s.lat) || s.satellites < 10 || s.gpsLevel < 4)
            return "Needs strong GPS (10+ satellites). Right now: " + s.satellites + ".";
        if (Double.isNaN(s.homeLat)) return "Home point isn't set yet.";
        if (s.flightMode != null && s.flightMode.toLowerCase(java.util.Locale.US).contains("sport"))
            return "Switch Sport mode off on the controller first (back to Normal).";
        if (s.compassError) return "Compass error. Land and calibrate away from metal first.";
        double fromDrone = Mission.dist2(s.lat, s.lng, m.wps.get(0).lat, m.wps.get(0).lng);
        if (fromDrone > 300) return "The mission starts " + Units.distance(fromDrone, Prefs.isImperial(context))
                + " away. Fly within 1000 ft of it first. (Is this the right house?)";
        for (Mission.Wp w : m.wps) {
            if (Mission.dist2(s.homeLat, s.homeLng, w.lat, w.lng) > 1000) return "Part of the mission is over 3000 ft from home.";
        }
        int need = m.estimatedBatteryPct() + 25;
        if (s.batteryPct >= 0 && s.batteryPct < need)
            return "Battery " + s.batteryPct + "% — this mission needs about " + m.estimatedBatteryPct()
                    + "% plus a 25% reserve. Use a fresher battery.";
        MissionControl mc = MissionControl.getInstance();
        if (mc == null || mc.getWaypointMissionOperator() == null) return "Waypoint missions aren't available on this drone.";
        return null;
    }

    public String start(Mission m) {
        if (running) return "A mission is already running.";
        String problem = preflight(m);
        if (problem != null) return problem;

        WaypointMission.Builder b = new WaypointMission.Builder()
                .autoFlightSpeed((float) Math.max(1, Math.min(6, m.speed)))
                .maxFlightSpeed(10f)
                .headingMode(WaypointMissionHeadingMode.USING_WAYPOINT_HEADING)
                .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
                .gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
                .finishedAction(m.goHome ? WaypointMissionFinishedAction.GO_HOME : WaypointMissionFinishedAction.NO_ACTION)
                .setExitMissionOnRCSignalLostEnabled(true)
                .setGimbalPitchRotationEnabled(true);
        for (Mission.Wp w : m.wps) {
            Waypoint wp = new Waypoint(w.lat, w.lng, (float) w.alt);
            wp.heading = Math.max(-180, Math.min(180, w.heading));
            wp.gimbalPitch = Math.max(-90, Math.min(0, w.pitch));
            wp.addAction(new WaypointAction(WaypointActionType.ROTATE_AIRCRAFT, wp.heading));
            wp.addAction(new WaypointAction(WaypointActionType.GIMBAL_PITCH, (int) wp.gimbalPitch));
            if (w.photo) {
                wp.addAction(new WaypointAction(WaypointActionType.STAY, 800)); // settle for a sharp photo
                wp.addAction(new WaypointAction(WaypointActionType.START_TAKE_PHOTO, 0));
            }
            b.addWaypoint(wp);
        }
        b.waypointCount(m.wps.size());
        WaypointMission wm = b.build();
        DJIError check = wm.checkParameters();
        if (check != null) return "The drone rejected the plan: " + check.getDescription();

        op = MissionControl.getInstance().getWaypointMissionOperator();
        op.removeListener(this);
        op.addListener(this);
        DJIError load = op.loadMission(wm);
        if (load != null) return "Couldn't load the mission: " + load.getDescription();

        mission = m;
        running = true;
        started = false;
        lastIndex = -1;
        photosTaken = 0;
        BaseProduct product = DJISDKManager.getInstance().getProduct();
        rc = product instanceof Aircraft ? ((Aircraft) product).getRemoteController() : null;
        if (rc != null) rc.setHardwareStateCallback(this::onRemote);
        MissionLog.started(context, m.name);

        // Photo mode first, then upload.
        Camera cam = product.getCamera();
        Runnable upload = () -> op.uploadMission(err -> {
            if (err != null) end("Upload failed: " + err.getDescription(), false);
        });
        if (cam != null) {
            cam.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO,
                    e1 -> cam.setShootPhotoMode(SettingsDefinitions.ShootPhotoMode.SINGLE, e2 -> upload.run()));
        } else {
            upload.run();
        }
        progress("Sending the plan to the drone…");
        return null;
    }

    public void stop(String reason) {
        if (!running) return;
        if (op != null) op.stopMission(null);
        end(reason, false);
    }

    // ------------------------------------------------------------ operator events

    @Override
    public void onUploadUpdate(WaypointMissionUploadEvent e) {
        if (!running || started) return;
        if (e.getProgress() != null && e.getProgress().totalWaypointCount > 0) {
            progress("Sending plan… " + (e.getProgress().uploadedWaypointIndex + 1) + "/" + e.getProgress().totalWaypointCount);
        }
        if (e.getCurrentState() == WaypointMissionState.READY_TO_EXECUTE) {
            started = true;
            op.startMission(err -> {
                if (err != null) end("Couldn't start: " + err.getDescription(), false);
            });
        }
    }

    @Override
    public void onExecutionUpdate(WaypointMissionExecutionEvent e) {
        if (!running || e.getProgress() == null) return;
        WaypointExecutionProgress p = e.getProgress();
        int total = p.totalWaypointCount > 0 ? p.totalWaypointCount : mission.wps.size();
        if (p.isWaypointReached && p.targetWaypointIndex != lastIndex) {
            lastIndex = p.targetWaypointIndex;
            if (lastIndex >= 0 && lastIndex < mission.wps.size() && mission.wps.get(lastIndex).photo) photosTaken++;
        }
        progress(mission.name + " · point " + Math.min(total, p.targetWaypointIndex + 1) + "/" + total
                + " · " + photosTaken + " photos");
    }

    @Override
    public void onExecutionStart() {
        progress(mission.name + " · flying to the first point…");
        VoiceAlerts va = VoiceAlerts.get(context);
        va.say("mission", va.line("Mission started.", "Mission started. I've got the wheel, thumbs ready just in case.",
                "Starting the roof scan. Sit back and relax."), 2000);
    }

    @Override
    public void onExecutionFinish(DJIError error) {
        if (!running) return;
        if (error == null) {
            VoiceAlerts va = VoiceAlerts.get(context);
            va.say("missiondone", va.line("Mission complete.", "Roof scan done. Nailed it.",
                    "Mission complete. " + photosTaken + " photos in the bag."), 2000);
            end("Mission complete: " + photosTaken + " photos." + (mission.goHome ? " Flying home." : ""), true);
        } else {
            end("Mission stopped: " + error.getDescription(), false);
        }
    }

    @Override
    public void onDownloadUpdate(WaypointMissionDownloadEvent e) {
    }

    private void onRemote(HardwareState h) {
        if (!running) return;
        boolean sticks = moved(h.getLeftStick()) || moved(h.getRightStick());
        boolean pause = h.getPauseButton() != null && h.getPauseButton().isClicked();
        if (sticks || pause) stop("Stopped: you took control.");
    }

    private static boolean moved(dji.common.Stick s) {
        return s != null && (Math.abs(s.getHorizontalPosition()) > STICK_DEADBAND
                || Math.abs(s.getVerticalPosition()) > STICK_DEADBAND);
    }

    private synchronized void end(String reason, boolean completed) {
        if (!running) return;
        running = false;
        if (op != null) op.removeListener(this);
        if (rc != null) rc.setHardwareStateCallback(null);
        MissionLog.ended(context);
        Mission m = mission;
        main.post(() -> {
            if (listener != null) listener.onMissionEnded(m, reason, completed);
        });
    }

    private void progress(String text) {
        Mission m = mission;
        main.post(() -> {
            if (listener != null && running) listener.onMissionProgress(m, text);
        });
    }
}
