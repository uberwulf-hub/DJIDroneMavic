package com.saulls.airpilot;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;

import com.secneo.sdk.Helper;

public class AirPilotApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Required by the DJI SDK before anything else touches it.
        Helper.install(AirPilotApp.this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashReporter.install(this);
        BroadcastReceiver receiver = new OnDJIUSBAttachedReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(DJIConnectionControlActivity.ACCESSORY_ATTACHED);
        registerReceiver(receiver, filter);
    }
}
