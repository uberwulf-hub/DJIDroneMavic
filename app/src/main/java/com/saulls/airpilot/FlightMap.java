package com.saulls.airpilot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;

/**
 * Worldwide map for the fly screen (OpenStreetMap or satellite imagery, no API key).
 * Shows the aircraft with its heading, the home point, the phone, and the flight path.
 * As a mini map it acts like one big button; expanded it can be panned and zoomed.
 */
public class FlightMap extends FrameLayout implements LocationListener {

    /** Esri World Imagery: free satellite tiles, served as z/y/x. */
    private static final ITileSource SATELLITE = new OnlineTileSourceBase("EsriWorldImagery",
            0, 19, 256, ".jpg",
            new String[]{"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"},
            "Imagery © Esri") {
        @Override
        public String getTileURLString(long index) {
            return getBaseUrl() + MapTileIndex.getZoom(index) + "/"
                    + MapTileIndex.getY(index) + "/" + MapTileIndex.getX(index);
        }
    };

    private static final double DEFAULT_ZOOM = 17.0;
    private static final int MAX_PATH_POINTS = 5000;

    private final MapView map;
    private final Marker aircraft;
    private final Marker home;
    private final Marker phone;
    private final Polyline path;
    private final TextView layerButton;
    private final TextView recenterButton;
    private final TextView attribution;
    private final Bitmap aircraftBitmap;

    private boolean mini = true;
    private boolean follow = true;
    private boolean centeredOnce = false;
    private double lastYaw = Double.NaN;
    private GeoPoint lastAircraft;
    private GeoPoint phonePoint;
    private boolean listeningForLocation;

    public FlightMap(Context context) {
        this(context, null);
    }

    public FlightMap(Context context, AttributeSet attrs) {
        super(context, attrs);

        // osmdroid needs a user agent (OSM tile policy) and a cache folder it is allowed to write.
        Configuration.getInstance().setUserAgentValue(context.getPackageName());
        File base = new File(context.getCacheDir(), "osmdroid");
        Configuration.getInstance().setOsmdroidBasePath(base);
        Configuration.getInstance().setOsmdroidTileCache(new File(base, "tiles"));

        map = new MapView(context);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.setMinZoomLevel(3.0);
        map.setMaxZoomLevel(19.0);
        map.getController().setZoom(DEFAULT_ZOOM);
        map.getController().setCenter(new GeoPoint(39.5, -98.35)); // middle of the US until we know better
        addView(map, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        path = new Polyline(map);
        path.getOutlinePaint().setColor(Color.parseColor("#FFD54F"));
        path.getOutlinePaint().setStrokeWidth(dp(3));
        path.getOutlinePaint().setAntiAlias(true);
        map.getOverlays().add(path);

        home = new Marker(map);
        home.setIcon(new BitmapDrawable(getResources(), homeBitmap()));
        home.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        home.setInfoWindow(null);
        home.setEnabled(false);
        map.getOverlays().add(home);

        phone = new Marker(map);
        phone.setIcon(new BitmapDrawable(getResources(), dotBitmap(Color.parseColor("#4285F4"))));
        phone.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        phone.setInfoWindow(null);
        phone.setEnabled(false);
        map.getOverlays().add(phone);

        aircraftBitmap = aircraftBitmap();
        aircraft = new Marker(map);
        aircraft.setIcon(new BitmapDrawable(getResources(), aircraftBitmap));
        aircraft.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        aircraft.setInfoWindow(null);
        aircraft.setEnabled(false);
        map.getOverlays().add(aircraft);

        layerButton = chip("");
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, (int) dp(40));
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins((int) dp(12), (int) dp(110), 0, 0);
        addView(layerButton, lp);
        layerButton.setOnClickListener(v -> {
            Prefs.setSatellite(getContext(), !Prefs.isSatellite(getContext()));
            applyLayer();
        });

        recenterButton = chip("◎ Follow");
        LayoutParams rp = new LayoutParams(LayoutParams.WRAP_CONTENT, (int) dp(40));
        rp.gravity = Gravity.TOP | Gravity.START;
        rp.setMargins((int) dp(12), (int) dp(152), 0, 0);
        addView(recenterButton, rp);
        recenterButton.setOnClickListener(v -> {
            follow = true;
            recenter(true);
            updateChrome();
        });

        attribution = new TextView(context);
        attribution.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        attribution.setTextColor(Color.parseColor("#DD222222"));
        attribution.setBackgroundColor(Color.parseColor("#AAFFFFFF"));
        attribution.setPadding((int) dp(4), (int) dp(1), (int) dp(4), (int) dp(1));
        LayoutParams ap = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        ap.gravity = Gravity.BOTTOM | Gravity.END;
        addView(attribution, ap);

        applyLayer();
        updateChrome();
    }

    // ------------------------------------------------------------ lifecycle

    public void onResume() {
        map.onResume();
        applyLayer();
        startLocation();
    }

    public void onPause() {
        stopLocation();
        map.onPause();
    }

    public void onDestroy() {
        stopLocation();
        map.onDetach();
    }

    // ------------------------------------------------------------ mini / expanded

    public void setMini(boolean mini) {
        this.mini = mini;
        if (mini) follow = true;
        updateChrome();
        post(() -> recenter(false));
    }

    public boolean isMini() {
        return mini;
    }

    private void updateChrome() {
        layerButton.setVisibility(mini ? GONE : VISIBLE);
        recenterButton.setVisibility(mini || follow ? GONE : VISIBLE);
        attribution.setVisibility(mini ? GONE : VISIBLE);
    }

    /** As a mini map, a tap anywhere opens the big map instead of panning. */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mini) return true;
        if (ev.getActionMasked() == MotionEvent.ACTION_MOVE && follow) {
            follow = false; // the pilot is exploring the map; stop dragging it back
            updateChrome();
        }
        return super.onInterceptTouchEvent(ev);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mini) {
            if (ev.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return true;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    // ------------------------------------------------------------ data

    public void update(Telemetry.Snapshot s) {
        if (!Double.isNaN(s.homeLat)) {
            home.setPosition(new GeoPoint(s.homeLat, s.homeLng));
            home.setEnabled(true);
        }
        if (!Double.isNaN(s.lat)) {
            GeoPoint p = new GeoPoint(s.lat, s.lng);
            aircraft.setPosition(p);
            aircraft.setEnabled(true);
            if (!Double.isNaN(s.yaw) && (Double.isNaN(lastYaw) || Math.abs(s.yaw - lastYaw) >= 3)) {
                lastYaw = s.yaw;
                aircraft.setIcon(new BitmapDrawable(getResources(), rotate(aircraftBitmap, (float) s.yaw)));
            }
            if (s.flying && (lastAircraft == null || lastAircraft.distanceToAsDouble(p) > 2)) {
                path.addPoint(p);
                if (path.getActualPoints().size() > MAX_PATH_POINTS) {
                    path.setPoints(new java.util.ArrayList<>(path.getActualPoints()
                            .subList(MAX_PATH_POINTS / 2, path.getActualPoints().size())));
                }
            }
            lastAircraft = p;
        }
        recenter(false);
        map.invalidate();
    }

    private void recenter(boolean animate) {
        if (!follow) return;
        GeoPoint target = lastAircraft != null ? lastAircraft : phonePoint;
        if (target == null) return;
        if (!centeredOnce) {
            map.getController().setZoom(DEFAULT_ZOOM);
            centeredOnce = true;
        }
        if (animate) map.getController().animateTo(target);
        else map.getController().setCenter(target);
    }

    private void applyLayer() {
        boolean sat = Prefs.isSatellite(getContext());
        ITileSource src = sat ? SATELLITE : TileSourceFactory.MAPNIK;
        if (map.getTileProvider().getTileSource() != src) map.setTileSource(src);
        layerButton.setText(sat ? "▦ Map" : "▦ Satellite");
        attribution.setText(sat ? "Imagery © Esri" : "© OpenStreetMap contributors");
    }

    // ------------------------------------------------------------ phone location

    private void startLocation() {
        if (listeningForLocation) return;
        Context c = getContext();
        if (ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) onLocationChanged(best);
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 2000, 2f, this, Looper.getMainLooper());
                }
            }
            listeningForLocation = true;
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    private void stopLocation() {
        if (!listeningForLocation) return;
        LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        if (lm != null) lm.removeUpdates(this);
        listeningForLocation = false;
    }

    @Override
    public void onLocationChanged(Location location) {
        phonePoint = new GeoPoint(location.getLatitude(), location.getLongitude());
        phone.setPosition(phonePoint);
        phone.setEnabled(true);
        if (lastAircraft == null) recenter(false);
        map.invalidate();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    // ------------------------------------------------------------ drawing helpers

    private TextView chip(String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) dp(12), 0, (int) dp(12), 0);
        t.setBackgroundResource(R.drawable.pill_bg);
        return t;
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /** White-outlined red arrow pointing up (north); rotated to the aircraft heading. */
    private Bitmap aircraftBitmap() {
        int size = (int) dp(30);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path p = new Path();
        p.moveTo(size / 2f, size * 0.08f);
        p.lineTo(size * 0.85f, size * 0.88f);
        p.lineTo(size / 2f, size * 0.68f);
        p.lineTo(size * 0.15f, size * 0.88f);
        p.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.parseColor("#E53935"));
        c.drawPath(p, fill);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2));
        stroke.setColor(Color.WHITE);
        c.drawPath(p, stroke);
        return b;
    }

    private Bitmap homeBitmap() {
        int size = (int) dp(24);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.parseColor("#298080"));
        c.drawCircle(size / 2f, size / 2f, size / 2f - dp(1), fill);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(2));
        ring.setColor(Color.WHITE);
        c.drawCircle(size / 2f, size / 2f, size / 2f - dp(1), ring);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(dp(13));
        text.setFakeBoldText(true);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("H", size / 2f, size / 2f - (text.descent() + text.ascent()) / 2, text);
        return b;
    }

    private Bitmap dotBitmap(int color) {
        int size = (int) dp(16);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setColor(Color.WHITE);
        c.drawCircle(size / 2f, size / 2f, size / 2f, ring);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        c.drawCircle(size / 2f, size / 2f, size / 2f - dp(2.5f), fill);
        return b;
    }

    private static Bitmap rotate(Bitmap src, float degreesClockwise) {
        Matrix m = new Matrix();
        m.postRotate(degreesClockwise, src.getWidth() / 2f, src.getHeight() / 2f);
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, m, null);
        return out;
    }

}
