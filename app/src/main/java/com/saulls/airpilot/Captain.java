package com.saulls.airpilot;

import java.util.Calendar;
import java.util.Random;

/** "This is your captain speaking": a different airline-style announcement every takeoff and landing. */
final class Captain {

    private Captain() {
    }

    private static String pick(Random r, String... s) {
        return s[r.nextInt(s.length)];
    }

    private static final String[] AIRLINES = {"Air Pilot", "Mavic Airways", "Backyard Airlines", "Propeller Express",
            "Quadcopter International", "Air Pilot Regional", "Thumbstick Airlines", "Gimbal Jet"};

    static String takeoff(Telemetry.Snapshot s, boolean imperial, Random r) {
        String airline = pick(r, AIRLINES);
        int flight = 100 + r.nextInt(900);
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String part = hour < 12 ? "this morning" : hour < 17 ? "this afternoon" : "this evening";

        String hello = pick(r,
                "Ladies and gentlemen, this is your captain speaking. Welcome aboard " + airline + " flight " + flight + ".",
                "Good " + (hour < 12 ? "morning" : hour < 17 ? "afternoon" : "evening") + " passengers, and welcome aboard " + airline + ".",
                "Flight attendants, prepare for takeoff. Welcome to " + airline + " flight " + flight + ".",
                "Hello everyone, this is your captain. Thanks for flying " + airline + " " + part + ".",
                "Tower, " + airline + " " + flight + ", rolling. Passengers, welcome aboard.");

        String info;
        int pick = r.nextInt(4);
        if (pick == 0 && s.batteryPct > 0) info = "We're departing with " + s.batteryPct + " percent fuel"
                + (s.batteryPct >= 90 ? ", so the snack cart is fully stocked." : s.batteryPct >= 60 ? ", which should be plenty for a short hop." : ". It'll be a short one, folks.");
        else if (pick == 1 && s.satellites > 0) info = s.satellites + " satellites are keeping an eye on us today"
                + (s.satellites >= 12 ? ", which is more than enough." : ", so stay close.");
        else if (pick == 2) info = pick(r,
                "Our cruising altitude today is whatever you feel like, up to " + (imperial ? "400 feet." : "120 metres."),
                "Flight time will be roughly one battery, depending on how many cool shots you want.",
                "Our route today takes us up, around, and hopefully back down in one piece.");
        else info = pick(r,
                "Please make sure your tray tables are stowed and your thumbs are on the sticks.",
                "Please keep your seatbelt fastened and your eyes on the aircraft at all times.",
                "In the unlikely event of a water landing, please don't. I can't swim.",
                "Please note this is a no-smoking flight, mostly because I have no lungs.",
                "There will be no in-flight movie, but you're welcome to film one.");

        String bye = pick(r,
                "Sit back, relax, and enjoy the flight.",
                "Cabin crew, seats for takeoff.",
                "We're cleared for takeoff. Here we go!",
                "Thank you for choosing " + airline + ". We know you have other drones.",
                "Up we go. Don't forget to look up from the screen once in a while.",
                "Let's get airborne.");
        return hello + " " + info + " " + bye;
    }

    static String landing(String flightTime, Random r) {
        return pick(r,
                "Ladies and gentlemen, welcome to the ground. Local time is now. Flight time was " + flightTime + ". Please remain seated until the propellers come to a complete stop.",
                "This is your captain. We've landed after " + flightTime + ". Thank you for flying with us, and please collect all your photos before leaving the aircraft.",
                "Touchdown. Flight time " + flightTime + ". On behalf of the entire crew, which is me, thanks for flying Air Pilot.",
                "We have arrived. " + flightTime + " in the air. Please check the overhead bin, your memories may have shifted during flight.",
                "And we're on the ground after " + flightTime + ". Passengers may now unbuckle and brag about the footage.");
    }
}
