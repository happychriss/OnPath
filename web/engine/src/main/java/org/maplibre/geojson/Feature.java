package org.maplibre.geojson;

import com.google.gson.JsonObject;

/** Web stand-in: poi/Poi.kt names MapLibre's Feature in a helper the web never calls (map taps are the page's own). */
public class Feature {
    public Object geometry() { return null; }
    public JsonObject properties() { return null; }
}
