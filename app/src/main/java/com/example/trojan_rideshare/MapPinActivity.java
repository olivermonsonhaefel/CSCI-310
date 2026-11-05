package com.example.trojan_rideshare;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Interactive Mapbox map. The first pin is the starting point and the second pin is the destination.
 */
public class MapPinActivity extends AppCompatActivity {

    public static final String EXTRA_ORIGIN_NAME = "originName";
    public static final String EXTRA_ORIGIN_LAT = "originLat";
    public static final String EXTRA_ORIGIN_LON = "originLon";
    public static final String EXTRA_DESTINATION_NAME = "destinationName";
    public static final String EXTRA_DESTINATION_LAT = "destinationLat";
    public static final String EXTRA_DESTINATION_LON = "destinationLon";

    private static final String STATE_HAS_ORIGIN = "hasOrigin";
    private static final String STATE_HAS_DESTINATION = "hasDestination";
    private static final String STATE_ORIGIN_LAT = "originLat";
    private static final String STATE_ORIGIN_LON = "originLon";
    private static final String STATE_DESTINATION_LAT = "destinationLat";
    private static final String STATE_DESTINATION_LON = "destinationLon";

    private final MapboxGeocoder geocoder = new MapboxGeocoder();

    private WebView mapWeb;
    private TextView tvMapPrompt;
    private MaterialButton btnUsePlaces;
    private ProgressBar progressBar;
    private boolean hasOrigin;
    private boolean hasDestination;
    private boolean mapReady;
    private boolean pageStarted;
    private double originLat;
    private double originLon;
    private double destinationLat;
    private double destinationLon;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenInsets.enable(this);
        setContentView(R.layout.activity_map_pin);
        ScreenInsets.pad(findViewById(R.id.mapPinRoot));

        if (savedInstanceState != null) {
            hasOrigin = savedInstanceState.getBoolean(STATE_HAS_ORIGIN);
            hasDestination = savedInstanceState.getBoolean(STATE_HAS_DESTINATION);
            originLat = savedInstanceState.getDouble(STATE_ORIGIN_LAT);
            originLon = savedInstanceState.getDouble(STATE_ORIGIN_LON);
            destinationLat = savedInstanceState.getDouble(STATE_DESTINATION_LAT);
            destinationLon = savedInstanceState.getDouble(STATE_DESTINATION_LON);
        }

        tvMapPrompt = findViewById(R.id.tvMapPrompt);
        mapWeb = findViewById(R.id.mapWeb);
        btnUsePlaces = findViewById(R.id.btnUsePlaces);
        MaterialButton btnClearPins = findViewById(R.id.btnClearPins);
        progressBar = findViewById(R.id.progressBar);
        updatePrompt();

        btnClearPins.setOnClickListener(v -> mapWeb.evaluateJavascript("clearPins()", null));
        btnUsePlaces.setOnClickListener(v -> usePins());

        if (BuildConfig.MAPBOX_TOKEN == null || BuildConfig.MAPBOX_TOKEN.isEmpty()) {
            showMapUnavailable();
            return;
        }

        mapWeb.getSettings().setJavaScriptEnabled(true);
        mapWeb.getSettings().setDomStorageEnabled(true);
        mapWeb.addJavascriptInterface(new MapBridge(), "AndroidMap");
        mapWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (pageStarted) {
                    return;
                }
                pageStarted = true;
                view.evaluateJavascript("startMap('" + jsString(BuildConfig.MAPBOX_TOKEN) + "')", null);
            }
        });

        try {
            mapWeb.loadDataWithBaseURL("https://api.mapbox.com", readMapHtml(), "text/html", "UTF-8", null);
        } catch (IOException e) {
            showMapUnavailable();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_HAS_ORIGIN, hasOrigin);
        outState.putBoolean(STATE_HAS_DESTINATION, hasDestination);
        outState.putDouble(STATE_ORIGIN_LAT, originLat);
        outState.putDouble(STATE_ORIGIN_LON, originLon);
        outState.putDouble(STATE_DESTINATION_LAT, destinationLat);
        outState.putDouble(STATE_DESTINATION_LON, destinationLon);
    }

    @Override
    protected void onDestroy() {
        if (mapWeb != null) {
            mapWeb.destroy();
        }
        super.onDestroy();
    }

    private void onMapReady() {
        mapReady = true;
        if (!hasOrigin) {
            return;
        }
        String script = String.format(Locale.US, "showPins(%f, %f, %s, %f, %f)",
                originLat, originLon, hasDestination ? "true" : "false", destinationLat, destinationLon);
        mapWeb.evaluateJavascript(script, null);
    }

    private void usePins() {
        if (!hasOrigin || !hasDestination) {
            return;
        }
        setLoading(true);
        labelPin(originLat, originLon, origin ->
                labelPin(destinationLat, destinationLon, destination -> deliver(origin, destination)));
    }

    private void labelPin(double lat, double lon, Consumer<Location> onReady) {
        geocoder.reverseLookUp(lat, lon,
                named -> onReady.accept(named != null ? named : unnamedPin(lat, lon)),
                error -> onReady.accept(unnamedPin(lat, lon)));
    }

    private void deliver(Location origin, Location destination) {
        Intent data = new Intent();
        data.putExtra(EXTRA_ORIGIN_NAME, origin.getPOIName());
        data.putExtra(EXTRA_ORIGIN_LAT, origin.getLat());
        data.putExtra(EXTRA_ORIGIN_LON, origin.getLon());
        data.putExtra(EXTRA_DESTINATION_NAME, destination.getPOIName());
        data.putExtra(EXTRA_DESTINATION_LAT, destination.getLat());
        data.putExtra(EXTRA_DESTINATION_LON, destination.getLon());
        setResult(RESULT_OK, data);
        finish();
    }

    private void updatePrompt() {
        if (!hasOrigin) {
            tvMapPrompt.setText(R.string.map_prompt_origin);
            btnUsePlaces.setEnabled(false);
        } else if (!hasDestination) {
            tvMapPrompt.setText(R.string.map_prompt_destination);
            btnUsePlaces.setEnabled(false);
        } else {
            tvMapPrompt.setText(R.string.map_prompt_ready);
            btnUsePlaces.setEnabled(true);
        }
    }

    private void showMapUnavailable() {
        tvMapPrompt.setText(MapboxGeocoder.LOCATION_UNAVAILABLE);
        btnUsePlaces.setEnabled(false);
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnUsePlaces.setEnabled(!loading && hasOrigin && hasDestination);
    }

    private String readMapHtml() throws IOException {
        StringBuilder html = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("map_picker.html"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                html.append(line).append('\n');
            }
        }
        return html.toString();
    }

    private static Location unnamedPin(double lat, double lon) {
        return new Location(String.format(Locale.US, "Pinned location (%.5f, %.5f)", lat, lon), lat, lon);
    }

    private static String jsString(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private class MapBridge {
        @JavascriptInterface
        public void onMapReady() {
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    MapPinActivity.this.onMapReady();
                }
            });
        }

        @JavascriptInterface
        public void onMapError() {
            runOnUiThread(() -> {
                if (!mapReady) {
                    showMapUnavailable();
                }
            });
        }

        @JavascriptInterface
        public void onOriginSet(double lat, double lon) {
            runOnUiThread(() -> {
                hasOrigin = true;
                originLat = lat;
                originLon = lon;
                updatePrompt();
            });
        }

        @JavascriptInterface
        public void onDestinationSet(double lat, double lon) {
            runOnUiThread(() -> {
                hasDestination = true;
                destinationLat = lat;
                destinationLon = lon;
                updatePrompt();
            });
        }

        @JavascriptInterface
        public void onCleared() {
            runOnUiThread(() -> {
                hasOrigin = false;
                hasDestination = false;
                updatePrompt();
            });
        }
    }
}
