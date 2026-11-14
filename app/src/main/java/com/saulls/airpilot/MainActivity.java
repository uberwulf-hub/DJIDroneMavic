package com.saulls.airpilot;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Start screen: asks for permissions, registers with DJI, waits for the drone,
 * then opens the fly screen automatically.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSION_CODE = 12345;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.VIBRATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
    };

    private static boolean isAppStarted = false;

    private final AtomicBoolean registrationInProgress = new AtomicBoolean(false);
    private final List<String> missingPermissions = new ArrayList<>();

    private TextView sdkStatus;
    private TextView productStatus;
    private Button flyButton;
    private boolean registered = false;
    private boolean autoOpenedFly = false;
    private boolean resumed = false;

    public static boolean isStarted() {
        return isAppStarted;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        isAppStarted = true;

        sdkStatus = findViewById(R.id.status_sdk);
        productStatus = findViewById(R.id.status_product);
        flyButton = findViewById(R.id.btn_fly);
        TextView version = findViewById(R.id.version);

        String sdkVersion = DJISDKManager.getInstance().getSDKVersion();
        version.setText("Air Pilot " + BuildConfig.VERSION_NAME + "  ·  DJI SDK " + sdkVersion);

        // Always allowed, so you can look at the fly screen without a drone.
        flyButton.setOnClickListener(v -> openFlyScreen());
        findViewById(R.id.btn_log).setOnClickListener(v -> startActivity(new Intent(this, FlightLogActivity.class)));
        findViewById(R.id.btn_settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        if (!hasApiKey()) {
            sdkStatus.setText("No DJI App Key in this build.\nAdd the DJI_API_KEY secret on GitHub and rebuild.");
            productStatus.setText("");
            return;
        }

        sdkStatus.setText("Starting…");
        productStatus.setText("Plug the phone into the controller, then power on the controller and drone.");
        checkAndRequestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshProductStatus();
        refreshSummary();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        DJISDKManager.getInstance().destroy();
        isAppStarted = false;
        super.onDestroy();
    }

    private void refreshSummary() {
        ((TextView) findViewById(R.id.mode_hint)).setText(Prefs.isAdvanced(this)
                ? "Opens in Pro mode" : "Opens in Simple mode");
        List<FlightRecorder.Flight> flights = FlightRecorder.load(this);
        TextView last = findViewById(R.id.last_flight);
        if (flights.isEmpty()) {
            last.setText("");
        } else {
            FlightRecorder.Flight f = flights.get(0);
            boolean imperial = Prefs.isImperial(this);
            last.setText("Last flight: " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(f.startMillis))
                    + "  ·  " + Units.duration(f.durationS)
                    + "  ·  " + Units.distance(f.maxDistanceM, imperial) + " away");
        }
    }

    private boolean hasApiKey() {
        try {
            ApplicationInfo info = getPackageManager()
                    .getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            String key = info.metaData == null ? null : info.metaData.getString("com.dji.sdk.API_KEY");
            return key != null && !key.isEmpty() && !key.startsWith("MISSING");
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void checkAndRequestPermissions() {
        missingPermissions.clear();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        if (missingPermissions.isEmpty()) {
            startSDKRegistration();
        } else {
            ActivityCompat.requestPermissions(this,
                    missingPermissions.toArray(new String[0]), REQUEST_PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_CODE) {
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    missingPermissions.remove(permissions[i]);
                }
            }
        }
        // Location is the one that really matters to DJI; try to register regardless
        // and let the SDK report anything it cannot do.
        startSDKRegistration();
    }

    private void startSDKRegistration() {
        if (!registrationInProgress.compareAndSet(false, true)) return;
        setSdkStatus("Registering with DJI… (needs internet the first time)");
        AsyncTask.execute(() -> DJISDKManager.getInstance().registerApp(getApplicationContext(), callback));
    }

    private final DJISDKManager.SDKManagerCallback callback = new DJISDKManager.SDKManagerCallback() {
        @Override
        public void onRegister(DJIError error) {
            registrationInProgress.set(false);
            if (error == DJISDKError.REGISTRATION_SUCCESS) {
                registered = true;
                setSdkStatus("DJI registration OK");
                DJISDKManager.getInstance().startConnectionToProduct();
            } else {
                setSdkStatus("DJI registration failed: "
                        + (error == null ? "unknown error" : error.getDescription())
                        + "\nCheck internet and that the App Key matches com.saulls.airpilot.");
            }
            refreshProductStatus();
        }

        @Override
        public void onProductDisconnect() {
            autoOpenedFly = false;
            refreshProductStatus();
        }

        @Override
        public void onProductConnect(BaseProduct product) {
            refreshProductStatus();
        }

        @Override
        public void onProductChanged(BaseProduct product) {
            refreshProductStatus();
        }

        @Override
        public void onComponentChange(BaseProduct.ComponentKey key, BaseComponent oldComponent,
                                      BaseComponent newComponent) {
            if (newComponent != null) {
                newComponent.setComponentListener(isConnected -> refreshProductStatus());
            }
            refreshProductStatus();
        }

        @Override
        public void onInitProcess(DJISDKInitEvent event, int totalProcess) {
        }

        @Override
        public void onDatabaseDownloadProgress(long current, long total) {
            if (total > 0) {
                setSdkStatus("Downloading fly-safe database… " + (int) (100 * current / total) + "%");
            }
        }
    };

    private void setSdkStatus(String text) {
        runOnUiThread(() -> sdkStatus.setText(text));
    }

    private void refreshProductStatus() {
        runOnUiThread(() -> {
            if (!registered) return;
            BaseProduct product = DJISDKManager.getInstance().getProduct();
            if (product != null && product.isConnected()) {
                String name = product.getModel() == null ? "DJI aircraft" : product.getModel().getDisplayName();
                productStatus.setText("Connected: " + name);
                Telemetry.get(MainActivity.this).attach();
                // Jump straight to the fly screen the first time the drone shows up.
                if (!autoOpenedFly && resumed && product.getModel() != null) {
                    autoOpenedFly = true;
                    openFlyScreen();
                }
            } else if (product != null) {
                productStatus.setText("Controller connected. Waiting for the drone…");
            } else {
                productStatus.setText("Waiting for controller… plug the phone into it and power it on.");
            }
        });
    }

    private void openFlyScreen() {
        startActivity(new Intent(this, FlyActivity.class));
    }
}
