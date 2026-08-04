package com.mockloc;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * MockLoc — dev-only GPS spoofing + synthetic directional-RSSI model for
 * testing the whyfi passive radio scanner on an Android emulator without
 * a physical device.
 *
 * Registers a test location provider on the emulator and walks a jittered
 * path around a user-chosen center point so each scan whyfi takes gets a
 * different GPS fix.  A simple HTTP server on port 8080 exposes the
 * current walk position plus a synthetic RSSI computed from a directional
 * antenna pattern modelled at the center point.
 *
 * The UI is map-centric: an osmdroid MapView shows the AP marker (tap to
 * move), the path outline, and the walker's current position as a moving
 * dot.  Controls (address geocode, shape picker, size sliders, start/stop)
 * live below the map.  All walk parameters are live-adjustable — the walker
 * reads the current config each tick, so changing shape or size while
 * spoofing updates the path in real time without restarting.
 *
 * mockloc CANNOT inject synthetic WiFi scan results — Android has no "test
 * WiFi provider" API and the emulator's WiFi HAL always returns "AndroidWifi"
 * at -50 dBm.  The HTTP server is the bridge: whyfi (or any test harness)
 * reads the JSON from localhost:8080 and can overlay the synthetic RSSI on
 * its own scan data in a debug hook.  Wiring that into whyfi's scan path is
 * a separate task.
 */
public class MainActivity extends Activity {

    // ── Location provider ──────────────────────────────────────────────

    private LocationManager lm;
    private Handler handler;
    private boolean spoofing = false;

    // ── Walk state ──────────────────────────────────────────────────────

    private double apLat, apLng;          // AP / center position
    private double curLat, curLng;       // current walker position
    private double bearingFromAp;        // bearing from AP to walker (deg)
    private double distanceFromAp;       // meters from AP to walker

    private int shape = 0;               // 0=circle, 1=oval, 2=rectangle
    private double paramA = 80;          // circle: radius; oval: major; rect: width
    private double paramB = 60;          // oval: minor; rect: height (unused for circle)
    private int step = 0;

    // ── Antenna pattern ─────────────────────────────────────────────────

    private static final int NUM_SECTORS = 36;            // 10 deg per sector
    private double[] antennaGain;                        // dB, per sector

    // ── HTTP server ─────────────────────────────────────────────────────

    private static final int HTTP_PORT = 8080;
    private ServerThread httpThread;
    private final Object posLock = new Object();

    // ── Map ──────────────────────────────────────────────────────────────

    private MapView mapView;
    private Marker apMarker;
    private Marker walkerMarker;
    private Polyline pathOutline;
    private Polyline pathTraced;

    // ── UI ──────────────────────────────────────────────────────────────

    private TextView statusText;
    private EditText addressInput;
    private EditText latInput;
    private EditText lngInput;
    private EditText paramAInput;
    private EditText paramBInput;
    private RadioGroup shapeGroup;
    private TextView resolvedText;

    private static final String PREFS = "mockloc";
    private static final String KEY_LAT = "ap_lat";
    private static final String KEY_LNG = "ap_lng";
    private static final String KEY_SHAPE = "shape";
    private static final String KEY_PARAM_A = "param_a";
    private static final String KEY_PARAM_B = "param_b";

    private final Random rng = new Random();

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // osmdroid requires a user-agent for OSM tile usage policy, and the
        // tile cache must point at app-private storage (cacheDir) rather than
        // osmdroid's external-storage default — otherwise we'd need a storage
        // permission on minSdk 28.  The base path also defaults to
        // getExternalFilesDir(null) which returns null on the emulator,
        // causing an NPE in the archive provider — set it to cacheDir too.
        // Same approach as whyfi's MissionScreen (which sets the tile cache;
        // we also set the base path to cover the emulator's null
        // getExternalFilesDir).
        Configuration.getInstance().setUserAgentValue(getPackageName());
        File osmBase = new File(getCacheDir(), "osmdroid");
        osmBase.mkdirs();
        Configuration.getInstance().setOsmdroidBasePath(osmBase);
        Configuration.getInstance().setOsmdroidTileCache(osmBase);

        buildUi();
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        generateAntennaPattern();
        loadPrefs();
        startHttpServer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        stopSpoofing();
        stopHttpServer();
        if (mapView != null) mapView.onDetach();
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI construction — map-centric layout
    // ─────────────────────────────────────────────────────────────────────

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        // Title
        TextView title = new TextView(this);
        title.setText("MockLoc — Emulator GPS + RSSI Sim");
        title.setTextSize(18);
        title.setPadding(0, 0, 0, 12);
        root.addView(title);

        // ── Map (fills most of the screen) ──
        mapView = new MapView(this);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.setClickable(true);
        mapView.setFocusable(true);
        mapView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(mapView);

        // AP marker — tap on map moves it
        apMarker = new Marker(mapView);
        apMarker.setTitle("AP position");
        apMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        mapView.getOverlays().add(apMarker);

        // Walker marker — updated each tick
        walkerMarker = new Marker(mapView);
        walkerMarker.setTitle("Walker");
        walkerMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        mapView.getOverlays().add(walkerMarker);

        // Path outline polyline — shows the shape boundary
        pathOutline = new Polyline();
        pathOutline.getOutlinePaint().setColor(Color.parseColor("#4CAF50"));
        pathOutline.getOutlinePaint().setStrokeWidth(4f);
        pathOutline.getOutlinePaint().setStyle(Paint.Style.STROKE);
        mapView.getOverlays().add(pathOutline);

        // Traced path — the trail the walker has actually followed
        pathTraced = new Polyline();
        pathTraced.getOutlinePaint().setColor(Color.parseColor("#FF9800"));
        pathTraced.getOutlinePaint().setStrokeWidth(2f);
        pathTraced.getOutlinePaint().setStyle(Paint.Style.STROKE);
        mapView.getOverlays().add(pathTraced);

        // Tap-to-place: tapping the map moves the AP marker to the tapped
        // point.  The user can also pan/zoom freely; a tap (not a drag)
        // places the marker.
        mapView.setOnTouchListener((v, event) -> {
            boolean consumed = false;
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                // Only treat as a tap if the map wasn't being dragged —
                // osmdroid fires ACTION_UP after both, but a drag has a
                // preceding ACTION_MOVE.  We track this with a simple flag.
                if (!mapDragged) {
                    GeoPoint tapped = (GeoPoint) mapView.getProjection()
                            .fromPixels((int) event.getX(), (int) event.getY());
                    setApPosition(tapped.getLatitude(), tapped.getLongitude());
                    consumed = true;
                }
                mapDragged = false;
            } else if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                mapDragged = true;
            }
            // Let the map handle pan/zoom gestures
            v.onTouchEvent(event);
            return consumed;
        });

        // ── Controls below the map ──

        // Address geocode row
        TextView addrLabel = new TextView(this);
        addrLabel.setText("Address or place name:");
        addrLabel.setTextSize(13);
        addrLabel.setPadding(0, 8, 0, 4);
        root.addView(addrLabel);

        LinearLayout addrRow = new LinearLayout(this);
        addrRow.setOrientation(LinearLayout.HORIZONTAL);
        addressInput = new EditText(this);
        addressInput.setHint("e.g. Marienplatz, Munich");
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT);
        addressInput.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        addrRow.addView(addressInput);
        Button geocodeBtn = new Button(this);
        geocodeBtn.setText("Geocode");
        geocodeBtn.setOnClickListener(v -> geocodeAddress());
        addrRow.addView(geocodeBtn);
        root.addView(addrRow);

        resolvedText = new TextView(this);
        resolvedText.setTextSize(12);
        resolvedText.setPadding(0, 8, 0, 8);
        root.addView(resolvedText);

        // Manual lat/lng entry
        TextView manualLabel = new TextView(this);
        manualLabel.setText("Or enter lat/lng directly:");
        manualLabel.setTextSize(13);
        manualLabel.setPadding(0, 4, 0, 4);
        root.addView(manualLabel);

        LinearLayout coordRow = new LinearLayout(this);
        coordRow.setOrientation(LinearLayout.HORIZONTAL);
        latInput = new EditText(this);
        latInput.setHint("Latitude");
        latInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        latInput.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        lngInput = new EditText(this);
        lngInput.setHint("Longitude");
        lngInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        lngInput.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        coordRow.addView(latInput);
        coordRow.addView(lngInput);
        root.addView(coordRow);

        Button setCoordsBtn = new Button(this);
        setCoordsBtn.setText("Set Coordinates");
        setCoordsBtn.setOnClickListener(v -> setManualCoords());
        root.addView(setCoordsBtn);

        // Shape selector
        TextView shapeLabel = new TextView(this);
        shapeLabel.setText("Walk shape:");
        shapeLabel.setTextSize(13);
        shapeLabel.setPadding(0, 12, 0, 4);
        root.addView(shapeLabel);

        shapeGroup = new RadioGroup(this);
        shapeGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbCircle = new RadioButton(this); rbCircle.setText("Circle"); rbCircle.setId(0);
        RadioButton rbOval = new RadioButton(this); rbOval.setText("Oval"); rbOval.setId(1);
        RadioButton rbRect = new RadioButton(this); rbRect.setText("Rectangle"); rbRect.setId(2);
        shapeGroup.addView(rbCircle);
        shapeGroup.addView(rbOval);
        shapeGroup.addView(rbRect);
        shapeGroup.setOnCheckedChangeListener((g, id) -> {
            shape = id;
            updateParamHints();
            updatePathOutline();
            savePrefs();
        });
        root.addView(shapeGroup);

        // Size parameters
        LinearLayout paramRow = new LinearLayout(this);
        paramRow.setOrientation(LinearLayout.HORIZONTAL);
        paramAInput = new EditText(this);
        paramAInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        paramAInput.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        paramBInput = new EditText(this);
        paramBInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        paramBInput.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        paramRow.addView(paramAInput);
        paramRow.addView(paramBInput);
        root.addView(paramRow);

        Button applyParamsBtn = new Button(this);
        applyParamsBtn.setText("Apply Sizes");
        applyParamsBtn.setOnClickListener(v -> applyParams());
        root.addView(applyParamsBtn);

        // Start / stop
        LinearLayout startStopRow = new LinearLayout(this);
        startStopRow.setOrientation(LinearLayout.HORIZONTAL);
        Button startBtn = new Button(this);
        startBtn.setText("Start Spoofing");
        startBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        startBtn.setOnClickListener(v -> startSpoofing());
        Button stopBtn = new Button(this);
        stopBtn.setText("Stop Spoofing");
        stopBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        stopBtn.setOnClickListener(v -> stopSpoofing());
        startStopRow.addView(startBtn);
        startStopRow.addView(stopBtn);
        root.addView(startStopRow);

        // Status
        statusText = new TextView(this);
        statusText.setTextSize(12);
        statusText.setPadding(0, 12, 0, 0);
        root.addView(statusText);

        setContentView(root);
    }

    private boolean mapDragged = false;

    private void updateParamHints() {
        switch (shape) {
            case 0:
                paramAInput.setHint("Radius (m)");
                paramBInput.setHint("(unused)");
                paramBInput.setEnabled(false);
                break;
            case 1:
                paramAInput.setHint("Major axis (m)");
                paramBInput.setHint("Minor axis (m)");
                paramBInput.setEnabled(true);
                break;
            case 2:
                paramAInput.setHint("Width (m)");
                paramBInput.setHint("Height (m)");
                paramBInput.setEnabled(true);
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Map updates
    // ─────────────────────────────────────────────────────────────────────

    private void setApPosition(double lat, double lng) {
        apLat = lat;
        apLng = lng;
        latInput.setText(String.format(Locale.US, "%.6f", lat));
        lngInput.setText(String.format(Locale.US, "%.6f", lng));
        resolvedText.setText(String.format(Locale.US, "AP at %.6f, %.6f", lat, lng));
        savePrefs();
        refreshMap();
    }

    /**
     * Redraw the AP marker, path outline, and walker marker on the map.
     * Called whenever the AP position, shape, or size changes, and each
     * walk tick for the walker marker.
     */
    private void refreshMap() {
        GeoPoint apPoint = new GeoPoint(apLat, apLng);
        apMarker.setPosition(apPoint);
        // If no walker position yet, center on AP
        if (curLat == 0 && curLng == 0) {
            walkerMarker.setPosition(apPoint);
        } else {
            walkerMarker.setPosition(new GeoPoint(curLat, curLng));
        }
        updatePathOutline();
        mapView.invalidate();
    }

    /**
     * Build the path outline polyline from the current shape + size
     * parameters.  Called on shape/size change and on each tick (cheap).
     */
    private void updatePathOutline() {
        List<GeoPoint> points = new ArrayList<>();
        int n = 64;
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double[] pos = shapePosition(t);
            points.add(new GeoPoint(pos[0], pos[1]));
        }
        pathOutline.setPoints(points);
        mapView.invalidate();
    }

    /**
     * Add a point to the traced path (the trail the walker has actually
     * followed).  Called each tick.
     */
    private void addTracedPoint(double lat, double lng) {
        List<GeoPoint> current = pathTraced.getPoints();
        if (current == null) current = new ArrayList<>();
        current.add(new GeoPoint(lat, lng));
        // Cap at 200 points to avoid unbounded growth
        if (current.size() > 200) {
            current = new ArrayList<>(current.subList(current.size() - 200, current.size()));
        }
        pathTraced.setPoints(current);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Geocoding
    // ─────────────────────────────────────────────────────────────────────

    private void geocodeAddress() {
        String addr = addressInput.getText().toString().trim();
        if (addr.isEmpty()) {
            toast("Enter an address first");
            return;
        }
        resolvedText.setText("Resolving...");
        // Geocoder does network I/O — run on a background thread to avoid
        // NetworkOnMainThreadException.
        new Thread(() -> {
            try {
                Geocoder gc = new Geocoder(this, Locale.getDefault());
                List<android.location.Address> results = gc.getFromLocationName(addr, 1);
                if (results == null || results.isEmpty()) {
                    runOnUiThread(() -> resolvedText.setText("No results for: " + addr));
                    return;
                }
                android.location.Address a = results.get(0);
                double lat = a.getLatitude();
                double lng = a.getLongitude();
                runOnUiThread(() -> {
                    setApPosition(lat, lng);
                    // Center the map on the geocoded point
                    mapView.getController().setCenter(new GeoPoint(lat, lng));
                    mapView.getController().setZoom(17.0);
                    resolvedText.setText(String.format(Locale.US,
                            "Resolved: %.6f, %.6f\n%s\nTap map to adjust.",
                            lat, lng,
                            a.getAddressLine(0) != null ? a.getAddressLine(0) : ""));
                });
            } catch (IOException e) {
                runOnUiThread(() -> resolvedText.setText("Geocode failed: " + e.getMessage()));
            }
        }).start();
    }

    private void setManualCoords() {
        try {
            double lat = Double.parseDouble(latInput.getText().toString().trim());
            double lng = Double.parseDouble(lngInput.getText().toString().trim());
            setApPosition(lat, lng);
            mapView.getController().setCenter(new GeoPoint(lat, lng));
            mapView.getController().setZoom(17.0);
        } catch (NumberFormatException e) {
            toast("Invalid coordinates");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Parameters
    // ─────────────────────────────────────────────────────────────────────

    private void applyParams() {
        try {
            paramA = Double.parseDouble(paramAInput.getText().toString().trim());
            if (shape != 0) {
                paramB = Double.parseDouble(paramBInput.getText().toString().trim());
            }
            savePrefs();
            updatePathOutline();
            toast("Sizes applied");
        } catch (NumberFormatException e) {
            toast("Invalid size values");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SharedPreferences persistence
    // ─────────────────────────────────────────────────────────────────────

    private void savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putFloat(KEY_LAT, (float) apLat)
                .putFloat(KEY_LNG, (float) apLng)
                .putInt(KEY_SHAPE, shape)
                .putFloat(KEY_PARAM_A, (float) paramA)
                .putFloat(KEY_PARAM_B, (float) paramB)
                .apply();
    }

    private void loadPrefs() {
        var p = getSharedPreferences(PREFS, MODE_PRIVATE);
        apLat = p.getFloat(KEY_LAT, 48.1351f);
        apLng = p.getFloat(KEY_LNG, 11.5820f);
        shape = p.getInt(KEY_SHAPE, 0);
        paramA = p.getFloat(KEY_PARAM_A, 80f);
        paramB = p.getFloat(KEY_PARAM_B, 60f);

        latInput.setText(String.format(Locale.US, "%.6f", apLat));
        lngInput.setText(String.format(Locale.US, "%.6f", apLng));
        paramAInput.setText(String.format(Locale.US, "%.0f", paramA));
        paramBInput.setText(String.format(Locale.US, "%.0f", paramB));
        shapeGroup.check(shape);
        updateParamHints();

        // Center map on the saved AP position
        mapView.getController().setCenter(new GeoPoint(apLat, apLng));
        mapView.getController().setZoom(17.0);
        refreshMap();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Spoofing
    // ─────────────────────────────────────────────────────────────────────

    private void startSpoofing() {
        if (spoofing) return;
        try {
            // powerRequirement (9th arg) must be 1-3, not 0 — AGP/Android
            // rejects 0 and the emulator's GPS won't update.
            lm.addTestProvider(LocationManager.GPS_PROVIDER,
                    false, false, false, false,
                    true, true, true,
                    1, 1);
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            lm.addTestProvider(LocationManager.NETWORK_PROVIDER,
                    false, false, false, false,
                    true, true, true,
                    1, 1);
            lm.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            spoofing = true;
            step = 0;
            pathTraced.setPoints(new ArrayList<>()); // clear traced trail
            handler.post(walkLoop);
            statusText.setText("Spoofing started.\nHTTP server on port " + HTTP_PORT);
        } catch (SecurityException e) {
            statusText.setText("FAILED: " + e.getMessage()
                    + "\nEnable mock location in Dev Options and select this app.");
        }
    }

    private void stopSpoofing() {
        if (!spoofing) return;
        handler.removeCallbacks(walkLoop);
        spoofing = false;
        try { lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false); } catch (Exception ignored) {}
        try { lm.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false); } catch (Exception ignored) {}
        statusText.setText("Spoofing stopped.");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Walk loop — random dwell, jittered positions, varied step size.
    // Reads shape/size/AP position fresh every tick so the user can
    // change them live while the walk is running.
    // ─────────────────────────────────────────────────────────────────────

    private final Runnable walkLoop = new Runnable() {
        @Override
        public void run() {
            if (!spoofing) return;

            // Number of outline points depends on shape — read shape fresh
            // each tick so live shape changes take effect immediately.
            int basePoints;
            switch (shape) {
                case 2:  basePoints = 24; break;   // rectangle: 6 per leg
                default: basePoints = 20; break;   // circle / oval
            }

            double t = (double) (step % basePoints) / basePoints;

            // Base position on the outline — reads apLat/apLng, paramA,
            // paramB, and shape live, so all are live-adjustable.
            double[] base = shapePosition(t);
            double bLat = base[0];
            double bLng = base[1];

            // Wander: small perpendicular offset, bounded so we don't drift
            // away from the chosen area.  +-4m in degrees.
            double wander = (rng.nextDouble() - 0.5) * 0.00008;

            // Step-size jitter: vary the fraction slightly so speed isn't
            // constant — we advance by 1 most ticks but sometimes skip or
            // repeat to simulate pace variation.
            int advance = 1;
            if (rng.nextDouble() < 0.15) advance = 2;        // occasional burst
            else if (rng.nextDouble() < 0.1) advance = 0;     // occasional stall

            curLat = bLat + wander;
            curLng = bLng + wander * 0.7;

            // Compute bearing + distance from AP to walker — reads apLat/apLng
            // live, so moving the AP marker while walking also takes effect.
            bearingFromAp = bearing(apLat, apLng, curLat, curLng);
            distanceFromAp = haversineMeters(apLat, apLng, curLat, curLng);

            pushFix(LocationManager.GPS_PROVIDER, curLat, curLng);
            pushFix(LocationManager.NETWORK_PROVIDER, curLat, curLng);

            double rssi = computeRSSI(bearingFromAp, distanceFromAp);

            statusText.setText(String.format(Locale.US,
                    "step %d  pt %d/%d\nlat=%.6f lng=%.6f\nbear=%.1f deg dist=%.1fm\nRSSI=%.1f dBm",
                    step, step % basePoints, basePoints,
                    curLat, curLng, bearingFromAp, distanceFromAp, rssi));

            // Update map overlays — walker dot + traced trail
            walkerMarker.setPosition(new GeoPoint(curLat, curLng));
            addTracedPoint(curLat, curLng);
            mapView.invalidate();

            step += advance;

            // Dwell: 1s normally, sometimes pause 2-5s
            int delayMs = 1000;
            double r = rng.nextDouble();
            if (r < 0.2) delayMs = 2000 + rng.nextInt(3000);  // pause
            else if (r < 0.35) delayMs = 500 + rng.nextInt(500);  // quick step

            handler.postDelayed(this, delayMs);
        }
    };

    /**
     * Returns {lat, lng} for a point at parameter t (0..1) around the
     * chosen shape outline.  Reads apLat/apLng, paramA, paramB, and shape
     * live — all are editable while the walk is running.
     */
    private double[] shapePosition(double t) {
        double angle = 2 * Math.PI * t;
        switch (shape) {
            case 1: {  // oval
                double aM = paramA;
                double bM = paramB;
                double dLat = (aM / 111000.0) * Math.sin(angle);
                double dLng = (bM / (111000.0 * Math.cos(Math.toRadians(apLat)))) * Math.cos(angle);
                return new double[]{apLat + dLat, apLng + dLng};
            }
            case 2: {  // rectangle — four legs
                double w = paramA;
                double h = paramB;
                double leg = t * 4;  // 0..4
                int legIdx = (int) leg;
                double frac = leg - legIdx;
                double dLat, dLng;
                double halfW = w / 2;
                double halfH = h / 2;
                switch (legIdx) {
                    case 0: dLat = halfH; dLng = -halfW + w * frac; break;
                    case 1: dLat = halfH - h * frac; dLng = halfW; break;
                    case 2: dLat = -halfH; dLng = halfW - w * frac; break;
                    default: dLat = -halfH + h * frac; dLng = -halfW; break;
                }
                double lat = apLat + dLat / 111000.0;
                double lng = apLng + dLng / (111000.0 * Math.cos(Math.toRadians(apLat)));
                return new double[]{lat, lng};
            }
            default: {  // circle
                double rM = paramA;
                double dLat = (rM / 111000.0) * Math.sin(angle);
                double dLng = (rM / (111000.0 * Math.cos(Math.toRadians(apLat)))) * Math.cos(angle);
                return new double[]{apLat + dLat, apLng + dLng};
            }
        }
    }

    private void pushFix(String provider, double lat, double lng) {
        Location loc = new Location(provider);
        loc.setLatitude(lat);
        loc.setLongitude(lng);
        loc.setAltitude(500.0);
        loc.setAccuracy(5.0f);
        loc.setSpeed(1.5f);
        loc.setBearing((float) bearingFromAp);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(System.nanoTime());
        try { lm.setTestProviderLocation(provider, loc); } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // Directional antenna pattern
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Generate a lumpy antenna pattern: 36 sectors of 10 deg, each a random
     * base value smoothed so adjacent sectors are correlated.  The pattern
     * is fixed for the session — the same bearing always yields the same
     * base gain.  This models a directional AP whose radiation pattern is
     * preserved in the directions it transmits.
     */
    private void generateAntennaPattern() {
        antennaGain = new double[NUM_SECTORS];
        // Start with random values
        double[] raw = new double[NUM_SECTORS];
        for (int i = 0; i < NUM_SECTORS; i++) {
            raw[i] = rng.nextDouble() * 10 - 5;  // -5..+5 dB
        }
        // Smooth: each sector = weighted average of itself + neighbors.
        // Three passes for a lumpy but correlated pattern.
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < NUM_SECTORS; i++) {
                int prev = (i - 1 + NUM_SECTORS) % NUM_SECTORS;
                int next = (i + 1) % NUM_SECTORS;
                antennaGain[i] = 0.5 * raw[i] + 0.25 * raw[prev] + 0.25 * raw[next];
            }
            System.arraycopy(antennaGain, 0, raw, 0, NUM_SECTORS);
        }
    }

    /**
     * Compute synthetic RSSI at the walker's position.
     *
     * RSSI = pattern_gain(bearing) - path_loss(distance) + noise
     *
     * pattern_gain is the fixed per-session antenna pattern (same bearing
     * always gives the same base gain).  path_loss uses a log-distance
     * model.  noise is small per-observation random jitter.
     */
    private double computeRSSI(double bearing, double distance) {
        int sector = (int) (bearing / (360.0 / NUM_SECTORS)) % NUM_SECTORS;
        if (sector < 0) sector += NUM_SECTORS;
        double gain = antennaGain[sector];

        // Log-distance path loss: -40 dBm at 1m, 2.0 path-loss exponent
        // (typical indoor).  Clamp distance to >=1m to avoid log(0).
        double d = Math.max(distance, 1.0);
        double pl = 40.0 + 20.0 * Math.log10(d);

        // Per-observation noise: +-2 dB
        double noise = (rng.nextDouble() - 0.5) * 4.0;

        return gain - pl + noise;
    }

    // ─────────────────────────────────────────────────────────────────────
    // HTTP server — exposes current position + synthetic RSSI as JSON
    // ─────────────────────────────────────────────────────────────────────

    private void startHttpServer() {
        httpThread = new ServerThread();
        httpThread.start();
    }

    private void stopHttpServer() {
        if (httpThread != null) {
            httpThread.cancel();
            httpThread = null;
        }
    }

    // Minimal HTTP server using raw ServerSocket — com.sun.net.httpserver
    // is not in the Android API.  Returns JSON with the current walker
    // position and synthetic RSSI.
    private class ServerThread extends Thread {
        private volatile boolean running = true;
        private ServerSocket serverSocket;

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(HTTP_PORT);
                while (running) {
                    try (Socket client = serverSocket.accept()) {
                        handleClient(client);
                    } catch (IOException e) {
                        if (running) {
                            // Accept failed but still running — keep going
                        }
                    }
                }
            } catch (IOException e) {
                runOnUiThread(() -> statusText.setText("HTTP server failed: " + e.getMessage()));
            }
        }

        void cancel() {
            running = false;
            try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        }

        private void handleClient(Socket client) throws IOException {
            // Read and discard the request line/headers
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            while (reader.readLine() != null && reader.ready()) {
                // drain
            }
            synchronized (posLock) {
                JSONObject json = new JSONObject();
                try {
                    json.put("lat", curLat);
                    json.put("lng", curLng);
                    json.put("rssi_dbm", computeRSSI(bearingFromAp, distanceFromAp));
                    json.put("ap_lat", apLat);
                    json.put("ap_lng", apLng);
                    json.put("bearing_from_ap", bearingFromAp);
                    json.put("distance_m", distanceFromAp);
                    json.put("shape", shape == 0 ? "circle" : shape == 1 ? "oval" : "rectangle");
                } catch (Exception e) {
                    json = new JSONObject();
                    try { json.put("error", e.getMessage()); } catch (Exception ignored) {}
                }
                byte[] body = json.toString().getBytes();
                String header = "HTTP/1.0 200 OK\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";
                OutputStream os = client.getOutputStream();
                os.write(header.getBytes());
                os.write(body);
                os.flush();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Geo helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Bearing in degrees from point 1 to point 2. */
    private static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double la1 = Math.toRadians(lat1);
        double la2 = Math.toRadians(lat2);
        double dLng = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dLng) * Math.cos(la2);
        double x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dLng);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Haversine distance in meters. */
    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000;
        double la1 = Math.toRadians(lat1);
        double la2 = Math.toRadians(lat2);
        double dLa = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLa / 2) * Math.sin(dLa / 2)
                + Math.cos(la1) * Math.cos(la2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Misc
    // ─────────────────────────────────────────────────────────────────────

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
