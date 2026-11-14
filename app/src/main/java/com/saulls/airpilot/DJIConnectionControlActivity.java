package com.saulls.airpilot;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.View;

/** Launched by Android when the DJI remote controller is plugged in over USB. */
public class DJIConnectionControlActivity extends Activity {

    public static final String ACCESSORY_ATTACHED = "com.saulls.airpilot.ACCESSORY_ATTACHED";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new View(this));

        Intent usbIntent = getIntent();
        if (usbIntent != null && UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(usbIntent.getAction())) {
            Intent attached = new Intent(ACCESSORY_ATTACHED);
            attached.setPackage(getPackageName());
            sendBroadcast(attached);
        }
        finish();
    }
}
