package com.applikationsprogramvara.osmviewer;

import android.content.Context;
import android.util.AttributeSet;

import org.metalev.multitouch.controller.MultiTouchController.PointInfo;
import org.osmdroid.views.MapView;

/**
 * MapView whose pinch zoom snaps to a grid of zoom levels "n + offset" instead of whole numbers.
 *
 * osmdroid always draws the tiles of level floor(zoom). With offset 0 (normal) zooming out a little shows
 * the next coarser level enlarged. With an offset just below 1 the resting zoom is n + 0.99: zooming out
 * a little then keeps the finer level n and only shows it smaller.
 */
public class SnapMapView extends MapView {

    private double zoomOffset = 0;

    public SnapMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setZoomRounding(false); // rounding is done here, on the grid
    }

    public void setZoomOffset(double offset) {
        zoomOffset = offset;
    }

    public double getZoomOffset() {
        return zoomOffset;
    }

    /** nearest resting zoom level */
    public double snap(double zoom) {
        return Math.round(zoom - zoomOffset) + zoomOffset;
    }

    /** level of the tiles that are drawn at a resting zoom */
    public int tileLevel() {
        return (int) Math.floor(getZoomLevelDouble() + 1e-6);
    }

    @Override
    public void selectObject(Object obj, PointInfo pt) {
        super.selectObject(obj, pt);
        if (obj == null) { // pinch finished
            double z = getZoomLevelDouble();
            double s = snap(z);
            if (Math.abs(s - z) > 1e-6)
                getController().setZoom(s);
        }
    }
}
