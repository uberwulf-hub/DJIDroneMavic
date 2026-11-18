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
    private boolean altWarned, farWarned, hotWarned, speedSaid, tenMinSaid;
    private long lastTelemetryMs;
    private boolean linkLostSaid;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean watchdogOn;

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
        lastTelemetryMs = System.currentTimeMillis();
        if (linkLostSaid) {
            linkLostSaid = false;
            say("linkback", line("Signal restored.",
                    "And we're back! Sorry, I went through a tunnel.",
                    "Signal's back. Did you miss me?",
                    "Reconnected. Nobody panic, I was fine the whole time."), 10_000);
        }
        startWatchdog();
        if (p == null) return;

        if (Double.isNaN(p.homeLat) && !Double.isNaN(s.homeLat)) say("home", line("Home point recorded.",
                "Home point locked in. I know where we live now.",
                "Got the home point. If things get weird, I know the way back.",
                "Home point saved. Let's go have some fun."), 60_000);
        if (!p.flying && s.flying) {
            lastBatteryCallout = 101;
            headHomeSaid = false;
            altWarned = farWarned = hotWarned = speedSaid = tenMinSaid = false;
            say("takeoff", casual(context) ? Captain.takeoff(s, Prefs.isImperial(context), random) : "Taking off.", 10_000);
            preflightWorries(s);
        }
        if (p.flying && !s.flying) {
            String t = spokenDuration(s.flightTimeS);
            say("landed", line("Landed. Flight time " + t + ".",
                    "Touchdown! " + t + " in the air. Nice flying.",
                    "And we're down. " + t + " of flight. Smooth.",
                    "Landed safe and sound after " + t + ". The ground missed me.",
                    Captain.landing(t, random)), 10_000);
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
            moreCallouts(p, s);
            if (!p.maxRadiusReached && s.maxRadiusReached) say("maxr", line("Maximum distance reached.",
                    "That's the end of my leash. Can't go any farther.",
                    "Max distance reached. I'm not allowed past this point."), 30_000);
        }
    }

    /** Obstacles, altitude, airspace, wind, distance and other "your pilot speaking" moments. */
    private void moreCallouts(Telemetry.Snapshot p, Telemetry.Snapshot s) {
        boolean imperial = Prefs.isImperial(context);
        // obstacles
        if (!p.braking && s.braking) FlightRecorder.event(context, "Obstacle braking" + (s.obstacleSide.isEmpty() ? "" : " (" + s.obstacleSide + ")"));
        if (!p.avoiding && s.avoiding) FlightRecorder.event(context, "Avoiding an obstacle");
        if (s.flyZone > p.flyZone) FlightRecorder.event(context, "Airspace: " + (s.flyZone == 1 ? "near a restricted zone" : s.flyZone == 2 ? "in a warning zone" : "in a restricted zone"));
        if (s.windLevel > p.windLevel) FlightRecorder.event(context, s.windLevel == 1 ? "Strong wind warning" : "Very strong wind warning");
        if (!p.braking && s.braking) {
            String where = s.obstacleSide.isEmpty() ? "" : " " + s.obstacleSide;
            say("obst", line("Obstacle" + where + ". Braking.",
                    "Whoa, brakes! Something" + where + " really wants to meet me.",
                    "Hit the brakes! There's something" + where + ". I'd rather not hug it.",
                    "Emergency stop. Obstacle" + where + ". I saw my life flash before my propellers.",
                    "Stopping! Something" + where + " is way too close for comfort."), 6_000);
        } else if (!p.avoiding && s.avoiding) {
            say("obst", line("Avoiding an obstacle.",
                    "Dodging something. Smooth moves, if I say so myself.",
                    "Swerving around an obstacle. Nobody tell the insurance company.",
                    "Obstacle avoided. Matrix style."), 6_000);
        } else if (!p.obstacleClose && s.obstacleClose && s.horizontalSpeedMps > 0.5 && s.obstacleM < 8) {
            String d = spokenDist(s.obstacleM, imperial);
            say("obst", line("Obstacle " + s.obstacleSide + ", " + d + ".",
                    "Heads up, something " + s.obstacleSide + ", about " + d + " away. Easy does it.",
                    "Object " + s.obstacleSide + ", " + d + ". I'm good at many things, but going through walls isn't one.",
                    "Something " + s.obstacleSide + ", " + d + " out. Let's not test the warranty."), 10_000);
        }
        if (!p.ascentLimited && s.ascentLimited) say("above", line("Obstacle above. Can't climb.",
                "Something's above me, so I can't go up. Low ceiling, high standards.",
                "Can't climb, there's something overhead. Probably a tree with ambitions."), 15_000);

        // getting close to the altitude ceiling (the drone's setting, or 400 feet / 120 metres)
        double ceiling = 120;
        if (s.maxHeightM > 0) ceiling = Math.min(ceiling, s.maxHeightM);
        if (!Double.isNaN(s.altitudeM)) {
            if (!altWarned && s.altitudeM > ceiling - 10 && s.verticalSpeedMps > 0.3) {
                altWarned = true;
                String c = spokenAlt(ceiling, imperial);
                say("ceiling", line("Approaching the " + c + " height limit.",
                        "Getting close to the " + c + " ceiling. Any higher and the airplanes start asking questions.",
                        "Almost at " + c + ". That's the legal limit, and I'm a law-abiding aircraft.",
                        "Nearing " + c + ". Up here the birds need a permit.",
                        "We're near the " + c + " ceiling. This is your captain reminding you that the sky does have a lid."), 30_000);
            } else if (s.altitudeM < ceiling - 20) {
                altWarned = false;
            }
        }

        // airspace
        if (s.flyZone > p.flyZone) {
            if (s.flyZone == 1) say("zone", line("Restricted airspace nearby.",
                    "Heads up, restricted airspace nearby. Probably an airport. Let's not wave at the jets.",
                    "There's a no-fly zone close by. I'll stay on the right side of the fence."), 60_000);
            else if (s.flyZone == 2) say("zone", line("Entering a warning zone. Check local rules.",
                    "We're in a warning zone. Fly nice, there may be real aircraft about.",
                    "Warning zone. Keep it low and keep your eyes peeled for full-size planes."), 60_000);
            else if (s.flyZone == 3) say("zone", line("Restricted zone. The drone may not be able to fly here.",
                    "We're in a restricted zone. Air traffic control would not approve. Let's back out.",
                    "Restricted airspace! Turn around before somebody important notices."), 60_000);
        }

        // wind
        if (s.windLevel > p.windLevel) {
            if (s.windLevel == 1) say("wind", line("Strong wind. Fly carefully.",
                    "It's getting breezy up here. Hold onto your hat.",
                    "Strong wind. I'm working out, but keep me closer to home."), 60_000);
            else say("wind", line("Very strong wind. Return home now.",
                    "Very strong wind! I'm basically a kite now. Bring me home.",
                    "This wind is no joke. Let's head home before I end up in the next town."), 30_000);
        }

        // landing near the ground
        if (!p.landingConfirmNeeded && s.landingConfirmNeeded) say("landconf", line("Close to the ground. Hold the stick down to land.",
                "Ground's right there. Hold the left stick down if you really want me to land.",
                "I'm hovering just above the ground. Keep the stick down to touch down."), 15_000);

        // visual line of sight
        if (!Double.isNaN(s.distanceM)) {
            if (!farWarned && s.distanceM > 450) {
                farWarned = true;
                say("far", line("The drone is " + spokenDist(s.distanceM, imperial) + " away. Keep it in sight.",
                        "I'm " + spokenDist(s.distanceM, imperial) + " out. Can you still see me? Wave if you can.",
                        "Long way out, " + spokenDist(s.distanceM, imperial) + ". Make sure you can still spot me."), 60_000);
            } else if (s.distanceM < 350) {
                farWarned = false;
            }
        }

        // battery temperature
        if (!hotWarned && !Double.isNaN(s.batteryTempC) && s.batteryTempC >= 62) {
            hotWarned = true;
            say("hot", line("Battery temperature is high.",
                    "My battery is running hot. Like, sidewalk-egg hot. Let's not push it.",
                    "Battery's toasty. Easy on the throttle and maybe call it soon."), 120_000);
        }

        // fun stuff
        if (!speedSaid && s.horizontalSpeedMps > 15) {
            speedSaid = true;
            say("speed", line("High speed.",
                    "Wheee! " + spokenSpeed(s.horizontalSpeedMps, imperial) + ". Is this a speed camera zone?",
                    "Now we're moving. Please keep your arms and legs inside the drone.",
                    "Going fast! I feel the need, the need for speed."), 120_000);
        }
        if (!tenMinSaid && s.flightTimeS >= 600) {
            tenMinSaid = true;
            say("ten", line("Ten minutes of flight.",
                    "Ten minutes in the air. Your captain thanks you for flying Air Pilot.",
                    "We've been up for ten minutes. Refreshments are not being served."), 120_000);
        }
        if (s.sdFull && !p.sdFull) say("sdfull", line("SD card full.",
                "The SD card is full. I can't take any more pictures, I'm stuffed.",
                "Memory card's full. No more photos until you make some room."), 60_000);
    }

    /** Spoken once at takeoff when something isn't ideal. */
    private void preflightWorries(Telemetry.Snapshot s) {
        if (!s.sdInserted) say("nosd", line("No SD card. Photos and videos won't be saved.",
                "Quick thing, there's no SD card. Anything we film is just for memories.",
                "No SD card in me. Great views, zero souvenirs."), 600_000);
        if (!Double.isNaN(s.batteryTempC) && s.batteryTempC > 0 && s.batteryTempC < 15) say("cold", line("Battery is cold. Hover for a minute to warm it up.",
                "Brr, the battery is cold. Let's hover for a minute while it warms up.",
                "Battery's chilly. Give it a minute of hovering before we go wild."), 600_000);
        if (s.gpsLevel >= 0 && s.gpsLevel <= 2) say("gpsweak", line("GPS is weak. The drone may drift.",
                "GPS is a bit weak. I may drift, so keep your thumbs ready."), 600_000);
    }

    /** If the drone stops talking for a few seconds mid-flight, say so. */
    private void startWatchdog() {
        if (watchdogOn) return;
        watchdogOn = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Telemetry.Snapshot s = prev;
                if (s != null && s.flying && !linkLostSaid && System.currentTimeMillis() - lastTelemetryMs > 4_000) {
                    linkLostSaid = true;
                    FlightRecorder.event(context, "Lost the signal from the drone");
                    say("linklost", line("Signal lost. The drone will return home if it doesn't reconnect.",
                            "I've lost the signal. Don't panic, I'll come home on my own if it doesn't come back.",
                            "Connection lost. If I don't hear from you soon, I'm flying home. I know the way.",
                            "Lost the link. Try pointing the antennas at me. If not, I'll find my own way home."), 20_000);
                }
                handler.postDelayed(this, 1_000);
            }
        }, 1_000);
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

    private static String spokenDist(double m, boolean imperial) {
        if (imperial) {
            double ft = m * Units.FEET_PER_METER;
            if (ft >= 2640) return String.format(java.util.Locale.US, "%.1f miles", ft / 5280);
            return Math.round(ft) + " feet";
        }
        if (m >= 1000) return String.format(java.util.Locale.US, "%.1f kilometres", m / 1000);
        return Math.round(m) + " metres";
    }

    /** 120 m is said as the familiar "400 feet". */
    private static String spokenAlt(double m, boolean imperial) {
        if (imperial) return (Math.abs(m - 120) < 1 ? 400 : Math.round(m * Units.FEET_PER_METER / 10) * 10) + " feet";
        return Math.round(m) + " metre";
    }

    private static String spokenSpeed(double mps, boolean imperial) {
        return imperial ? Math.round(mps * 2.23694) + " miles an hour" : Math.round(mps * 3.6) + " kilometres an hour";
    }

    private static String spokenDuration(long seconds) {
        if (seconds < 60) return seconds + " seconds";
        long m = seconds / 60, s = seconds % 60;
        return m + (m == 1 ? " minute" : " minutes") + (s >= 10 ? " " + s + " seconds" : "");
    }
}
