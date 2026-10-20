package com.applikationsprogramvara.osmviewer;

import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.MapTileIndex;

/** Thunderforest maps (OpenCycleMap): tile URLs need "?apikey=..." (free key on thunderforest.com). */
public class ThunderforestTileSource extends XYTileSource {

    /** set from the settings; empty = tiles come with an "API key required" watermark */
    static String apiKey = "";

    public ThunderforestTileSource(String name, String style) {
        super(name, 0, 22, 256, ".png",
                new String[]{"https://tile.thunderforest.com/" + style + "/"},
                "Karten © Thunderforest, Daten © OpenStreetMap-Mitwirkende");
    }

    @Override
    public String getTileURLString(long pMapTileIndex) {
        return getBaseUrl() + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex) + "/"
                + MapTileIndex.getY(pMapTileIndex) + imageFilenameEnding()
                + (apiKey.isEmpty() ? "" : "?apikey=" + apiKey);
    }
}
