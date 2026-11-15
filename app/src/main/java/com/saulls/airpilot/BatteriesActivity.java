package com.saulls.airpilot;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Each battery the drone has used: health, cycles, flights and heat. Tap one to rename it. */
public class BatteriesActivity extends Activity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = DensityUtil.dip2px(this, 24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(R.color.background_blue));
        root.setPadding(pad, 0, pad, 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(DensityUtil.dip2px(this, 56));
        Button back = new Button(this);
        back.setText("‹ Back");
        back.setAllCaps(false);
        back.setTextColor(Color.WHITE);
        back.setTextSize(16);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("Batteries");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(title);
        root.addView(bar);

        TextView hint = new TextView(this);
        hint.setText("Each battery is recognised by its serial number the first time it's in the drone. Tap one to rename it.");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(13);
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        List<BatteryBook.Pack> packs = BatteryBook.list(this);
        if (packs.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No batteries yet. Connect the drone and they'll show up here.");
            t.setTextColor(0xCCFFFFFF);
            t.setTextSize(15);
            t.setPadding(0, DensityUtil.dip2px(this, 24), 0, 0);
            list.addView(t);
            return;
        }
        Telemetry.Snapshot now = Telemetry.get(this).last();
        DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
        for (BatteryBook.Pack p : packs) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            int cp = DensityUtil.dip2px(this, 14);
            card.setPadding(cp, cp, cp, cp);
            card.setBackgroundResource(R.drawable.hud_bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = DensityUtil.dip2px(this, 12);

            int h = p.healthPct();
            String status;
            int color;
            if (h >= 0 && h < 70) {
                status = "Worn — consider replacing";
                color = 0xFFFF5252;
            } else if ((h >= 0 && h < 85) || (!Double.isNaN(p.maxTempC) && p.maxTempC >= 65)) {
                status = h >= 0 && h < 85 ? "Getting older — expect shorter flights" : "Has run hot — avoid long Sport flights";
                color = 0xFFFFB74D;
            } else {
                status = "Good";
                color = 0xFF9FE3B0;
            }
            boolean inDrone = p.serial.equals(now.batterySerial);

            TextView name = new TextView(this);
            name.setText(p.name + (inDrone ? "   ● in the drone" : ""));
            name.setTextColor(Color.WHITE);
            name.setTextSize(18);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(name);

            TextView st = new TextView(this);
            st.setText(status);
            st.setTextColor(color);
            st.setTextSize(14);
            card.addView(st);

            StringBuilder d = new StringBuilder();
            d.append(h >= 0 ? "Health " + h + "%" : "Health unknown");
            if (p.fullMah > 0 && p.designMah > 0) d.append(" (").append(p.fullMah).append(" of ").append(p.designMah).append(" mAh)");
            d.append("\nCharge cycles: ").append(p.cycles >= 0 ? String.valueOf(p.cycles) : "unknown");
            d.append("\nFlights with Air Pilot: ").append(p.flights).append(" · ").append(Units.duration(p.flightSeconds)).append(" total");
            if (!Double.isNaN(p.lastFlightMaxTempC)) d.append("\nLast flight: up to ").append(Math.round(p.lastFlightMaxTempC)).append("°C");
            if (p.lastFlightUsedPct >= 0) d.append(", used ").append(p.lastFlightUsedPct).append('%');
            if (!Double.isNaN(p.maxTempC)) d.append("\nHottest ever: ").append(Math.round(p.maxTempC)).append("°C");
            d.append("\nFirst seen ").append(df.format(new Date(p.firstSeen)))
                    .append(" · last used ").append(df.format(new Date(p.lastSeen)));
            d.append("\nSerial ").append(p.serial);
            TextView details = new TextView(this);
            details.setText(d);
            details.setTextColor(0xDDFFFFFF);
            details.setTextSize(14);
            details.setLineSpacing(DensityUtil.dip2px(this, 3), 1f);
            details.setPadding(0, DensityUtil.dip2px(this, 6), 0, 0);
            card.addView(details);

            card.setOnClickListener(v -> rename(p));
            list.addView(card, lp);
        }
        View spacer = new View(this);
        list.addView(spacer, new LinearLayout.LayoutParams(1, DensityUtil.dip2px(this, 24)));
    }

    private void rename(BatteryBook.Pack p) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setText(p.name);
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Name this battery")
                .setMessage("Tip: write the same name on the battery with a marker.")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    BatteryBook.rename(this, p.serial, et.getText().toString());
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
