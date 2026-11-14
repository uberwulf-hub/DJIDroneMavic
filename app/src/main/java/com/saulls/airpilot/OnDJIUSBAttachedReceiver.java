package com.saulls.airpilot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import dji.sdk.sdkmanager.DJISDKManager;

/** Opens the app (or tells the running SDK) when the controller is attached. */
public class OnDJIUSBAttachedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!MainActivity.isStarted()) {
            Intent start = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (start == null) return;
            start.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            start.addCategory(Intent.CATEGORY_LAUNCHER);
            context.startActivity(start);
        } else {
            Intent attached = new Intent(DJISDKManager.USB_ACCESSORY_ATTACHED);
            context.sendBroadcast(attached);
        }
    }
}
