package com.saulls.airpilot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/** Simple line chart of one value over the flight (altitude, speed, battery). */
public class TrackChart extends View {

    private float[] times = new float[0];
    private float[] values = new float[0];
    private String label = "";
    private String unit = "";
    private int color = Color.parseColor("#4FC3F7");

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TrackChart(Context c, AttributeSet a) {
        super(c, a);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(2));
        grid.setColor(Color.parseColor("#33FFFFFF"));
        grid.setStrokeWidth(dp(1));
        text.setColor(Color.parseColor("#CCFFFFFF"));
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
    }

    public void setData(String label, String unit, int color, float[] times, float[] values) {
        this.label = label;
        this.unit = unit;
        this.color = color;
        this.times = times;
        this.values = values;
        invalidate();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float top = dp(20), bottom = h - dp(4), left = dp(4), right = w - dp(4);
        c.drawLine(left, bottom, right, bottom, grid);
        c.drawLine(left, top, right, top, grid);

        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : values) {
            if (Float.isNaN(v)) continue;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        String title = label;
        if (max >= min) title += String.format(java.util.Locale.US, "   %.0f – %.0f %s", min, max, unit);
        c.drawText(title, left, dp(14), text);
        if (values.length < 2 || max < min) return;
        if (max - min < 1e-3f) {
            max += 1;
            min -= 1;
        }
        float t0 = times[0], t1 = times[times.length - 1];
        if (t1 <= t0) t1 = t0 + 1;

        Path p = new Path();
        boolean started = false;
        for (int i = 0; i < values.length; i++) {
            if (Float.isNaN(values[i])) {
                started = false;
                continue;
            }
            float x = left + (times[i] - t0) / (t1 - t0) * (right - left);
            float y = bottom - (values[i] - min) / (max - min) * (bottom - top);
            if (!started) {
                p.moveTo(x, y);
                started = true;
            } else {
                p.lineTo(x, y);
            }
        }
        line.setColor(color);
        c.drawPath(p, line);
    }
}
