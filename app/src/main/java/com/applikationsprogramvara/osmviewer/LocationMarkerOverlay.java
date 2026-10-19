package com.applikationsprogramvara.osmviewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

/**
 * Current position as in MyTrails: translucent circle for the GPS accuracy, a red cone for the
 * direction the phone points to (compass, falls back to the GPS course while moving) and a red dot.
 */
public class LocationMarkerOverlay extends MyLocationNewOverlay implements SensorEventListener {

    private static final float CONE_HALF_ANGLE = 30f;

    private final MapView mapView;
    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final WindowManager windowManager;
    private final float density;

    private final Paint accuracyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accuracyStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint conePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point pixel = new Point();
    private final RectF oval = new RectF();

    private final float[] rotation = new float[9];
    private final float[] remapped = new float[9];
    private final float[] orientation = new float[3];

    private boolean sensorRegistered;
    private float azimuth = Float.NaN;      // degrees from true north, smoothed
    private float drawnAzimuth = Float.NaN;
    private float declination;
    private Location declinationFor;
    private long lastInvalidate;

    public LocationMarkerOverlay(IMyLocationProvider provider, MapView mapView) {
        super(provider, mapView);
        this.mapView = mapView;
        Context context = mapView.getContext();
        density = context.getResources().getDisplayMetrics().density;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) : null;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        accuracyFill.setColor(Color.argb(60, 70, 90, 255));
        accuracyStroke.setColor(Color.argb(150, 70, 90, 255));
        accuracyStroke.setStyle(Paint.Style.STROKE);
        accuracyStroke.setStrokeWidth(1.5f * density);
        dotFill.setColor(Color.rgb(230, 20, 20));
        dotStroke.setColor(Color.WHITE);
        dotStroke.setStyle(Paint.Style.STROKE);
        dotStroke.setStrokeWidth(2f * density);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void drawMyLocation(Canvas canvas, Projection pj, Location fix) {
        pj.toPixels(new GeoPoint(fix.getLatitude(), fix.getLongitude()), pixel);
        float x = pixel.x, y = pixel.y;
        float dotRadius = 5f * density;

        float accuracy = fix.hasAccuracy() ? pj.metersToPixels(fix.getAccuracy(), fix.getLatitude(), pj.getZoomLevel()) : 0;
        if (accuracy > dotRadius * 1.5f) {
            canvas.drawCircle(x, y, accuracy, accuracyFill);
            canvas.drawCircle(x, y, accuracy, accuracyStroke);
        }

        float direction = azimuth;
        if (Float.isNaN(direction) && fix.hasBearing() && fix.getSpeed() > 1f)
            direction = fix.getBearing();
        if (!Float.isNaN(direction)) {
            float length = Math.max(30f * density, Math.min(accuracy, 70f * density));
            conePaint.setShader(new RadialGradient(x, y, length,
                    new int[]{Color.argb(200, 230, 20, 20), Color.argb(0, 230, 20, 20)}, null, Shader.TileMode.CLAMP));
            oval.set(x - length, y - length, x + length, y + length);
            // drawArc: 0° = east, clockwise; map north = -90°. The canvas already follows any map rotation.
            canvas.drawArc(oval, direction - 90f - CONE_HALF_ANGLE, 2 * CONE_HALF_ANGLE, true, conePaint);
            drawnAzimuth = direction;
        }

        canvas.drawCircle(x, y, dotRadius, dotFill);
        canvas.drawCircle(x, y, dotRadius, dotStroke);
    }

    // ------------------------------------------------------------------ compass

    @Override
    public boolean enableMyLocation(IMyLocationProvider provider) {
        boolean result = super.enableMyLocation(provider);
        if (rotationSensor != null && !sensorRegistered) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
            sensorRegistered = true;
        }
        return result;
    }

    @Override
    public void disableMyLocation() {
        super.disableMyLocation();
        if (sensorRegistered) {
            sensorManager.unregisterListener(this);
            sensorRegistered = false;
        }
        azimuth = Float.NaN;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values);
        int axisX, axisY;
        switch (windowManager.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:  axisX = SensorManager.AXIS_Y;       axisY = SensorManager.AXIS_MINUS_X; break;
            case Surface.ROTATION_180: axisX = SensorManager.AXIS_MINUS_X; axisY = SensorManager.AXIS_MINUS_Y; break;
            case Surface.ROTATION_270: axisX = SensorManager.AXIS_MINUS_Y; axisY = SensorManager.AXIS_X;       break;
            default:                   axisX = SensorManager.AXIS_X;       axisY = SensorManager.AXIS_Y;       break;
        }
        SensorManager.remapCoordinateSystem(rotation, axisX, axisY, remapped);
        SensorManager.getOrientation(remapped, orientation);

        Location fix = getLastFix();
        if (fix != null && fix != declinationFor) {
            declination = new GeomagneticField((float) fix.getLatitude(), (float) fix.getLongitude(),
                    (float) fix.getAltitude(), fix.getTime()).getDeclination();
            declinationFor = fix;
        }

        float measured = normalize((float) Math.toDegrees(orientation[0]) + declination);
        if (Float.isNaN(azimuth)) {
            azimuth = measured;
        } else {
            float diff = ((measured - azimuth + 540f) % 360f) - 180f;
            azimuth = normalize(azimuth + diff * 0.2f); // smoothing
        }

        long now = SystemClock.uptimeMillis();
        boolean changed = Float.isNaN(drawnAzimuth) || Math.abs(((azimuth - drawnAzimuth + 540f) % 360f) - 180f) > 1.5f;
        if (changed && now - lastInvalidate > 60 && fix != null) {
            lastInvalidate = now;
            mapView.postInvalidate();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static float normalize(float degrees) {
        degrees %= 360f;
        return degrees < 0 ? degrees + 360f : degrees;
    }
}
