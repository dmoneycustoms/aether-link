package global.nscb.aether;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * SpiritScan-style session log — append-only trip events for AETHER.
 */
public final class SessionLogger {
    private static final Object LOCK = new Object();
    private static OutputStreamWriter out;
    private static File file;
    private static boolean open;

    private SessionLogger() {}

    public static void start(Context ctx) {
        synchronized (LOCK) {
            if (open) return;
            try {
                File dir = new File(ctx.getApplicationContext().getFilesDir(), "sessions");
                if (!dir.exists()) dir.mkdirs();
                SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
                fmt.setTimeZone(TimeZone.getDefault());
                file = new File(dir, "aether_" + fmt.format(new Date()) + ".log");
                out = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
                open = true;
                write("SESSION_START");
            } catch (Exception e) {
                open = false;
                out = null;
            }
        }
    }

    public static void line(String event, String detail) {
        synchronized (LOCK) {
            if (!open || out == null) return;
            try {
                SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
                out.write(fmt.format(new Date()));
                out.write("  ");
                out.write(event);
                if (detail != null && !detail.isEmpty()) {
                    out.write("  ");
                    out.write(detail.replace('\n', ' '));
                }
                out.write('\n');
                out.flush();
            } catch (Exception ignored) {
            }
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            if (!open) return;
            write("SESSION_END");
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
            out = null;
            open = false;
        }
    }

    public static String currentPath() {
        synchronized (LOCK) {
            return file == null ? "" : file.getAbsolutePath();
        }
    }

    private static void write(String event) {
        line(event, "");
    }
}
