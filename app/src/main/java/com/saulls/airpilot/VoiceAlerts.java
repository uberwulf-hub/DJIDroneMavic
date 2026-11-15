package com.saulls.airpilot;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Spoken callouts so the pilot can keep their eyes on the drone. */
public final class VoiceAlerts {

    private static VoiceAlerts instance;

    public static synchronized VoiceAlerts get(Context c) {
        if (instance == null) instance = new VoiceAlerts(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private TextToSpeech tts;
    private boolean ready;
    private final Map<String, Long> lastSaid = new HashMap<>();
    private Telemetry.Snapshot prev;
    private int lastBatteryCallout = 101;
    private boolean headHomeSaid;

    private VoiceAlerts(Context c) {
        context = c;
    }

    private final java.util.Random random = new java.util.Random();

    /** "casual" (default, a bit of humour) or "standard" (plain). */
    public static boolean casual(Context c) {
        return !"standard".equals(Prefs.raw(c).getString("voice_style", "casual"));
    }

    public static void setCasual(Context c, boolean on) {
        Prefs.raw(c).edit().putString("voice_style", on ? "casual" : "standard").apply();
    }

    /** Standard wording, or one of the casual lines at random. */
    public String line(String standard, String... casualLines) {
        if (!casual(context) || casualLines.length == 0) return standard;
        return casualLines[random.nextInt(casualLines.length)];
    }

    public static boolean enabled(Context c) {
        return Prefs.raw(c).getBoolean("voice_alerts", true);
    }

    public static void setEnabled(Context c, boolean on) {
        Prefs.raw(c).edit().putBoolean("voice_alerts", on).apply();
    }

    private final java.util.List<Runnable> whenReady = new java.util.ArrayList<>();

    private void ensureTts() {
        if (tts != null) return;
        tts = new TextToSpeech(context, status -> {
            ready = status == TextToSpeech.SUCCESS;
            if (ready) {
                applyVoice();
                for (Runnable r : whenReady) r.run();
            }
            whenReady.clear();
        });
    }

    /** Runs once the speech engine is ready. */
    public void onReady(Runnable r) {
        ensureTts();
        if (ready) r.run();
        else whenReady.add(r);
    }

    public static String voiceName(Context c) {
        return Prefs.raw(c).getString("voice_name", "");
    }

    /** Pick a voice by name ("" = the phone's default). */
    public void setVoice(String name) {
        Prefs.raw(context).edit().putString("voice_name", name == null ? "" : name).apply();
        if (ready) applyVoice();
    }

    private void applyVoice() {
        String name = voiceName(context);
        if (!name.isEmpty()) {
            try {
                for (android.speech.tts.Voice v : tts.getVoices()) {
                    if (v.getName().equals(name)) {
                        tts.setVoice(v);
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        tts.setLanguage(Locale.getDefault());
    }

    /** English voices on this phone, best first: Australian, then British, US, others; offline before online. */
    public java.util.List<android.speech.tts.Voice> englishVoices() {
        java.util.List<android.speech.tts.Voice> out = new java.util.ArrayList<>();
        if (!ready) return out;
        try {
            for (android.speech.tts.Voice v : tts.getVoices()) {
                if (v.getLocale() != null && "en".equals(v.getLocale().getLanguage())
                        && (v.getFeatures() == null || !v.getFeatures().contains("notInstalled"))) out.add(v);
            }
        } catch (Exception ignored) {
        }
        java.util.Collections.sort(out, (a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return ra - rb;
            return a.getName().compareTo(b.getName());
        });
        return out;
    }

    private static int rank(android.speech.tts.Voice v) {
        String c = v.getLocale().getCountry();
        int r = "AU".equals(c) ? 0 : "GB".equals(c) ? 10 : "US".equals(c) ? 20 : 30;
        return r + (v.isNetworkConnectionRequired() ? 5 : 0);
    }

    /** Human label, e.g. "English (Australia) · voice 3 · offline". */
    public static String label(android.speech.tts.Voice v, int index) {
        return v.getLocale().getDisplayName() + " \u00B7 voice " + index
                + (v.isNetworkConnectionRequired() ? " \u00B7 needs internet" : " \u00B7 offline");
    }

    /** Speak a sample with a specific voice (without saving it). */
    public void preview(android.speech.tts.Voice v) {
        if (!ready) return;
        tts.stop();
        tts.setVoice(v);
        tts.speak("Battery 30 percent. Time to head home.", TextToSpeech.QUEUE_FLUSH, null, "preview");
    }

    /** Say something, at most once every {@code minGapMs} for the same key. */
    public void say(String key, String text, long minGapMs) {
        if (!enabled(context)) return;
        long now = System.currentTimeMillis();
        Long last = lastSaid.get(key);
        if (last != null && now - last < minGapMs) return;
        lastSaid.put(key, now);
        ensureTts();
        if (ready) tts.speak(text, TextToSpeech.QUEUE_ADD, null, key + now);
    }

    void onTelemetry(Telemetry.Snapshot s) {
        if (!enabled(context)) {
            prev = s;
            return;
        }
        ensureTts();
        Telemetry.Snapshot p = prev;
        prev = s;
        if (p == null) return;

        if (Double.isNaN(p.homeLat) && !Double.isNaN(s.homeLat)) say("home", line("Home point recorded.",
                "Home point locked in. I know where we live now.",
                "Got the home point. If things get weird, I know the way back.",
                "Home point saved. Let's go have some fun."), 60_000);
        if (!p.flying && s.flying) {
            lastBatteryCallout = 101;
            headHomeSaid = false;
        }
        if (p.flying && !s.flying) {
            String t = spokenDuration(s.flightTimeS);
            say("landed", line("Landed. Flight time " + t + ".",
                    "Touchdown! " + t + " in the air. Nice flying.",
                    "And we're down. " + t + " of flight. Smooth.",
                    "Landed safe and sound after " + t + ". The ground missed me."), 10_000);
        }
        if (s.flying) {
            if (s.batteryPct > 0) {
                for (int level : new int[]{50, 30, 20, 15, 10}) {
                    if (s.batteryPct <= level && lastBatteryCallout > level) {
                        lastBatteryCallout = level;
                        say("batt", batteryLine(s.batteryPct), 5_000);
                        break;
                    }
                }
            }
            if (!headHomeSaid && s.returnBatteryNeeded > 0 && s.batteryPct > 0 && !s.goingHome
                    && s.batteryPct - s.returnBatteryNeeded <= 8 && s.distanceM > 30) {
                headHomeSaid = true;
                String t = spokenDuration(s.returnSeconds);
                say("headhome", line("Time to head home. About " + t + " to get back.",
                        "Heads up, it's about " + t + " back to base. Now's a good time to turn around.",
                        "Time to head home. It's about " + t + " back, and I'd like to make it.",
                        "Just saying, home is about " + t + " away and my battery agrees we should go."), 60_000);
            }
            if (!p.lowBattery && s.lowBattery) say("low", line("Low battery. Return home soon.",
                    "Low battery. I'm getting hangry. Bring me home soon.",
                    "Low battery warning. Snack time is coming up fast.",
                    "Battery's low. Let's wrap this up and head home."), 30_000);
            if (!p.criticalBattery && s.criticalBattery) say("crit", line("Critical battery. The drone will land.",
                    "Critical battery! I'm landing now. Steer me somewhere nice.",
                    "Critical battery. Gravity is winning this one. Landing now, pick a soft spot.",
                    "Critical battery! Coming down now. Please, not in the pool."), 30_000);
            if (!p.goingHome && s.goingHome) say("rth", line("Returning home.",
                    "Heading home. Put the kettle on.",
                    "Returning home. Don't wait up.",
                    "Going home. Hands off the sticks, I've got this."), 15_000);
            if (!p.compassError && s.compassError) say("compass", line("Compass interference. Fly carefully.",
                    "Compass interference. Something metal is messing with my sense of direction. Fly gently.",
                    "My compass is confused. Easy on the sticks for a bit."), 30_000);
            if (p.gpsLevel >= 2 && s.gpsLevel >= 0 && s.gpsLevel <= 1) say("gps", line("Weak GPS. The drone may drift.",
                    "GPS is weak. I might wander a bit, keep your thumbs ready.",
                    "Losing GPS. I may drift with the wind, so stay on the sticks."), 30_000);
            if (!p.maxHeightReached && s.maxHeightReached) say("maxh", line("Maximum altitude reached.",
                    "That's as high as I'm allowed to go. Rules are rules.",
                    "Max altitude. The sky is not the limit, this is."), 30_000);
            if (!p.maxRadiusReached && s.maxRadiusReached) say("maxr", line("Maximum distance reached.",
                    "That's the end of my leash. Can't go any farther.",
                    "Max distance reached. I'm not allowed past this point."), 30_000);
        }
    }

    private String batteryLine(int pct) {
        String std = "Battery " + pct + " percent.";
        if (pct > 40) return line(std,
                "Battery's at " + pct + " percent. Half a tank, plenty of fun left.",
                pct + " percent battery. We're halfway through the snacks.");
        if (pct > 25) return line(std,
                "Battery's at " + pct + " percent. Start thinking about the scenic route home.",
                pct + " percent left. Maybe one more cool shot, then home.");
        if (pct > 17) return line(std,
                pct + " percent. This is me politely suggesting we head home.",
                pct + " percent battery. Not panicking, but I'm eyeing the exit.");
        return line(std,
                pct + " percent. Okay, now I'm actually asking. Home, please.",
                pct + " percent. I'd really like to land somewhere that isn't a tree.");
    }

    private static String spokenDuration(long seconds) {
        if (seconds < 60) return seconds + " seconds";
        long m = seconds / 60, s = seconds % 60;
        return m + (m == 1 ? " minute" : " minutes") + (s >= 10 ? " " + s + " seconds" : "");
    }
}
