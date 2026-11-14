# Air Pilot

A clean, ad-free Android flight app for DJI drones, built on DJI Mobile SDK v4 / UX SDK 4.16.2
(the same SDK DJI Go Fly uses). Package name: `com.saulls.airpilot`.

The app opens to a start screen that registers with DJI and waits for the drone. When the drone connects it
goes straight to the fly screen: live video, telemetry, takeoff/land, return home and camera controls.

## Features

- **Simple mode (default):** live video, a big altitude / distance / speed / flight-time readout,
  takeoff, return home, shutter and the status bar. Nothing else in the way.
- **Pro mode:** one tap on the SIMPLE/PRO pill brings back ISO, shutter, aperture, EV, white balance,
  focus, lens controls and DJI's full dashboard.
- **Flight log:** every flight is recorded automatically (time, duration, max height, distance, speed).
  Tap a flight to open its takeoff spot in Maps.
- **Safety & Settings:** beginner mode, max altitude, distance limit, return-to-home altitude and
  low-battery warning, saved straight to the drone. Plus units (ft/m), grid lines and default mode.
- Screen stays awake while the fly screen is open.

Mockups (drawn from the layouts, not real device screenshots):

| Simple mode | Pro mode |
|---|---|
| ![Simple](docs/mockups/2-fly-simple-mode.png) | ![Pro](docs/mockups/3-fly-pro-mode.png) |

More in [docs/mockups](docs/mockups).

## One-time setup

1. **DJI App Key.** At developer.dji.com go to Apps, then Create App. Choose Mobile SDK and enter the package
   name `com.saulls.airpilot` exactly. Copy the key.
2. **Add it to GitHub.** In this repo go to Settings → Secrets and variables → Actions → New repository secret.
   Name it `DJI_API_KEY` and paste the key. The key never goes in the code.

## Building

Every push builds automatically. You can also open Actions → **Build Air Pilot APK** → Run workflow.
When the run finishes, download **AirPilot-debug-apk** from the bottom of the run page.

## Installing on the phone

1. Unzip the download and open the `.apk` with Files, then allow installing from that app if asked.
2. The phone needs internet the first time the app opens, so it can register with DJI.
3. Plug the phone into the controller, power on the controller and then the drone.
   The app jumps to the fly screen once the drone connects. The **FLY** button opens it any time.

New builds install over the old one, so your settings are kept.

## Local build (optional)

Use JDK 11 and the Android SDK. Put `DJI_API_KEY=yourkey` in `local.properties`, then run `./gradlew assembleDebug`.
