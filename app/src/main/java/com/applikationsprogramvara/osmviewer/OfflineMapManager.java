package com.applikationsprogramvara.osmviewer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.format.Formatter;

import androidx.annotation.Nullable;

import org.osmdroid.util.BoundingBox;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages offline map archives (MBTiles, osmdroid SQLite, osmdroid ZIP, GEMF) stored in the
 * app-specific folder Android/data/&lt;package&gt;/files/maps. Files are imported via the system
 * file picker (copied into that folder) or can be copied there manually via USB.
 */
public class OfflineMapManager {

    public static final String[] SUPPORTED_EXTENSIONS = {".mbtiles", ".sqlite", ".zip", ".gemf"};

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context context;

    public OfflineMapManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public File getDirectory() {
        File dir = context.getExternalFilesDir("maps");
        if (dir == null) // external storage not available
            dir = new File(context.getFilesDir(), "maps");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public List<File> list() {
        File[] files = getDirectory().listFiles(f -> f.isFile() && isSupported(f.getName()));
        List<File> result = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            result.addAll(Arrays.asList(files));
        }
        return result;
    }

    public boolean hasMaps() {
        return !list().isEmpty();
    }

    @Nullable
    public File find(@Nullable String fileName) {
        if (fileName == null) return null;
        File f = new File(getDirectory(), fileName);
        return f.isFile() ? f : null;
    }

    public static boolean isSupported(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED_EXTENSIONS)
            if (lower.endsWith(ext)) return true;
        return false;
    }

    public static boolean isMBTiles(File f) {
        return f.getName().toLowerCase(Locale.ROOT).endsWith(".mbtiles");
    }

    /** Name without extension plus file size, for menus. */
    public String describe(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot + 1) : "";
        return base + "  (" + ext + ", " + Formatter.formatShortFileSize(context, f.length()) + ")";
    }

    // ---------------------------------------------------------------- import

    public interface ImportListener {
        void onProgress(long copied, long total); // total < 0 if unknown
        void onDone(File file);
        void onError(Exception e);
    }

    public static class UnsupportedFileException extends IOException {
        public UnsupportedFileException() { super("unsupported file type"); }
    }

    public static class NotEnoughSpaceException extends IOException {
        public final long needed;
        public NotEnoughSpaceException(long needed) { super("not enough space"); this.needed = needed; }
    }

    /**
     * Copies the picked document into the maps folder on a background thread.
     * Callbacks are delivered on the main thread. Set {@code cancel} to abort.
     */
    public void importFromUri(Uri uri, AtomicBoolean cancel, ImportListener listener) {
        EXECUTOR.execute(() -> {
            File part = null;
            try {
                ContentResolver cr = context.getContentResolver();
                String displayName = null;
                long size = -1;
                try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        if (!c.isNull(0)) displayName = c.getString(0);
                        if (!c.isNull(1)) size = c.getLong(1);
                    }
                }
                if (displayName == null) displayName = uri.getLastPathSegment();
                if (displayName == null) displayName = "offline-map";
                displayName = displayName.replaceAll("[\\\\/:*?\"<>|]", "_");

                File dir = getDirectory();
                if (size > 0 && dir.getUsableSpace() < size + 10L * 1024 * 1024)
                    throw new NotEnoughSpaceException(size);

                part = new File(dir, ".import.part");
                final long total = size;
                try (InputStream in = cr.openInputStream(uri);
                     OutputStream out = new FileOutputStream(part)) {
                    if (in == null) throw new IOException("cannot open file");
                    byte[] buf = new byte[256 * 1024];
                    long copied = 0;
                    long lastReport = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel.get()) throw new InterruptedException();
                        out.write(buf, 0, n);
                        copied += n;
                        long now = System.currentTimeMillis();
                        if (now - lastReport > 200) {
                            lastReport = now;
                            final long c = copied;
                            MAIN.post(() -> listener.onProgress(c, total));
                        }
                    }
                }

                // file name without a known extension (some file providers strip it): accept SQLite files as MBTiles
                if (!isSupported(displayName)) {
                    if (isSQLite(part)) displayName = displayName + ".mbtiles";
                    else throw new UnsupportedFileException();
                }

                File target = uniqueFile(dir, displayName);
                if (!part.renameTo(target)) throw new IOException("cannot rename imported file");
                MAIN.post(() -> listener.onDone(target));
            } catch (InterruptedException e) {
                if (part != null) //noinspection ResultOfMethodCallIgnored
                    part.delete();
            } catch (Exception e) {
                if (part != null) //noinspection ResultOfMethodCallIgnored
                    part.delete();
                MAIN.post(() -> listener.onError(e));
            }
        });
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            f = new File(dir, base + "-" + i + ext);
            if (!f.exists()) return f;
        }
    }

    private static boolean isSQLite(File f) {
        byte[] header = new byte[16];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(header) != 16) return false;
        } catch (IOException e) {
            return false;
        }
        return new String(header, 0, 15).equals("SQLite format 3");
    }

    // ---------------------------------------------------------------- tile size

    /**
     * Pixel width of the tiles in an MBTiles file (256 for normal tiles, 512 for "@2x" tiles).
     * Read from the first tile's image header; 256 for other formats or on errors.
     */
    public static int readTileSize(File file) {
        if (!isMBTiles(file))
            return 256;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor c = db.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null)) {
            if (c.moveToFirst()) {
                byte[] data = c.getBlob(0);
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (o.outWidth >= 64 && o.outWidth <= 2048)
                    return o.outWidth;
            }
        } catch (Exception ignored) {
        }
        return 256;
    }

    // ---------------------------------------------------------------- bounds

    public interface BoundsListener {
        void onBounds(@Nullable BoundingBox box);
    }

    /** Reads the covered area of an MBTiles file on a background thread (null for other formats). */
    public void readBounds(File file, BoundsListener listener) {
        EXECUTOR.execute(() -> {
            BoundingBox box = null;
            try {
                if (isMBTiles(file)) box = readMBTilesBounds(file);
            } catch (Exception ignored) {
            }
            final BoundingBox result = box;
            MAIN.post(() -> listener.onBounds(result));
        });
    }

    @Nullable
    private static BoundingBox readMBTilesBounds(File file) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            // 1) "bounds" metadata: left,bottom,right,top
            try (Cursor c = db.rawQuery("SELECT value FROM metadata WHERE name = 'bounds'", null)) {
                if (c.moveToFirst()) {
                    String[] p = c.getString(0).split(",");
                    if (p.length == 4) {
                        double w = Double.parseDouble(p[0].trim()), s = Double.parseDouble(p[1].trim());
                        double e = Double.parseDouble(p[2].trim()), n = Double.parseDouble(p[3].trim());
                        if (n > s && e > w) return new BoundingBox(n, e, s, w);
                    }
                }
            } catch (Exception ignored) {
                // no metadata table or malformed value, fall through
            }

            // 2) derive from tiles at a moderate zoom level (fast, uses the tiles index)
            int zoom = -1;
            try (Cursor c = db.rawQuery("SELECT MAX(zoom_level) FROM tiles WHERE zoom_level <= 12", null)) {
                if (c.moveToFirst() && !c.isNull(0)) zoom = c.getInt(0);
            }
            if (zoom < 0) {
                try (Cursor c = db.rawQuery("SELECT MIN(zoom_level) FROM tiles", null)) {
                    if (c.moveToFirst() && !c.isNull(0)) zoom = c.getInt(0);
                }
            }
            if (zoom < 0) return null;

            try (Cursor c = db.rawQuery("SELECT MIN(tile_column), MAX(tile_column), MIN(tile_row), MAX(tile_row) FROM tiles WHERE zoom_level = ?",
                    new String[]{Integer.toString(zoom)})) {
                if (!c.moveToFirst() || c.isNull(0)) return null;
                long n = 1L << zoom;
                long minX = c.getLong(0), maxX = c.getLong(1);
                // MBTiles uses TMS rows (origin bottom left), convert to XYZ
                long minY = n - 1 - c.getLong(3), maxY = n - 1 - c.getLong(2);
                return new BoundingBox(tileLat(minY, n), tileLon(maxX + 1, n), tileLat(maxY + 1, n), tileLon(minX, n));
            }
        }
    }

    private static double tileLon(long x, long n) {
        return x * 360.0 / n - 180.0;
    }

    private static double tileLat(long y, long n) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * y / n))));
    }
}
