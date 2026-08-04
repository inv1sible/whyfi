# MockLoc

Dev-only GPS spoofing + synthetic directional-RSSI model for testing the
whyfi passive radio scanner on an Android emulator without a physical device.

## What it does

1. **GPS spoofing**: registers a test location provider on the emulator and
   walks a jittered path around a user-chosen center point. Each tick pushes
   a new GPS fix so whyfi's `getLastKnownLocation()` returns a different
   position on every scan.

2. **Map view for AP position**: an osmdroid (OpenStreetMap) map fills the
   upper half of the screen.  After geocoding an address or entering lat/lng,
   the map centers on the resolved point.  Tap anywhere on the map to move
   the AP marker — the tapped point wins over the geocoded/typed position.
   Pan and zoom are supported via multi-touch.  No Google Maps, no API key
   (matches whyfi's Play-Services-free posture).

3. **Live-adjustable walk**: the walk shape (circle/oval/rectangle) and size
   parameters are editable while the walk is running — the walker reads the
   current config every tick, so changing shape or size updates the path in
   real time without restarting.  Moving the AP marker while walking also
   takes effect immediately.  The map shows:
   - Green polyline: the path outline (shape boundary)
   - Orange polyline: the trail the walker has actually followed
   - AP marker and walker marker (updated each tick)

4. **Walk shape control**: circle, oval, or rectangle. Adjust the size(s) in
   meters via text fields. The walk loosely follows the outline with random
   wander, varied dwell times (1-5s), and occasional pace changes.

5. **Directional RSSI model**: models an AP at the geocoded position with a
   directional radiation pattern (36 sectors of 10 degrees, smoothed into a
   lumpy fixed-per-session antenna gain). As the walker moves around the AP,
   the observed RSSI = pattern_gain(bearing) - path_loss(distance) + noise.
   The pattern is preserved: the same bearing always gives the same base
   gain, but each observation has small random noise.

## Important limitation

mockloc **cannot inject synthetic WiFi scan results** into the emulator.
Android has no "test WiFi provider" API, and the emulator's WiFi HAL always
returns "AndroidWifi" at -50 dBm. mockloc only *computes* the synthetic RSSI
and exposes it via a local HTTP server on port 8080:

```json
{"lat":..., "lng":..., "rssi_dbm":..., "ap_lat":..., "ap_lng", "bearing_from_ap":..., "distance_m":..., "shape":"circle"}
```

whyfi can read this from a debug hook to overlay the synthetic RSSI on its
own scan data. **Wiring that into whyfi's scan path is a separate task.**

## Build

No JDK on the host — use the same Gradle Docker image as the whyfi
android-builder.  osmdroid is added as a dependency (same version as the
whyfi Mission view — `org.osmdroid:osmdroid-android:6.1.20`).

```bash
sudo docker run --rm -v /path/to/mockloc:/workspace -v /opt/android-sdk:/opt/android-sdk \
  -e ANDROID_SDK_ROOT=/opt/android-sdk -w /workspace gradle:8.9-jdk17 \
  gradle assembleDebug --no-daemon --console=plain
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Install and test

```bash
export PATH="/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/34.0.0:$PATH"
adb -s emulator-5554 uninstall com.mockloc 2>/dev/null
adb -s emulator-5554 install app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell appops set com.mockloc android:mock_location allow
adb -s emulator-5554 shell pm grant com.mockloc android.permission.ACCESS_FINE_LOCATION
adb -s emulator-5554 shell am start -n com.mockloc/.MainActivity

# verify GPS spoofing:
adb -s emulator-5554 shell "dumpsys location 2>/dev/null | grep -iE 'last location=Location\[gps' | head -1"

# query the RSSI HTTP server:
adb -s emulator-5554 shell "nc -w 2 127.0.0.1 8080 <<< 'GET / HTTP/1.0\n\n'"
```

You must enable mock location in the emulator's Developer Options and select
MockLoc as the mock location app before starting spoofing.

## Not a service

mockloc is a standalone dev/testing tool. It is not part of the main whyfi
build, not in docker-compose.yml, and not a service. Build and install it on
demand.