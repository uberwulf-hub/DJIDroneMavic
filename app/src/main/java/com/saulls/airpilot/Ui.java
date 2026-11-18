package com.saulls.airpilot;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

/**
 * Shared dark palette and small drawable helpers for screens that build their UI in code.
 * Colours mirror res/values/colors.xml so XML and code screens look the same.
 */
final class Ui {

    private Ui() {
    }

    static final int BG = 0xFF0B0F14;
    static final int SURFACE = 0xFF141A22;
    static final int SURFACE_2 = 0xFF1B2330;
    static final int BORDER = 0xFF26303D;
    static final int ACCENT = 0xFF3DA9FC;
    static final int ACCENT_TINT = 0x263DA9FC;
    static final int ON_ACCENT = 0xFF0B0F14;
    static final int SUCCESS = 0xFF4ADE80;
    static final int WARN = 0xFFFBBF24;
    static final int DANGER = 0xFFF87171;
    static final int TEXT = 0xFFE6EDF3;
    static final int TEXT_2 = 0xFF8B98A5;
    static final int TEXT_MUTED = 0xFF5F6B78;
    static final int RIPPLE = 0x1FFFFFFF;

    static int dp(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    /** Rounded rectangle with an optional 1dp border (pass 0 for none). */
    static GradientDrawable rounded(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(c, 1)), stroke);
        return d;
    }

    /** Surface card: dark fill, subtle border, 16dp corners. */
    static Drawable card(Context c) {
        return rounded(c, SURFACE, BORDER, 16);
    }

    /** Tappable card / list row with a ripple. */
    static Drawable cardButton(Context c, boolean selected) {
        GradientDrawable base = selected
                ? rounded(c, SURFACE_2, ACCENT, 14)
                : rounded(c, SURFACE, BORDER, 14);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), base, null);
    }

    /** Full-radius pill, e.g. a status chip. */
    static Drawable pill(Context c, int fill, int stroke) {
        return rounded(c, fill, stroke, 999);
    }
}
