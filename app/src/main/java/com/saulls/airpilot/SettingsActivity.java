package com.saulls.airpilot;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dji.common.error.DJIError;
import dji.common.util.CommonCallbacks;
import dji.sdk.flightcontroller.FlightController;

/** App preferences plus the drone's own safety limits. */
public class SettingsActivity extends Activity {

    // DJI's allowed ranges, in meters / percent.
    private static final int ALT_MIN = 20, ALT_MAX = 500;
    private static final int DIST_MIN = 15, DIST_MAX = 8000;
    private static final int RTH_MIN = 20, RTH_MAX = 500;
    private static final int BATT_MIN = 15, BATT_MAX = 50;

    private Switch swAdvanced, swImperial, swGrid, swBeginner, swDistLimit;
    private EditText etMaxAlt, etMaxDist, etRthAlt, etLowBatt;
    private TextView droneState, droneHint, saveResult;
    private Button saveButton;
    private boolean imperial;
    private static final int RC_SIGN_IN = 9001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        swAdvanced = findViewById(R.id.sw_advanced);
        swImperial = findViewById(R.id.sw_imperial);
        swGrid = findViewById(R.id.sw_grid);
        swBeginner = findViewById(R.id.sw_beginner);
        swDistLimit = findViewById(R.id.sw_dist_limit);
        etMaxAlt = findViewById(R.id.et_max_alt);
        etMaxDist = findViewById(R.id.et_max_dist);
        etRthAlt = findViewById(R.id.et_rth_alt);
        etLowBatt = findViewById(R.id.et_low_batt);
        droneState = findViewById(R.id.drone_state);
        droneHint = findViewById(R.id.drone_hint);
        saveResult = findViewById(R.id.save_result);
        saveButton = findViewById(R.id.btn_save_drone);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        imperial = Prefs.isImperial(this);
        swAdvanced.setChecked(Prefs.isAdvanced(this));
        swImperial.setChecked(imperial);
        swGrid.setChecked(Prefs.isGrid(this));
        swAdvanced.setOnCheckedChangeListener((b, on) -> Prefs.setAdvanced(this, on));
        swGrid.setOnCheckedChangeListener((b, on) -> Prefs.setGrid(this, on));
        swImperial.setOnCheckedChangeListener((b, on) -> switchUnits(on));
        swDistLimit.setOnCheckedChangeListener((b, on) -> etMaxDist.setEnabled(on));

        Switch swVoice = findViewById(R.id.sw_voice);
        swVoice.setChecked(VoiceAlerts.enabled(this));
        swVoice.setOnCheckedChangeListener((b, on) -> {
            VoiceAlerts.setEnabled(this, on);
            if (on) VoiceAlerts.get(this).say("test", "Voice callouts on.", 0);
        });
        Switch swCasual = findViewById(R.id.sw_voice_casual);
        swCasual.setChecked(VoiceAlerts.casual(this));
        swCasual.setOnCheckedChangeListener((b, on) -> {
            VoiceAlerts.setCasual(this, on);
            VoiceAlerts va = VoiceAlerts.get(this);
            va.say("styletest", on ? Captain.takeoff(Telemetry.get(this).last(), Prefs.isImperial(this), new java.util.Random())
                    : "Standard voice style.", 0);
        });
        android.widget.RadioGroup ll = findViewById(R.id.low_light);
        String llm = LowLight.mode(this);
        ll.check(LowLight.ON.equals(llm) ? R.id.ll_on : LowLight.OFF.equals(llm) ? R.id.ll_off : R.id.ll_auto);
        ll.setOnCheckedChangeListener((g, id) -> {
            String m = id == R.id.ll_on ? LowLight.ON : id == R.id.ll_off ? LowLight.OFF : LowLight.AUTO;
            LowLight.setMode(this, m);
            if (!LowLight.AUTO.equals(m)) LowLight.apply(this, LowLight.ON.equals(m), null);
        });
        Switch swByDate = findViewById(R.id.sw_by_date);
        swByDate.setChecked(Prefs.byDate(this));
        swByDate.setOnCheckedChangeListener((b, on) -> Prefs.setByDate(this, on));
        findViewById(R.id.btn_crash).setOnClickListener(v -> CrashReporter.show(this));
        findViewById(R.id.btn_voice).setOnClickListener(v -> chooseVoice());
        VoiceAlerts.get(this).onReady(this::refreshVoiceLabel);
        Switch swAuto = findViewById(R.id.sw_auto_download);
        swAuto.setChecked(Prefs.isAutoDownload(this));
        swAuto.setOnCheckedChangeListener((b, on) -> Prefs.setAutoDownload(this, on));
        setupDrive();

        updateUnitLabels();
        saveButton.setOnClickListener(v -> saveToDrone());
        setupBand();
        loadFromDrone();
    }

    // ------------------------------------------------------------ video link band

    private boolean bandLoading;

    private dji.sdk.airlink.WiFiLink wifiLink() {
        dji.sdk.base.BaseProduct p = dji.sdk.sdkmanager.DJISDKManager.getInstance().getProduct();
        if (p == null || !p.isConnected() || p.getAirLink() == null || !p.getAirLink().isWiFiLinkSupported()) return null;
        return p.getAirLink().getWiFiLink();
    }

    private void setupBand() {
        android.widget.RadioGroup band = findViewById(R.id.band);
        TextView status = findViewById(R.id.band_status);
        dji.sdk.airlink.WiFiLink link = wifiLink();
        if (link == null) {
            status.setText("Connect the drone to change this.");
            for (int i = 0; i < band.getChildCount(); i++) band.getChildAt(i).setEnabled(false);
            return;
        }
        bandLoading = true;
        link.getFrequencyBand(new dji.common.util.CommonCallbacks.CompletionCallbackWith<dji.common.airlink.WiFiFrequencyBand>() {
            @Override
            public void onSuccess(dji.common.airlink.WiFiFrequencyBand b) {
                runOnUiThread(() -> {
                    band.check(b == dji.common.airlink.WiFiFrequencyBand.FREQUENCY_BAND_5_GHZ ? R.id.band_58 : R.id.band_24);
                    status.setText("Now using " + (b == dji.common.airlink.WiFiFrequencyBand.FREQUENCY_BAND_5_GHZ ? "5.8 GHz." : "2.4 GHz."));
                    bandLoading = false;
                });
            }

            @Override
            public void onFailure(dji.common.error.DJIError e) {
                runOnUiThread(() -> {
                    status.setText("Couldn't read the band: " + e.getDescription());
                    bandLoading = false;
                });
            }
        });
        band.setOnCheckedChangeListener((g, id) -> {
            if (bandLoading) return;
            dji.sdk.airlink.WiFiLink l = wifiLink();
            if (l == null) return;
            dji.common.airlink.WiFiFrequencyBand want = id == R.id.band_58
                    ? dji.common.airlink.WiFiFrequencyBand.FREQUENCY_BAND_5_GHZ
                    : dji.common.airlink.WiFiFrequencyBand.FREQUENCY_BAND_2_DOT_4_GHZ;
            status.setText("Switching… the video may drop for a few seconds.");
            l.setFrequencyBand(want, err -> runOnUiThread(() -> status.setText(err == null
                    ? "Switched to " + (id == R.id.band_58 ? "5.8 GHz." : "2.4 GHz.")
                    : "Couldn't switch: " + err.getDescription())));
        });
    }

    // ------------------------------------------------------------ voice

    private void refreshVoiceLabel() {
        String name = VoiceAlerts.voiceName(this);
        String text = "Voice: phone default";
        java.util.List<android.speech.tts.Voice> voices = VoiceAlerts.get(this).englishVoices();
        for (int i = 0; i < voices.size(); i++) {
            if (voices.get(i).getName().equals(name)) text = "Voice: " + VoiceAlerts.label(voices.get(i), i + 1);
        }
        ((TextView) findViewById(R.id.voice_label)).setText(text);
    }

    private void chooseVoice() {
        VoiceAlerts va = VoiceAlerts.get(this);
        va.onReady(() -> {
            java.util.List<android.speech.tts.Voice> voices = va.englishVoices();
            String[] items = new String[voices.size() + 1];
            items[0] = "Phone default";
            int checked = 0;
            String current = VoiceAlerts.voiceName(this);
            for (int i = 0; i < voices.size(); i++) {
                items[i + 1] = VoiceAlerts.label(voices.get(i), i + 1);
                if (voices.get(i).getName().equals(current)) checked = i + 1;
            }
            final int[] pick = {checked};
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Voice (tap to hear it)")
                    .setSingleChoiceItems(items, checked, (d, which) -> {
                        pick[0] = which;
                        if (which > 0) va.preview(voices.get(which - 1));
                    })
                    .setPositiveButton("Use this voice", (d, w) -> {
                        va.setVoice(pick[0] == 0 ? "" : voices.get(pick[0] - 1).getName());
                        refreshVoiceLabel();
                    })
                    .setNeutralButton("Get more voices", (d, w) -> {
                        va.setVoice(VoiceAlerts.voiceName(this)); // undo preview
                        try {
                            startActivity(new android.content.Intent("com.android.settings.TTS_SETTINGS"));
                        } catch (Exception e) {
                            startActivity(new android.content.Intent(
                                    android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA));
                        }
                    })
                    .setNegativeButton("Cancel", (d, w) -> va.setVoice(VoiceAlerts.voiceName(this)))
                    .show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        VoiceAlerts.get(this).onReady(this::refreshVoiceLabel);
    }

    // ------------------------------------------------------------ Google Drive

    private void setupDrive() {
        Switch swDrive = findViewById(R.id.sw_drive);
        Switch swWifi = findViewById(R.id.sw_drive_wifi);
        Switch swLogs = findViewById(R.id.sw_drive_logs);
        EditText folder = findViewById(R.id.et_drive_folder);

        swDrive.setChecked(DriveSync.isSwitchOn(this));
        swWifi.setChecked(DriveSync.wifiOnly(this));
        swLogs.setChecked(DriveSync.uploadLogs(this));
        folder.setText(DriveSync.folder(this));

        swDrive.setOnCheckedChangeListener((b, on) -> {
            if (on && DriveSync.account(this) == null) {
                startActivityForResult(DriveSync.signInIntent(this), RC_SIGN_IN);
            }
            DriveSync.setEnabled(this, on);
            refreshDrive();
        });
        swWifi.setOnCheckedChangeListener((b, on) -> DriveSync.setWifiOnly(this, on));
        swLogs.setOnCheckedChangeListener((b, on) -> DriveSync.setUploadLogs(this, on));
        folder.setOnFocusChangeListener((v, has) -> {
            if (!has) DriveSync.setFolder(this, folder.getText().toString());
        });
        folder.setOnEditorActionListener((v, actionId, ev) -> {
            DriveSync.setFolder(this, folder.getText().toString());
            folder.setText(DriveSync.folder(this));
            folder.clearFocus();
            return false;
        });
        findViewById(R.id.btn_drive_signin).setOnClickListener(v -> {
            if (DriveSync.account(this) != null) {
                DriveSync.signOut(this);
                Prefs.raw(this).edit().putBoolean("drive_enabled", false).apply();
                ((Switch) findViewById(R.id.sw_drive)).setChecked(false);
                refreshDrive();
            } else {
                startActivityForResult(DriveSync.signInIntent(this), RC_SIGN_IN);
            }
        });
        refreshDrive();
    }

    private void refreshDrive() {
        com.google.android.gms.auth.api.signin.GoogleSignInAccount a = DriveSync.account(this);
        ((TextView) findViewById(R.id.drive_account)).setText(a != null
                ? "Signed in as " + a.getEmail() : "Not signed in");
        ((Button) findViewById(R.id.btn_drive_signin)).setText(a != null ? "Sign out" : "Sign in with Google");
        int waiting = DriveSync.pendingCount(this);
        String st = DriveSync.status(this);
        String text = waiting > 0 ? waiting + " waiting to upload. " : "";
        if (!st.isEmpty()) text += "Last: " + st;
        ((TextView) findViewById(R.id.drive_status)).setText(text);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_SIGN_IN) return;
        try {
            com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(data)
                    .getResult(com.google.android.gms.common.api.ApiException.class);
            DriveSync.setEnabled(this, true);
            ((Switch) findViewById(R.id.sw_drive)).setChecked(true);
        } catch (com.google.android.gms.common.api.ApiException e) {
            String why;
            switch (e.getStatusCode()) {
                case 10:
                    why = "Google doesn't recognise this app yet (code 10). Check the Android OAuth client in "
                            + "Google Cloud uses package com.saulls.airpilot and the SHA-1 from the README.";
                    break;
                case 12501:
                    why = "Sign-in was cancelled.";
                    break;
                case 7:
                    why = "No internet connection.";
                    break;
                default:
                    why = "Google sign-in failed (code " + e.getStatusCode() + ").";
            }
            ((Switch) findViewById(R.id.sw_drive)).setChecked(false);
            Prefs.raw(this).edit().putBoolean("drive_enabled", false).apply();
            new android.app.AlertDialog.Builder(this).setTitle("Google Drive").setMessage(why)
                    .setPositiveButton("OK", null).show();
        }
        refreshDrive();
    }

    @Override
    protected void onPause() {
        EditText folder = findViewById(R.id.et_drive_folder);
        if (folder != null) DriveSync.setFolder(this, folder.getText().toString());
        super.onPause();
    }

    private void updateUnitLabels() {
        String u = Units.lengthUnit(imperial);
        ((TextView) findViewById(R.id.lbl_max_alt)).setText("Max altitude (" + u + ")");
        ((TextView) findViewById(R.id.lbl_max_dist)).setText("Max distance (" + u + ")");
        ((TextView) findViewById(R.id.lbl_rth_alt)).setText("Return-to-home altitude (" + u + ")");
    }

    /** Converts the numbers already typed so they keep meaning the same distance. */
    private void switchUnits(boolean toImperial) {
        for (EditText et : new EditText[]{etMaxAlt, etMaxDist, etRthAlt}) {
            Integer meters = readMeters(et);
            if (meters != null) et.setText(String.valueOf(Units.fromMeters(meters, toImperial)));
        }
        imperial = toImperial;
        Prefs.setImperial(this, toImperial);
        updateUnitLabels();
    }

    private void setDroneControlsEnabled(boolean enabled) {
        for (android.view.View v : new android.view.View[]{swBeginner, swDistLimit, etMaxAlt, etMaxDist, etRthAlt, etLowBatt, saveButton}) {
            v.setEnabled(enabled);
            v.setAlpha(enabled ? 1f : 0.4f);
        }
        if (enabled) etMaxDist.setEnabled(swDistLimit.isChecked());
    }

    private void loadFromDrone() {
        FlightController fc = Telemetry.flightController();
        if (fc == null) {
            droneState.setText("Drone not connected");
            droneHint.setVisibility(android.view.View.VISIBLE);
            setDroneControlsEnabled(false);
            return;
        }
        droneState.setText("Drone connected");
        droneHint.setVisibility(android.view.View.GONE);
        setDroneControlsEnabled(true);

        fc.getNoviceModeEnabled(callback(v -> swBeginner.setChecked(v)));
        fc.getMaxFlightHeight(callback(v -> etMaxAlt.setText(String.valueOf(Units.fromMeters(v, imperial)))));
        fc.getMaxFlightRadiusLimitationEnabled(callback(v -> {
            swDistLimit.setChecked(v);
            etMaxDist.setEnabled(v);
        }));
        fc.getMaxFlightRadius(callback(v -> etMaxDist.setText(String.valueOf(Units.fromMeters(v, imperial)))));
        fc.getGoHomeHeightInMeters(callback(v -> etRthAlt.setText(String.valueOf(Units.fromMeters(v, imperial)))));
        fc.getLowBatteryWarningThreshold(callback(v -> etLowBatt.setText(String.valueOf(v))));
    }

    private interface Consumer<T> {
        void accept(T t);
    }

    private <T> CommonCallbacks.CompletionCallbackWith<T> callback(Consumer<T> onValue) {
        return new CommonCallbacks.CompletionCallbackWith<T>() {
            @Override
            public void onSuccess(T value) {
                runOnUiThread(() -> onValue.accept(value));
            }

            @Override
            public void onFailure(DJIError error) {
                // Not every drone supports every setting; leave the field as it is.
            }
        };
    }

    private Integer readMeters(EditText et) {
        String t = et.getText().toString().trim();
        if (TextUtils.isEmpty(t)) return null;
        try {
            return Units.toMeters(Double.parseDouble(t), imperial);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer readInt(EditText et) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String rangeText(int minM, int maxM) {
        return Units.fromMeters(minM, imperial) + "–" + Units.fromMeters(maxM, imperial) + " " + Units.lengthUnit(imperial);
    }

    private void saveToDrone() {
        FlightController fc = Telemetry.flightController();
        if (fc == null) {
            saveResult.setText("Drone not connected.");
            return;
        }
        Integer alt = readMeters(etMaxAlt);
        Integer dist = readMeters(etMaxDist);
        Integer rth = readMeters(etRthAlt);
        Integer batt = readInt(etLowBatt);

        List<String> problems = new ArrayList<>();
        if (alt != null && (alt < ALT_MIN || alt > ALT_MAX)) problems.add("Max altitude must be " + rangeText(ALT_MIN, ALT_MAX));
        if (swDistLimit.isChecked() && dist != null && (dist < DIST_MIN || dist > DIST_MAX)) problems.add("Max distance must be " + rangeText(DIST_MIN, DIST_MAX));
        if (rth != null && (rth < RTH_MIN || rth > RTH_MAX)) problems.add("Return-to-home altitude must be " + rangeText(RTH_MIN, RTH_MAX));
        if (rth != null && alt != null && rth > alt) problems.add("Return-to-home altitude can't be above max altitude");
        if (batt != null && (batt < BATT_MIN || batt > BATT_MAX)) problems.add("Low battery warning must be " + BATT_MIN + "–" + BATT_MAX + "%");
        if (!problems.isEmpty()) {
            saveResult.setText(TextUtils.join("\n", problems));
            return;
        }

        saveResult.setText("Saving…");
        List<String> failures = new ArrayList<>();
        AtomicInteger pending = new AtomicInteger(1); // released after all calls are queued

        Runnable done = () -> runOnUiThread(() -> {
            if (failures.isEmpty()) {
                saveResult.setText("Saved to drone ✓");
            } else {
                saveResult.setText("Some settings were not saved:\n" + TextUtils.join("\n", failures));
            }
            loadFromDrone();
        });

        Consumer<CallbackSetter> run = setter -> {
            pending.incrementAndGet();
            setter.call(error -> {
                if (error != null) {
                    synchronized (failures) {
                        failures.add(setter.name + ": " + error.getDescription());
                    }
                }
                if (pending.decrementAndGet() == 0) done.run();
            });
        };

        run.accept(new CallbackSetter("Beginner mode", cb -> fc.setNoviceModeEnabled(swBeginner.isChecked(), cb)));
        if (alt != null) run.accept(new CallbackSetter("Max altitude", cb -> fc.setMaxFlightHeight(alt, cb)));
        run.accept(new CallbackSetter("Distance limit", cb -> fc.setMaxFlightRadiusLimitationEnabled(swDistLimit.isChecked(), cb)));
        if (swDistLimit.isChecked() && dist != null) run.accept(new CallbackSetter("Max distance", cb -> fc.setMaxFlightRadius(dist, cb)));
        if (rth != null) run.accept(new CallbackSetter("Return-to-home altitude", cb -> fc.setGoHomeHeightInMeters(rth, cb)));
        if (batt != null) run.accept(new CallbackSetter("Low battery warning", cb -> fc.setLowBatteryWarningThreshold(batt, cb)));

        if (pending.decrementAndGet() == 0) done.run();
    }

    /** A named drone setting write, so failures can say which one went wrong. */
    private static final class CallbackSetter {
        final String name;
        final Consumer<CommonCallbacks.CompletionCallback<DJIError>> action;

        CallbackSetter(String name, Consumer<CommonCallbacks.CompletionCallback<DJIError>> action) {
            this.name = name;
            this.action = action;
        }

        void call(CommonCallbacks.CompletionCallback<DJIError> cb) {
            action.accept(cb);
        }
    }
}
