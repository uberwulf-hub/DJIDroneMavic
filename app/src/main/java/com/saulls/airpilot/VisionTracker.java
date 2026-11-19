package com.saulls.airpilot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.remotecontroller.HardwareState;
import dji.sdk.base.BaseProduct;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.sdk.remotecontroller.RemoteController;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Tracking that runs on the phone instead of inside the drone: the live video is checked about
 * ten times a second by an on-phone object detector (people, bikes, cars, dogs), and the drone is
 * steered with virtual sticks to keep the chosen subject centred (and, in Follow, the same size).
 */
public final class VisionTracker {

    public enum Mode { FOLLOW, SPOTLIGHT }

    public static final class Det {
        public final RectF box;   // normalized 0..1 video coordinates
        public final int cls;
        public final String label;
        public final float score;

        Det(RectF box, int cls, String label, float score) {
            this.box = box;
            this.cls = cls;
            this.label = label;
            this.score = score;
        }
    }

    public interface Listener {
        /** While choosing: everything the phone sees. */
        void onVisionDetections(List<Det> dets);

        /** While tracking: the subject's box (null when lost), a status line and 2 good / 1 unsure / -1 lost. */
        void onVisionTrack(RectF box, String status, int quality);

        void onVisionEnded(String reason);
    }

    private static VisionTracker instance;

    public static synchronized VisionTracker get(Context c) {
        if (instance == null) instance = new VisionTracker(c.getApplicationContext());
        return instance;
    }

    // which COCO classes we offer (index into the model's output, label list is offset by one)
    private static final int PERSON = 0, BICYCLE = 1, CAR = 2, MOTORCYCLE = 3, DOG = 17;
    private static final int SIZE = 300;
    private static final long TICK_MS = 100;
    private static final int STICK_DEADBAND = 80;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;
    private Interpreter interpreter;
    private String[] labels;
    private final ByteBuffer input = ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder());
    private final int[] pixels = new int[SIZE * SIZE];
    private Bitmap frame;
    private volatile boolean busy;

    private Listener listener;
    private View video;
    private Mode mode = Mode.FOLLOW;
    private boolean recordVideo, weRecording;
    private volatile boolean choosing, running;

    // tracking state
    private volatile RectF target;     // smoothed box
    private RectF velocity = new RectF();
    private int targetCls;
    private float targetHeight0;
    private volatile long lastSeenMs, lastFrameMs;
    private String lastStatus;
    private boolean saidLost;

    // control
    private FlightController fc;
    private RemoteController rc;
    private dji.sdk.gimbal.Gimbal gimbal;
    private Timer timer;
    private double cmdForward, cmdYawRate;
    private long lastGimbalMs, startedMs;

    private VisionTracker(Context c) {
        context = c;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isChoosing() {
        return choosing;
    }

    public boolean isRunning() {
        return running;
    }

    public Mode mode() {
        return mode;
    }

    // ------------------------------------------------------------ start / choose / stop

    /** Start looking for subjects in the video. Returns null if started, else why not. */
    public String beginChoosing(View videoView, Mode m, boolean record) {
        if (running || choosing) return "Tracking is already running.";
        if (!(videoView instanceof TextureView) && !(videoView instanceof SurfaceView))
            return "Live video isn't showing yet.";
        if (videoView instanceof SurfaceView && Build.VERSION.SDK_INT < 24)
            return "This needs Android 7 or newer.";
        Telemetry.Snapshot s = Telemetry.get(context).last();
        if (!s.flying) return "Take off first, then point the camera at the subject.";
        try {
            loadModel();
        } catch (Exception e) {
            return "Couldn't load the vision model: " + e.getMessage();
        }
        video = videoView;
        mode = m;
        recordVideo = record;
        choosing = true;
        target = null;
        startFrames();
        FlightRecorder.event(context, "Vision " + title() + ": choosing a subject");
        return null;
    }

    /** The pilot picked one of the boxes shown by onVisionDetections. */
    public String pick(Det d) {
        if (!choosing || d == null) return "Nothing to pick.";
        String problem = takeControl();
        if (problem != null) {
            stopAll(problem);
            return problem;
        }
        choosing = false;
        target = new RectF(d.box);
        velocity = new RectF();
        targetCls = d.cls;
        targetHeight0 = d.box.height();
        lastSeenMs = System.currentTimeMillis();
        lastStatus = null;
        saidLost = false;
        FlightRecorder.event(context, "Vision " + title() + ": tracking a " + d.label);
        VoiceAlerts va = VoiceAlerts.get(context);
        va.say("vision", va.line("Tracking the " + d.label + ".",
                "Locked on to the " + d.label + ". Act natural.",
                "Got the " + d.label + ". I'll be right behind you. Not in a creepy way.",
                "Target acquired. Smile, you're on drone camera."), 5_000);
        return null;
    }

    public void cancelChoosing() {
        if (choosing) stopAll("Cancelled.");
    }

    public void stop(String reason) {
        if (choosing || running) stopAll(reason);
    }

    private String title() {
        return mode == Mode.FOLLOW ? "Follow" : "Spotlight";
    }

    // ------------------------------------------------------------ frames + detection

    private void loadModel() throws Exception {
        if (interpreter != null) return;
        ByteBuffer model;
        try (InputStream in = context.getAssets().open("detect.tflite")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            byte[] all = out.toByteArray();
            model = ByteBuffer.allocateDirect(all.length).order(ByteOrder.nativeOrder());
            model.put(all);
            model.rewind();
        }
        List<String> l = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(context.getAssets().open("labelmap.txt")))) {
            String line;
            while ((line = r.readLine()) != null) l.add(line.trim());
        }
        labels = l.toArray(new String[0]);
        Interpreter.Options o = new Interpreter.Options();
        o.setNumThreads(4);
        interpreter = new Interpreter(model, o);
    }

    private final Runnable frameLoop = new Runnable() {
        @Override
        public void run() {
            if (!choosing && !running) return;
            grabFrame();
            main.postDelayed(this, TICK_MS);
        }
    };

    private void startFrames() {
        if (workerThread == null) {
            workerThread = new HandlerThread("vision");
            workerThread.start();
            worker = new Handler(workerThread.getLooper());
        }
        if (frame == null) frame = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        main.removeCallbacks(frameLoop);
        main.post(frameLoop);
    }

    /** Copy the current video picture (squashed to 300x300, which is what the model expects). */
    private void grabFrame() {
        if (busy || video == null) return;
        if (video instanceof TextureView) {
            TextureView tv = (TextureView) video;
            if (!tv.isAvailable()) return;
            busy = true;
            tv.getBitmap(frame);
            worker.post(this::detect);
        } else if (Build.VERSION.SDK_INT >= 24) {
            SurfaceView sv = (SurfaceView) video;
            if (sv.getHolder() == null || sv.getHolder().getSurface() == null || !sv.getHolder().getSurface().isValid()) return;
            busy = true;
            try {
                PixelCopy.request(sv, frame, result -> {
                    if (result == PixelCopy.SUCCESS) worker.post(this::detect);
                    else busy = false;
                }, worker);
            } catch (Exception e) {
                busy = false;
            }
        }
    }

    private final float[][][] outBoxes = new float[1][10][4];
    private final float[][] outClasses = new float[1][10];
    private final float[][] outScores = new float[1][10];
    private final float[] outCount = new float[1];

    private void detect() {
        try {
            if (interpreter == null) return;
            frame.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
            input.rewind();
            for (int p : pixels) {
                input.put((byte) ((p >> 16) & 0xFF));
                input.put((byte) ((p >> 8) & 0xFF));
                input.put((byte) (p & 0xFF));
            }
            input.rewind();
            Map<Integer, Object> out = new HashMap<>();
            out.put(0, outBoxes);
            out.put(1, outClasses);
            out.put(2, outScores);
            out.put(3, outCount);
            interpreter.runForMultipleInputsOutputs(new Object[]{input}, out);
            List<Det> dets = new ArrayList<>();
            int n = Math.min(10, (int) outCount[0]);
            for (int i = 0; i < n; i++) {
                int cls = (int) outClasses[0][i];
                if (cls != PERSON && cls != BICYCLE && cls != CAR && cls != MOTORCYCLE && cls != DOG) continue;
                float sc = outScores[0][i];
                if (sc < 0.3f) continue;
                float[] b = outBoxes[0][i]; // ymin, xmin, ymax, xmax
                RectF r = new RectF(clamp01(b[1]), clamp01(b[0]), clamp01(b[3]), clamp01(b[2]));
                if (r.width() < 0.01f || r.height() < 0.01f) continue;
                String label = cls + 1 < labels.length ? labels[cls + 1] : "subject";
                dets.add(new Det(r, cls, label, sc));
            }
            lastFrameMs = System.currentTimeMillis();
            onDetections(dets);
        } catch (Exception e) {
            // a bad frame: skip it
        } finally {
            busy = false;
        }
    }

    private void onDetections(List<Det> dets) {
        if (choosing) {
            List<Det> shown = new ArrayList<>();
            for (Det d : dets) if (d.score >= 0.45f) shown.add(d);
            main.post(() -> {
                if (choosing && listener != null) listener.onVisionDetections(shown);
            });
            return;
        }
        if (!running || target == null) return;
        RectF prev = target;
        RectF predicted = new RectF(prev.left + velocity.left, prev.top + velocity.top,
                prev.right + velocity.right, prev.bottom + velocity.bottom);
        Det best = null;
        double bestScore = -1;
        for (Det d : dets) {
            if (!sameKind(d.cls, targetCls)) continue;
            double iou = iou(predicted, d.box);
            double dist = Math.hypot(predicted.centerX() - d.box.centerX(), predicted.centerY() - d.box.centerY());
            double sizeRatio = d.box.height() / Math.max(0.01, predicted.height());
            if (iou < 0.05 && dist > 0.18) continue;          // too far from where it should be
            if (sizeRatio < 0.5 || sizeRatio > 2.0) continue;  // too different in size
            double sc = 2 * iou + (1 - Math.min(1, dist / 0.25)) + 0.3 * d.score;
            if (sc > bestScore) {
                bestScore = sc;
                best = d;
            }
        }
        long now = System.currentTimeMillis();
        if (best != null) {
            float a = 0.6f; // smoothing
            RectF b = best.box;
            RectF next = new RectF(prev.left + a * (b.left - prev.left), prev.top + a * (b.top - prev.top),
                    prev.right + a * (b.right - prev.right), prev.bottom + a * (b.bottom - prev.bottom));
            velocity = new RectF(0.5f * (next.left - prev.left), 0.5f * (next.top - prev.top),
                    0.5f * (next.right - prev.right), 0.5f * (next.bottom - prev.bottom));
            target = next;
            lastSeenMs = now;
            if (saidLost) {
                saidLost = false;
                VoiceAlerts va = VoiceAlerts.get(context);
                va.say("visionback", va.line("Subject found.", "Found you again!", "There you are. Don't scare me like that."), 5_000);
            }
            post(next, title() + " · tracking the " + label(targetCls), best.score > 0.55f ? 2 : 1);
        } else {
            velocity = new RectF(); // stop predicting motion we can't see
            long lost = now - lastSeenMs;
            post(lost < 600 ? prev : null, lost < 600 ? title() + " · tracking" : "Lost sight of the subject — hovering", lost < 600 ? 1 : -1);
        }
    }

    private boolean sameKind(int a, int b) {
        if (a == b) return true;
        // a person on a bike is sometimes seen as the bike
        boolean ridden = (a == BICYCLE || a == MOTORCYCLE) && b == PERSON || (b == BICYCLE || b == MOTORCYCLE) && a == PERSON;
        return ridden;
    }

    private String label(int cls) {
        return labels != null && cls + 1 < labels.length ? labels[cls + 1] : "subject";
    }

    private void post(RectF box, String status, int q) {
        RectF copy = box == null ? null : new RectF(box);
        main.post(() -> {
            if (running && listener != null) listener.onVisionTrack(copy, status, q);
        });
        if (!status.equals(lastStatus)) {
            lastStatus = status;
            FlightRecorder.event(context, "Vision " + status);
        }
    }

    // ------------------------------------------------------------ flying

    private String takeControl() {
        BaseProduct product = DJISDKManager.getInstance().getProduct();
        if (!(product instanceof Aircraft) || !product.isConnected()) return "Drone not connected.";
        Aircraft aircraft = (Aircraft) product;
        fc = aircraft.getFlightController();
        rc = aircraft.getRemoteController();
        gimbal = aircraft.getGimbal();
        if (fc == null) return "Flight controller not available.";
        Telemetry.Snapshot s = Telemetry.get(context).last();
        if (!s.flying) return "Take off first.";
        if (s.flightMode != null && s.flightMode.toLowerCase(java.util.Locale.US).contains("sport"))
            return "Switch Sport mode off on the controller first (back to Normal).";
        if (s.goingHome) return "The drone is returning home.";
        if (s.lowBattery || (s.batteryPct >= 0 && s.batteryPct < 20)) return "Battery too low.";
        if (SmartMove.get(context).isRunning() || ActiveTracker.get().isRunning()) return "Another move is running.";

        fc.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        fc.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
        fc.setVerticalControlMode(VerticalControlMode.VELOCITY);
        fc.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
        running = true;
        cmdForward = 0;
        cmdYawRate = 0;
        startedMs = System.currentTimeMillis();
        if (rc != null) rc.setHardwareStateCallback(this::onRemote);
        fc.setVirtualStickModeEnabled(true, error -> {
            if (error != null) {
                String d = error.getDescription() == null ? "" : error.getDescription();
                stopAll("Couldn't take control: " + d);
                return;
            }
            if (recordVideo) startRecording();
            timer = new Timer("vision-track");
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    tick();
                }
            }, 0, TICK_MS);
        });
        return null;
    }

    private void tick() {
        if (!running) return;
        Telemetry.Snapshot s = Telemetry.get(context).last();
        long now = System.currentTimeMillis();
        if (now - s.time > 1500) {
            stopAll("Lost contact with the drone's sensors.");
            return;
        }
        if (!s.flying) {
            stopAll("Drone landed.");
            return;
        }
        if (s.goingHome) {
            stopAll("Return to home started.");
            return;
        }
        if (s.lowBattery || s.criticalBattery) {
            stopAll("Low battery.");
            return;
        }
        if (!Double.isNaN(s.distanceM) && s.distanceM > 300) {
            stopAll("Too far from home (1,000 ft). Stopped.");
            return;
        }
        RectF t = target;
        long lost = now - lastSeenMs;
        boolean videoStale = now - lastFrameMs > 1000;
        double wantForward = 0, wantYaw = 0, up = 0;
        if (t != null && lost < 600 && !videoStale) {
            double ex = t.centerX() - 0.5;
            if (Math.abs(ex) > 0.03) wantYaw = clamp(ex * 110, -50, 50);
            if (mode == Mode.FOLLOW) {
                double err = (targetHeight0 - t.height()) / Math.max(0.05, targetHeight0); // + = subject got smaller
                if (Math.abs(err) > 0.08) wantForward = clamp(err * 6, -2, 6);
                // Don't drive into something the sensors see.
                if (wantForward > 0 && s.obstacleClose && "in front".equals(s.obstacleSide) && s.obstacleM < 6) wantForward = 0;
                if (wantForward < 0 && s.obstacleClose && "behind".equals(s.obstacleSide) && s.obstacleM < 6) wantForward = 0;
            }
            aimGimbal(t, now);
        } else if (lost > 10_000 || (videoStale && now - lastFrameMs > 10_000)) {
            stopAll(videoStale ? "The video stopped, so tracking stopped." : "Couldn't find the subject for 10 seconds. Stopped.");
            return;
        } else if (lost > 1_500 && !saidLost) {
            saidLost = true;
            VoiceAlerts va = VoiceAlerts.get(context);
            va.say("visionlost", va.line("Subject lost. Hovering.",
                    "Lost you! Hovering here. Come back into view.",
                    "Where'd you go? I'm hovering until I spot you again.",
                    "Lost sight of you. I'll wait right here like a loyal drone."), 10_000);
        }
        if (!Double.isNaN(s.altitudeM) && s.altitudeM < 2.0) up = 0.4; // keep clear of heads and the ground
        // gentle acceleration
        cmdForward += clamp(wantForward - cmdForward, -0.35, 0.35);
        cmdYawRate += clamp(wantYaw - cmdYawRate, -12, 12);
        FlightController f = fc;
        if (f != null && running) {
            // DJI quirk (body frame, velocity mode): "pitch" is sideways, "roll" is forward.
            f.sendVirtualStickFlightControlData(new FlightControlData(0f, (float) cmdForward, (float) cmdYawRate, (float) up), null);
        }
    }

    /** Small relative tilts to keep the subject a little below the middle of the picture. */
    private void aimGimbal(RectF t, long now) {
        if (gimbal == null || now - lastGimbalMs < 300) return;
        double ey = t.centerY() - 0.55;
        if (Math.abs(ey) < 0.07) return;
        lastGimbalMs = now;
        float step = (float) clamp(-ey * 25, -6, 6);
        gimbal.rotate(new dji.common.gimbal.Rotation.Builder().mode(dji.common.gimbal.RotationMode.RELATIVE_ANGLE)
                .pitch(step).time(0.3).build(), null);
    }

    private void onRemote(HardwareState h) {
        if (!running) return;
        boolean sticks = moved(h.getLeftStick()) || moved(h.getRightStick());
        boolean pause = h.getPauseButton() != null && h.getPauseButton().isClicked();
        boolean home = h.getGoHomeButton() != null && h.getGoHomeButton().isClicked();
        if (sticks || pause || home) stopAll("Stopped: you took control.");
    }

    private static boolean moved(dji.common.Stick s) {
        return s != null && (Math.abs(s.getHorizontalPosition()) > STICK_DEADBAND
                || Math.abs(s.getVerticalPosition()) > STICK_DEADBAND);
    }

    private void startRecording() {
        BaseProduct p = DJISDKManager.getInstance().getProduct();
        dji.sdk.camera.Camera cam = p != null ? p.getCamera() : null;
        if (cam == null) return;
        cam.setMode(dji.common.camera.SettingsDefinitions.CameraMode.RECORD_VIDEO,
                err -> cam.startRecordVideo(e -> weRecording = e == null));
    }

    private synchronized void stopAll(String reason) {
        boolean wasActive = running || choosing;
        boolean wasRunning = running;
        running = false;
        choosing = false;
        target = null;
        main.removeCallbacks(frameLoop);
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        if (wasRunning && fc != null) {
            for (int i = 0; i < 3; i++) fc.sendVirtualStickFlightControlData(new FlightControlData(0, 0, 0, 0), null);
            fc.setVirtualStickModeEnabled(false, null);
            fc.setYawControlMode(YawControlMode.ANGLE); // what the other moves expect
        }
        if (rc != null) rc.setHardwareStateCallback(null);
        if (weRecording) {
            BaseProduct p = DJISDKManager.getInstance().getProduct();
            dji.sdk.camera.Camera cam = p != null ? p.getCamera() : null;
            if (cam != null) main.postDelayed(() -> cam.stopRecordVideo(null), 300);
        }
        weRecording = false;
        if (!wasActive) return;
        FlightRecorder.event(context, "Vision " + title() + " ended: " + reason);
        main.post(() -> {
            if (listener != null) listener.onVisionEnded(reason);
        });
    }

    // ------------------------------------------------------------ helpers

    private static double iou(RectF a, RectF b) {
        float l = Math.max(a.left, b.left), t = Math.max(a.top, b.top);
        float r = Math.min(a.right, b.right), btm = Math.min(a.bottom, b.bottom);
        float inter = Math.max(0, r - l) * Math.max(0, btm - t);
        float uni = a.width() * a.height() + b.width() * b.height() - inter;
        return uni <= 0 ? 0 : inter / uni;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
