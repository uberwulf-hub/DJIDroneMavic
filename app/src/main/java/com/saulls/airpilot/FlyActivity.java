package com.saulls.airpilot;

import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import dji.keysdk.CameraKey;
import dji.keysdk.KeyManager;
import dji.ux.panel.CameraSettingAdvancedPanel;
import dji.ux.panel.CameraSettingExposurePanel;
import dji.ux.utils.DJIProductUtil;
import dji.ux.widget.FPVOverlayWidget;
import dji.ux.widget.FPVWidget;
import dji.ux.widget.ThermalPaletteWidget;
import dji.ux.widget.config.CameraConfigApertureWidget;
import dji.ux.widget.config.CameraConfigEVWidget;
import dji.ux.widget.config.CameraConfigISOAndEIWidget;
import dji.ux.widget.config.CameraConfigSSDWidget;
import dji.ux.widget.config.CameraConfigShutterWidget;
import dji.ux.widget.config.CameraConfigStorageWidget;
import dji.ux.widget.config.CameraConfigWBWidget;
import dji.ux.widget.controls.CameraControlsWidget;
import dji.ux.widget.controls.LensControlWidget;

/**
 * Activity that shows all the UI elements together
 */
public class FlyActivity extends Activity implements Telemetry.Listener, SmartMove.Listener {

    private FlightMap mapWidget;
    private ViewGroup parentView;
    private FPVWidget fpvWidget;
    private FPVWidget secondaryFPVWidget;
    private FPVOverlayWidget fpvOverlayWidget;
    private RelativeLayout primaryVideoView;
    private FrameLayout secondaryVideoView;
    private boolean isMapMini = true;

    private CameraSettingExposurePanel cameraSettingExposurePanel;
    private CameraSettingAdvancedPanel cameraSettingAdvancedPanel;
    private CameraConfigISOAndEIWidget cameraConfigISOAndEIWidget;
    private CameraConfigShutterWidget cameraConfigShutterWidget;
    private CameraConfigApertureWidget cameraConfigApertureWidget;
    private CameraConfigEVWidget cameraConfigEVWidget;
    private CameraConfigWBWidget cameraConfigWBWidget;
    private CameraConfigStorageWidget cameraConfigStorageWidget;
    private CameraConfigSSDWidget cameraConfigSSDWidget;
    private CameraControlsWidget controlsWidget;
    private LensControlWidget lensControlWidget;
    private ThermalPaletteWidget thermalPaletteWidget;


    // Views that only show in Advanced (Pro) mode
    private static final int[] ADVANCED_ONLY = {
            R.id.camera, R.id.camera2, R.id.manual_focus, R.id.camera_lens_control,
            R.id.expose_switch, R.id.dashboard_widget
    };

    private TextView modeToggle;
    private View closePanelsButton;
    private View moveBanner;
    private TextView moveText;
    private boolean wasFlying;
    private long flightStartedAt;
    private android.app.AlertDialog landingDialog;
    private int edgeInset;
    private View simpleHud;
    private TextView hudAlt, hudDist, hudSpeed, hudVSpeed, hudTime;
    private boolean advanced;
    private boolean imperial;

    private int height;
    private int width;
    private int margin;
    private int deviceWidth;
    private int deviceHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fly);

        height = DensityUtil.dip2px(this, 100);
        width = DensityUtil.dip2px(this, 150);
        margin = DensityUtil.dip2px(this, 12);

        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        final Display display = windowManager.getDefaultDisplay();
        Point outPoint = new Point();
        display.getRealSize(outPoint);
        deviceHeight = outPoint.y;
        deviceWidth = outPoint.x;

        mapWidget = findViewById(R.id.map_widget);
        mapWidget.setOnClickListener(v -> onViewClick(mapWidget));

        initCameraView();
        parentView = (ViewGroup) findViewById(R.id.root_view);

        fpvWidget = findViewById(R.id.fpv_widget);
        fpvWidget.setOnClickListener(view -> onViewClick(fpvWidget));
        fpvOverlayWidget = findViewById(R.id.fpv_overlay_widget);
        primaryVideoView = findViewById(R.id.fpv_container);
        secondaryVideoView = findViewById(R.id.secondary_video_view);
        secondaryFPVWidget = findViewById(R.id.secondary_fpv_widget);
        secondaryFPVWidget.setOnClickListener(view -> swapVideoSource());

        fpvWidget.setCameraIndexListener((cameraIndex, lensIndex) -> cameraWidgetKeyIndexUpdated(fpvWidget.getCameraKeyIndex(), fpvWidget.getLensKeyIndex()));
        updateSecondaryVideoVisibility();

        // Never let the screen sleep while flying.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        simpleHud = findViewById(R.id.simple_hud);
        hudAlt = findViewById(R.id.hud_alt);
        hudDist = findViewById(R.id.hud_dist);
        hudSpeed = findViewById(R.id.hud_speed);
        hudVSpeed = findViewById(R.id.hud_vspeed);
        hudTime = findViewById(R.id.hud_time);
        modeToggle = findViewById(R.id.mode_toggle);
        findViewById(R.id.btn_home).setOnClickListener(v -> finish());
        applyEdgeInsets();
        moveBanner = findViewById(R.id.move_banner);
        moveText = findViewById(R.id.move_text);
        findViewById(R.id.btn_moves).setOnClickListener(v -> showMovesDialog());
        findViewById(R.id.move_stop).setOnClickListener(v -> SmartMove.get(this).stop("Stopped."));
        closePanelsButton = findViewById(R.id.btn_close_panels);
        closePanelsButton.setOnClickListener(v -> closeAllPanels());
        parentView.getViewTreeObserver().addOnGlobalLayoutListener(this::updateCloseButton);
        enlargeCameraMenus();
        modeToggle.setOnClickListener(v -> {
            Prefs.setAdvanced(this, !advanced);
            applyMode();
        });
    }

    // ------------------------------------------------------------ smart moves

    private void showMovesDialog() {
        if (SmartMove.get(this).isRunning()) return;
        boolean imperial = Prefs.isImperial(this);
        View v = getLayoutInflater().inflate(R.layout.dialog_moves, null);
        android.widget.RadioGroup type = v.findViewById(R.id.move_type);
        TextView help = v.findViewById(R.id.move_help);
        TextView sizeLabel = v.findViewById(R.id.size_label);
        android.widget.RadioButton sizeS = v.findViewById(R.id.size_s);
        android.widget.RadioButton sizeM = v.findViewById(R.id.size_m);
        android.widget.RadioButton sizeL = v.findViewById(R.id.size_l);
        View orbitOptions = v.findViewById(R.id.orbit_options);
        final double[] orbitSizes = {10, 15, 30};
        final double[] dronieSizes = {30, 50, 100};

        Runnable refresh = () -> {
            boolean orbit = type.getCheckedRadioButtonId() == R.id.type_orbit;
            double[] sizes = orbit ? orbitSizes : dronieSizes;
            sizeLabel.setText(orbit ? "CIRCLE RADIUS" : "HOW FAR BACK");
            sizeS.setText(Units.distance(sizes[0], imperial));
            sizeM.setText(Units.distance(sizes[1], imperial));
            sizeL.setText(Units.distance(sizes[2], imperial));
            orbitOptions.setVisibility(orbit ? View.VISIBLE : View.GONE);
            help.setText(orbit
                    ? "Circles the spot the camera is pointing at, keeping it in frame. Point the camera at your subject first."
                    : "Flies backward and up while filming you, for a reveal shot. Face the camera at yourself first.");
        };
        type.setOnCheckedChangeListener((g, id) -> refresh.run());
        refresh.run();

        new android.app.AlertDialog.Builder(this)
                .setTitle("Smart moves")
                .setView(v)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start", (d, w) -> {
                    SmartMove.Params p = new SmartMove.Params();
                    boolean orbit = type.getCheckedRadioButtonId() == R.id.type_orbit;
                    p.type = orbit ? SmartMove.Type.ORBIT : SmartMove.Type.DRONIE;
                    int sizeId = ((android.widget.RadioGroup) v.findViewById(R.id.size)).getCheckedRadioButtonId();
                    int idx = sizeId == R.id.size_s ? 0 : sizeId == R.id.size_l ? 2 : 1;
                    p.radiusM = orbitSizes[idx];
                    p.distanceM = dronieSizes[idx];
                    int speedId = ((android.widget.RadioGroup) v.findViewById(R.id.speed)).getCheckedRadioButtonId();
                    double[] speeds = orbit ? new double[]{1.5, 3, 5} : new double[]{2, 4, 6};
                    p.speedMps = speeds[speedId == R.id.speed_slow ? 0 : speedId == R.id.speed_fast ? 2 : 1];
                    p.clockwise = ((android.widget.RadioGroup) v.findViewById(R.id.direction))
                            .getCheckedRadioButtonId() == R.id.dir_cw;
                    p.continuous = ((android.widget.CheckBox) v.findViewById(R.id.continuous)).isChecked();
                    p.recordVideo = ((android.widget.CheckBox) v.findViewById(R.id.record)).isChecked();
                    String problem = SmartMove.get(this).start(p);
                    if (problem != null) {
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("Can't start")
                                .setMessage(problem)
                                .setPositiveButton("OK", null)
                                .show();
                    } else {
                        moveText.setText(orbit ? "Orbit starting…" : "Dronie starting…");
                        moveBanner.setVisibility(View.VISIBLE);
                    }
                })
                .show();
    }

    @Override
    public void onMoveProgress(SmartMove.Type type, float progress, String text) {
        moveBanner.setVisibility(View.VISIBLE);
        moveText.setText(text);
    }

    @Override
    public void onMoveEnded(SmartMove.Type type, String reason, boolean completed) {
        moveBanner.setVisibility(View.GONE);
        android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------ after landing

    private void onLanded() {
        if (!Prefs.isAutoDownload(this) || isFinishing()) return;
        long since = flightStartedAt > 0 ? flightStartedAt : System.currentTimeMillis() - 30 * 60 * 1000L;
        final int[] seconds = {5};
        landingDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Landed")
                .setMessage("Downloading this flight's photos and videos in 5…")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Download now", (d, w) -> startAutoDownload(since))
                .show();
        Runnable countdown = new Runnable() {
            @Override
            public void run() {
                if (landingDialog == null || !landingDialog.isShowing()) return;
                seconds[0]--;
                if (seconds[0] <= 0) {
                    landingDialog.dismiss();
                    startAutoDownload(since);
                } else {
                    landingDialog.setMessage("Downloading this flight's photos and videos in " + seconds[0] + "…");
                    parentView.postDelayed(this, 1000);
                }
            }
        };
        parentView.postDelayed(countdown, 1000);
    }

    private void startAutoDownload(long since) {
        if (isFinishing() || Telemetry.get(this).last().flying) return;
        startActivity(new android.content.Intent(this, MediaActivity.class)
                .putExtra(MediaActivity.EXTRA_AUTO_SINCE, since));
    }

    /**
     * Keeps the buttons away from the phone's edges (where they're hard to tap, especially
     * without a case) while the live video still fills the whole screen.
     */
    private void applyEdgeInsets() {
        edgeInset = DensityUtil.dip2px(this, 28);
        parentView.setPadding(edgeInset, 0, edgeInset, 0);
        for (int id : new int[]{R.id.fpv_container, R.id.fpv_overlay_widget}) {
            View v = findViewById(id);
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) v.getLayoutParams();
            lp.leftMargin = -edgeInset;
            lp.rightMargin = -edgeInset;
            v.setLayoutParams(lp);
        }
    }

    private boolean anyPanelOpen() {
        for (int id : new int[]{R.id.camera_setting_exposure_panel, R.id.camera_setting_advanced_panel,
                R.id.pre_flight_check_list, R.id.rtk_panel, R.id.spotlight_panel, R.id.speaker_panel}) {
            View v = findViewById(id);
            if (v != null && v.getVisibility() == View.VISIBLE) return true;
        }
        return false;
    }

    private void updateCloseButton() {
        if (closePanelsButton == null) return;
        int want = anyPanelOpen() ? View.VISIBLE : View.GONE;
        if (closePanelsButton.getVisibility() != want) closePanelsButton.setVisibility(want);
    }

    private void closeAllPanels() {
        controlsWidget.setAdvancedPanelVisibility(false);
        controlsWidget.setExposurePanelVisibility(false);
        for (int id : new int[]{R.id.camera_setting_exposure_panel, R.id.camera_setting_advanced_panel,
                R.id.pre_flight_check_list, R.id.rtk_panel, R.id.spotlight_panel, R.id.speaker_panel}) {
            View v = findViewById(id);
            if (v != null) v.setVisibility(View.GONE);
        }
        updateCloseButton();
    }

    @Override
    public void onBackPressed() {
        if (anyPanelOpen()) {
            closeAllPanels();
            return;
        }
        super.onBackPressed();
    }

    /** DJI draws its camera menus for tablets; scale them up so the text is readable on a phone. */
    private void enlargeCameraMenus() {
        final float scale = 1.25f;
        for (View panel : new View[]{cameraSettingAdvancedPanel, cameraSettingExposurePanel}) {
            panel.post(() -> {
                panel.setPivotX(panel.getWidth()); // grow toward the left, away from the shutter column
                panel.setPivotY(0);
                panel.setScaleX(scale);
                panel.setScaleY(scale);
            });
        }
    }

    /** Shows or hides the pro camera controls. Hidden views keep their space so nothing jumps around. */
    private void applyMode() {
        advanced = Prefs.isAdvanced(this);
        for (int id : ADVANCED_ONLY) {
            View v = findViewById(id);
            if (v != null) v.setVisibility(advanced ? View.VISIBLE : View.INVISIBLE);
        }
        simpleHud.setVisibility(advanced ? View.GONE : View.VISIBLE);
        modeToggle.setText(advanced ? "PRO  \u25B8 Simple" : "SIMPLE  \u25B8 Pro");
        if (!advanced) {
            controlsWidget.setAdvancedPanelVisibility(false);
            controlsWidget.setExposurePanelVisibility(false);
        }
    }

    @Override
    public void onTelemetry(Telemetry.Snapshot s) {
        if (s.flying && !wasFlying) flightStartedAt = System.currentTimeMillis();
        if (!s.flying && wasFlying) onLanded();
        wasFlying = s.flying;
        mapWidget.update(s);
        hudAlt.setText(Units.distance(s.altitudeM, imperial));
        hudDist.setText(Units.distance(s.distanceM, imperial));
        hudSpeed.setText(Units.speed(s.horizontalSpeedMps, imperial));
        hudVSpeed.setText(Units.speed(s.verticalSpeedMps, imperial));
        hudTime.setText(s.flying || s.flightTimeS > 0 ? Units.duration(s.flightTimeS) : "--");
    }

    private void initCameraView() {
        cameraSettingExposurePanel = findViewById(R.id.camera_setting_exposure_panel);
        cameraSettingAdvancedPanel = findViewById(R.id.camera_setting_advanced_panel);
        cameraConfigISOAndEIWidget = findViewById(R.id.camera_config_iso_and_ei_widget);
        cameraConfigShutterWidget = findViewById(R.id.camera_config_shutter_widget);
        cameraConfigApertureWidget = findViewById(R.id.camera_config_aperture_widget);
        cameraConfigEVWidget = findViewById(R.id.camera_config_ev_widget);
        cameraConfigWBWidget = findViewById(R.id.camera_config_wb_widget);
        cameraConfigStorageWidget = findViewById(R.id.camera_config_storage_widget);
        cameraConfigSSDWidget = findViewById(R.id.camera_config_ssd_widget);
        lensControlWidget = findViewById(R.id.camera_lens_control);
        controlsWidget = findViewById(R.id.CameraCapturePanel);
        thermalPaletteWidget = findViewById(R.id.thermal_pallette_widget);
    }

    private void onViewClick(View view) {
        if (view == fpvWidget && !isMapMini) {
            resizeFPVWidget(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT, 0, 0);
            reorderCameraCapturePanel();
            ResizeAnimation mapViewAnimation = new ResizeAnimation(mapWidget, deviceWidth - 2 * edgeInset, deviceHeight, width, height, margin);
            mapWidget.startAnimation(mapViewAnimation);
            isMapMini = true;
            mapWidget.setMini(true);
        } else if (view == mapWidget && isMapMini) {
            hidePanels();
            resizeFPVWidget(width, height, margin, 12);
            reorderCameraCapturePanel();
            ResizeAnimation mapViewAnimation = new ResizeAnimation(mapWidget, width, height, deviceWidth - 2 * edgeInset, deviceHeight, 0);
            mapWidget.startAnimation(mapViewAnimation);
            isMapMini = false;
            mapWidget.setMini(false);
        }
    }

    private void resizeFPVWidget(int width, int height, int margin, int fpvInsertPosition) {
        RelativeLayout.LayoutParams fpvParams = (RelativeLayout.LayoutParams) primaryVideoView.getLayoutParams();
        fpvParams.height = height;
        fpvParams.width = width;
        boolean full = width == RelativeLayout.LayoutParams.MATCH_PARENT;
        fpvParams.rightMargin = full ? -edgeInset : margin;
        fpvParams.leftMargin = full ? -edgeInset : 0;
        fpvParams.bottomMargin = margin;
        if (isMapMini) {
            fpvParams.addRule(RelativeLayout.CENTER_IN_PARENT, 0);
            fpvParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, RelativeLayout.TRUE);
            fpvParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE);
        } else {
            fpvParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, 0);
            fpvParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, 0);
            fpvParams.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
        }
        primaryVideoView.setLayoutParams(fpvParams);

        parentView.removeView(primaryVideoView);
        parentView.addView(primaryVideoView, fpvInsertPosition);
    }

    private void reorderCameraCapturePanel() {
        View cameraCapturePanel = findViewById(R.id.CameraCapturePanel);
        parentView.removeView(cameraCapturePanel);
        parentView.addView(cameraCapturePanel, isMapMini ? 9 : 13);
    }

    private void swapVideoSource() {
        if (secondaryFPVWidget.getVideoSource() == FPVWidget.VideoSource.SECONDARY) {
            fpvWidget.setVideoSource(FPVWidget.VideoSource.SECONDARY);
            secondaryFPVWidget.setVideoSource(FPVWidget.VideoSource.PRIMARY);
        } else {
            fpvWidget.setVideoSource(FPVWidget.VideoSource.PRIMARY);
            secondaryFPVWidget.setVideoSource(FPVWidget.VideoSource.SECONDARY);
        }
    }

    private void cameraWidgetKeyIndexUpdated(int keyIndex, int subKeyIndex) {
        controlsWidget.updateKeyOnIndex(keyIndex, subKeyIndex);
        cameraSettingExposurePanel.updateKeyOnIndex(keyIndex, subKeyIndex);
        cameraSettingAdvancedPanel.updateKeyOnIndex(keyIndex, subKeyIndex);
        cameraConfigISOAndEIWidget.updateKeyOnIndex(keyIndex, subKeyIndex);
        cameraConfigShutterWidget.updateKeyOnIndex(keyIndex, subKeyIndex);
        cameraConfigApertureWidget.updateKeyOnIndex(keyIndex, subKeyIndex);
        cameraConfigEVWidget.updateKeyOnIndex(keyIndex, subKeyIndex);
        cameraConfigWBWidget.updateKeyOnIndex(keyIndex, subKeyIndex);
        cameraConfigStorageWidget.updateKeyOnIndex(keyIndex, subKeyIndex);
        cameraConfigSSDWidget.updateKeyOnIndex(keyIndex, subKeyIndex);
        controlsWidget.updateKeyOnIndex(keyIndex, subKeyIndex);
        lensControlWidget.updateKeyOnIndex(keyIndex, subKeyIndex);
        thermalPaletteWidget.updateKeyOnIndex(keyIndex, subKeyIndex);

        fpvOverlayWidget.updateKeyOnIndex(keyIndex, subKeyIndex);
    }

    private void updateSecondaryVideoVisibility() {
        if (secondaryFPVWidget.getVideoSource() == null || !DJIProductUtil.isSupportMultiCamera()) {
            secondaryVideoView.setVisibility(View.GONE);
        } else {
            secondaryVideoView.setVisibility(View.VISIBLE);
        }
    }

    private void hidePanels() {
        //These panels appear based on keys from the drone itself.
        if (KeyManager.getInstance() != null) {
            KeyManager.getInstance().setValue(CameraKey.create(CameraKey.HISTOGRAM_ENABLED, fpvWidget.getCameraKeyIndex()), false, null);
            KeyManager.getInstance().setValue(CameraKey.create(CameraKey.COLOR_WAVEFORM_ENABLED, fpvWidget.getCameraKeyIndex()), false, null);
        }

        //These panels have buttons that toggle them, so call the methods to make sure the button state is correct.
        controlsWidget.setAdvancedPanelVisibility(false);
        controlsWidget.setExposurePanelVisibility(false);

        //These panels don't have a button state, so we can just hide them.
        findViewById(R.id.pre_flight_check_list).setVisibility(View.GONE);
        findViewById(R.id.rtk_panel).setVisibility(View.GONE);
        //findViewById(R.id.simulator_panel).setVisibility(View.GONE);
        findViewById(R.id.spotlight_panel).setVisibility(View.GONE);
        findViewById(R.id.speaker_panel).setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Hide both the navigation bar and the status bar.
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        mapWidget.onResume();

        imperial = Prefs.isImperial(this);
        applyMode();
        fpvOverlayWidget.setGridOverlayEnabled(Prefs.isGrid(this));
        if (Prefs.isGrid(this)) {
            fpvOverlayWidget.setCurrentGridOverlayType(FPVOverlayWidget.GridOverlayType.PARALLEL);
        }
        Telemetry telemetry = Telemetry.get(this);
        telemetry.attach();
        wasFlying = telemetry.last().flying;
        telemetry.addListener(this);
        SmartMove.get(this).setListener(this);
        moveBanner.setVisibility(SmartMove.get(this).isRunning() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onPause() {
        Telemetry.get(this).removeListener(this);
        SmartMove.get(this).setListener(null);
        mapWidget.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mapWidget.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
    }

    private class ResizeAnimation extends Animation {

        private View mView;
        private int mToHeight;
        private int mFromHeight;

        private int mToWidth;
        private int mFromWidth;
        private int mMargin;

        private ResizeAnimation(View v, int fromWidth, int fromHeight, int toWidth, int toHeight, int margin) {
            mToHeight = toHeight;
            mToWidth = toWidth;
            mFromHeight = fromHeight;
            mFromWidth = fromWidth;
            mView = v;
            mMargin = margin;
            setDuration(300);
        }

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            float height = (mToHeight - mFromHeight) * interpolatedTime + mFromHeight;
            float width = (mToWidth - mFromWidth) * interpolatedTime + mFromWidth;
            RelativeLayout.LayoutParams p = (RelativeLayout.LayoutParams) mView.getLayoutParams();
            p.height = (int) height;
            p.width = (int) width;
            p.rightMargin = mMargin;
            p.bottomMargin = mMargin;
            mView.requestLayout();
        }
    }
}
