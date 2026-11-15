package com.saulls.airpilot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.InputStream;

/** Receives plans from the planner (airpilot:// links, QR codes) or plan files (Drive, Files, email). */
public class ImportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(in.getAction())) uri = in.getParcelableExtra(Intent.EXTRA_STREAM);
        else uri = in.getData();
        try {
            Mission m;
            if (uri == null) throw new IllegalArgumentException("Nothing to import");
            if ("airpilot".equals(uri.getScheme())) {
                String d = uri.getQueryParameter("d");
                if (d == null) throw new IllegalArgumentException("The link has no plan in it");
                m = Mission.fromLinkPayload(d);
            } else if ("https".equals(uri.getScheme()) && uri.getFragment() != null) {
                m = Mission.fromLinkPayload(uri.getFragment());
            } else {
                try (InputStream s = getContentResolver().openInputStream(uri)) {
                    m = Mission.fromStream(s);
                }
            }
            m.save(this);
            Toast.makeText(this, "Imported \"" + m.name + "\"", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, MissionsActivity.class)
                    .putExtra(MissionsActivity.EXTRA_SHOW, m.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't import that plan: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
