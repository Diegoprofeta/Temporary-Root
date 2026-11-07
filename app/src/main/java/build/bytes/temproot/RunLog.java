package build.bytes.temproot;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Console log persisted to disk so it survives a crash or kernel panic, plus the
 * kernel panic tail from /sys/fs/pstore, which only root can read.
 *
 * A run writes a start marker, and an end marker when the binary returns. A log
 * without the end marker means the last run died, so the next app launch tries
 * to pull the panic trace while it still has root.
 */
final class RunLog {
    private static final String NAME = "last-run.log";
    private static final String DISPLAY_NAME = "TemporaryRoot-log.txt";
    private static final String START_MARK = "=== run start";
    private static final String END_MARK = "=== run end";
    private static final String[] PSTORE = {
            "/sys/fs/pstore/console-ramoops-0",
            "/sys/fs/pstore/console-ramoops",
            "/sys/fs/pstore/dmesg-ramoops-0",
    };

    private static File cached;
    private static long lastExportSize;

    private RunLog() {
    }

    /** One log for the whole app, always in device-protected storage. */
    static synchronized File file(Context ctx) {
        if (cached == null) {
            cached = new File(ctx.createDeviceProtectedStorageContext().getFilesDir(), NAME);
        }
        return cached;
    }

    /**
     * Appends a run marker. The file is never wiped by a new run: a previous
     * run without its end marker means a crash or panic, and wiping it here
     * used to destroy the only evidence. History is trimmed from the front
     * instead so the file cannot grow forever.
     */
    static synchronized void start(Context ctx) {
        String prev = read(ctx);
        if (!prev.isEmpty() && !prev.startsWith("No saved log")) {
            if (prev.length() > 256 * 1024) {
                prev = "\n… older log trimmed\n" + prev.substring(prev.length() - 128 * 1024);
                write(ctx, prev);
            }
            if (unfinishedRun(ctx) != null) {
                append(ctx, "(previous run ended without a run-end marker - crash, panic or reboot)\n");
            }
        }
        append(ctx, START_MARK + " " + stamp() + " ===\n");
    }

    static synchronized void append(Context ctx, String text) {
        try (FileOutputStream out = new FileOutputStream(file(ctx), true)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            // Without fsync the log lives in the page cache; a kernel panic
            // (which is exactly when we need it) wipes unflushed writes.
            out.getFD().sync();
        } catch (Exception ignored) {
        }
        // A run can die mid-way (panic/reboot before finish()), so keep the
        // Download copy fresh enough to pull evidence without adb or root.
        if (file(ctx).length() - lastExportSize > 8 * 1024) export(ctx);
    }

    /** True when the text's last run block never reached its end marker. */
    private static boolean lastRunFinished(String text) {
        int i = text.lastIndexOf(START_MARK);
        return i < 0 || text.indexOf(END_MARK, i) >= 0;
    }

    /** Full log text when the most recent run never ended (crash/panic), else null. */
    static synchronized String unfinishedRun(Context ctx) {
        String text = read(ctx);
        if (text.isEmpty() || text.startsWith("No saved log")) return null;
        return lastRunFinished(text) ? null : text;
    }

    static synchronized void finish(Context ctx) {
        append(ctx, END_MARK + " " + stamp() + " ===\n");
        export(ctx);
    }

    static String read(Context ctx) {
        try {
            String text = new String(Files.readAllBytes(file(ctx).toPath()), StandardCharsets.UTF_8);
            return text.isEmpty() ? "No saved log yet." : text;
        } catch (Exception e) {
            return "No saved log yet.";
        }
    }

    /** Appends the kernel panic trace when the last run never wrote its end
     *  marker. Needs root; silently does nothing without it. */
    static void capturePanicIfNeeded(Context ctx) {
        if (unfinishedRun(ctx) == null) return;

        String trace = suCat();
        if (trace == null || trace.trim().isEmpty()) return;

        append(ctx, "\n=== kernel panic trace (pstore) ===\n" + trace + "\n");
        export(ctx);
    }

    /**
     * Copies the log to Download/TemporaryRoot/last-run.log so it can be pulled
     * without adb or root. Overwrites the previous copy.
     */
    static synchronized void export(Context ctx) {
        lastExportSize = file(ctx).length();
        String text = read(ctx);
        if (text.isEmpty() || text.startsWith("No saved log")) return;

        ContentResolver resolver = ctx.getContentResolver();
        String path = Environment.DIRECTORY_DOWNLOADS + "/TemporaryRoot";
        try {
            resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{DISPLAY_NAME, path + "/"});
        } catch (Exception ignored) {
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, DISPLAY_NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
        try {
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return;
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out != null) out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static String suCat() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + String.join(" ", PSTORE) + " 2>/dev/null"});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = reader.readLine()) != null; ) sb.append(line).append('\n');
            }
            return p.waitFor() == 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(Context ctx, String text) {
        try (FileOutputStream out = new FileOutputStream(file(ctx), false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception ignored) {
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
    }
}
