/*
 * Temporary diagnostics for the Copy text patch, writes a log file to Documents.
 */

package app.morphe.extension.reddit.patches;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

final class CopyTextDebugLog {

    private static OutputStream stream;
    private static boolean failed;
    private static final Map<String, Integer> COUNTS = new HashMap<>();

    private CopyTextDebugLog() {
    }

    /**
     * Logs the first 40 messages of a key, then every 100th. For callers that run very often.
     */
    static void logLimited(String key, String message) {
        int count;
        synchronized (COUNTS) {
            Integer previous = COUNTS.get(key);
            count = previous == null ? 1 : previous + 1;
            COUNTS.put(key, count);
        }
        if (count <= 40 || count % 100 == 0) log("[" + key + " #" + count + "] " + message);
    }

    static void logStack(String message, int frames) {
        StringBuilder builder = new StringBuilder(message);
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (int i = 2; i < stack.length && i < 2 + frames; i++) {
            builder.append("\n    at ").append(stack[i]);
        }
        log(builder.toString());
    }

    static synchronized void log(String message) {
        if (failed) return;
        try {
            if (stream == null) {
                Context context = Utils.getContext();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                        "morphe_copy_text_debug_" + System.currentTimeMillis() + ".txt");
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS);
                Uri uri = context.getContentResolver().insert(
                        MediaStore.Files.getContentUri("external"), values);
                if (uri == null) {
                    failed = true;
                    return;
                }
                stream = context.getContentResolver().openOutputStream(uri, "wa");
            }
            String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " " + message + "\n";
            stream.write(line.getBytes(StandardCharsets.UTF_8));
            stream.flush();
        } catch (Exception ex) {
            failed = true;
            Logger.printException(() -> "CopyTextDebugLog failure", ex);
        }
    }
}
