package org.inaturalist.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import org.tinylog.Logger;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Looks up ground elevation (in meters) for a coordinate using the free AWS Terrain Tiles
 * ("Terrarium" encoding): https://registry.opendata.aws/terrain-tiles/
 *
 * Each pixel of a 256x256 PNG tile encodes the elevation as: (R * 256 + G + B / 256) - 32768
 *
 * Tiles are cached in memory. Once the tile under a point has been downloaded, the elevation of any
 * other point inside it is just a pixel read (see getCachedElevation) - so it can be done instantly,
 * on the main thread, e.g. while the user is dragging a marker around.
 */
public class ElevationUtils {
    private static final String TAG = "ElevationUtils";

    private static final String TILE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png";
    // ~19 m per pixel at the equator (each tile is ~5 km wide) - good balance between detail and download size
    private static final int ZOOM = 13;
    private static final int TILE_SIZE = 256;
    private static final double METERS_TO_FEET = 3.28084;

    public interface Callback {
        /** Called on the main thread. elevationMeters is null if the lookup failed */
        void onElevation(Double elevationMeters);
    }

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    // Enough for the tile under the user + its 8 neighbors, plus some extra (each tile is ~256 KB in memory)
    private static final LruCache<String, Bitmap> sTileCache = new LruCache<>(16);

    /** Looks up the elevation asynchronously (result is delivered on the main thread) */
    public static void getElevation(final double latitude, final double longitude, final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Double elevation = null;
                try {
                    elevation = getElevationBlocking(latitude, longitude);
                } catch (Exception e) {
                    Logger.tag(TAG).error(e);
                }

                // Warm up the cache around this point, so moving the point around is instant
                prefetchAround(latitude, longitude);

                final Double result = elevation;
                sMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onElevation(result);
                    }
                });
            }
        });
    }

    /**
     * Returns the elevation of a point if the tile it's in is already in memory - and null otherwise.
     * Never does any network access, so it's fast and safe to call from the main thread.
     */
    public static Double getCachedElevation(double latitude, double longitude) {
        if (!isInRange(latitude)) return null;

        double[] t = toTileCoordinates(latitude, longitude);
        Bitmap tile;
        synchronized (sTileCache) {
            tile = sTileCache.get(tileKey((int) t[0], (int) t[1]));
        }
        if (tile == null) return null;

        return elevationInTile(tile, t[2], t[3]);
    }

    /** Looks up the elevation synchronously (downloads the tile if needed) - must NOT be called on the main thread */
    public static Double getElevationBlocking(double latitude, double longitude) throws Exception {
        if (!isInRange(latitude)) return null;

        double[] t = toTileCoordinates(latitude, longitude);
        Bitmap tile = getTile((int) t[0], (int) t[1]);
        if (tile == null) return null;

        return elevationInTile(tile, t[2], t[3]);
    }

    /** Downloads (in the background) the tiles around a point, so they're ready if the point moves */
    private static void prefetchAround(double latitude, double longitude) {
        if (!isInRange(latitude)) return;

        double[] t = toTileCoordinates(latitude, longitude);
        int tilesCount = 1 << ZOOM;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int x = (((int) t[0] + dx) % tilesCount + tilesCount) % tilesCount; // Wrap around the antimeridian
                int y = (int) t[1] + dy;
                if ((y < 0) || (y >= tilesCount)) continue;

                try {
                    getTile(x, y);
                } catch (Exception e) {
                    Logger.tag(TAG).warn("Failed prefetching elevation tile: " + e);
                }
            }
        }
    }

    private static boolean isInRange(double latitude) {
        return (latitude >= -85.0511) && (latitude <= 85.0511); // Web mercator range
    }

    private static String tileKey(int x, int y) {
        return ZOOM + "/" + x + "/" + y;
    }

    // Returns { tile x, tile y, x position inside tile (0-256), y position inside tile (0-256) }
    private static double[] toTileCoordinates(double latitude, double longitude) {
        int tilesCount = 1 << ZOOM;
        double latRad = Math.toRadians(latitude);
        double tileXf = (longitude + 180.0) / 360.0 * tilesCount;
        double tileYf = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * tilesCount;

        int tileX = (int) Math.floor(tileXf);
        int tileY = (int) Math.floor(tileYf);
        double px = (tileXf - tileX) * TILE_SIZE;
        double py = (tileYf - tileY) * TILE_SIZE;

        tileX = ((tileX % tilesCount) + tilesCount) % tilesCount; // Wrap around the antimeridian
        tileY = Math.max(0, Math.min(tilesCount - 1, tileY));

        return new double[] { tileX, tileY, px, py };
    }

    // Bilinear interpolation between the 4 closest pixels
    private static double elevationInTile(Bitmap tile, double px, double py) {
        px -= 0.5;
        py -= 0.5;
        int x0 = (int) Math.floor(px);
        int y0 = (int) Math.floor(py);
        double fx = px - x0;
        double fy = py - y0;

        double e00 = pixelElevation(tile, x0, y0);
        double e10 = pixelElevation(tile, x0 + 1, y0);
        double e01 = pixelElevation(tile, x0, y0 + 1);
        double e11 = pixelElevation(tile, x0 + 1, y0 + 1);

        return (e00 * (1 - fx) * (1 - fy)) + (e10 * fx * (1 - fy)) + (e01 * (1 - fx) * fy) + (e11 * fx * fy);
    }

    private static double pixelElevation(Bitmap tile, int x, int y) {
        // Clamp to the tile's edges
        x = Math.max(0, Math.min(tile.getWidth() - 1, x));
        y = Math.max(0, Math.min(tile.getHeight() - 1, y));

        int pixel = tile.getPixel(x, y);
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;

        return (r * 256.0 + g + b / 256.0) - 32768.0;
    }

    private static Bitmap getTile(int x, int y) throws Exception {
        String key = tileKey(x, y);

        synchronized (sTileCache) {
            Bitmap cached = sTileCache.get(key);
            if (cached != null) return cached;
        }

        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            connection = (HttpURLConnection) new URL(String.format(Locale.ENGLISH, TILE_URL, ZOOM, x, y)).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Logger.tag(TAG).warn("Elevation tile request failed: " + connection.getResponseCode());
                return null;
            }

            input = connection.getInputStream();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888; // Must not lose precision / apply color conversion
            Bitmap tile = BitmapFactory.decodeStream(input, null, options);

            if (tile != null) {
                synchronized (sTileCache) {
                    sTileCache.put(key, tile);
                }
            }
            return tile;
        } finally {
            if (input != null) {
                try { input.close(); } catch (Exception e) { /* Ignore */ }
            }
            if (connection != null) connection.disconnect();
        }
    }

    public static long toFeet(double elevationMeters) {
        return Math.round(elevationMeters * METERS_TO_FEET);
    }

    /** Formats an elevation in both units for display, e.g. "1,234 m (4,049 ft)" */
    public static String format(double elevationMeters) {
        return String.format(Locale.getDefault(), "%,d m (%,d ft)", Math.round(elevationMeters), toFeet(elevationMeters));
    }

    /** Plain version of format(), used when copying to the clipboard, e.g. "1234 m / 4049 ft" */
    public static String formatPlain(double elevationMeters) {
        return String.format(Locale.ENGLISH, "%d m / %d ft", Math.round(elevationMeters), toFeet(elevationMeters));
    }
}
