package com.github.benhawks.canondirectprint.app;

import android.os.Environment;
import android.util.Log;

import com.github.ma1co.openmemories.framework.DateTime;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Appends to CDPRINT/LOG.TXT on the memory card (there is no logcat on the camera without adb). */
public final class Logger {
    private static final String TAG = "CanonDirectPrint";

    private Logger() {}

    public static File getDirectory() {
        return new File(Environment.getExternalStorageDirectory(), "CDPRINT");
    }

    public static File getFile() {
        return new File(getDirectory(), "LOG.TXT");
    }

    private static synchronized void log(String type, String msg) {
        Log.i(TAG, msg);
        try {
            File file = getFile();
            file.getParentFile().mkdirs();
            if (file.length() > 1024 * 1024)
                file.delete();
            BufferedWriter writer = new BufferedWriter(new FileWriter(file, true));
            try {
                writer.append(timestamp());
                writer.append(" [").append(type).append("] ").append(msg);
                writer.newLine();
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            // nothing we can do
        }
    }

    /**
     * The camera's own clock. Android's system clock is not set on these
     * cameras (it starts at 1970), so ask Sony's time API via the framework.
     */
    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        try {
            Calendar now = DateTime.getInstance().getCurrentTime();
            format.setTimeZone(now.getTimeZone());
            return format.format(now.getTime());
        } catch (Throwable t) {
            return format.format(new Date());
        }
    }

    public static void info(String msg) { log("INFO", msg); }
    public static void error(String msg) { log("ERROR", msg); }

    public static void error(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log("ERROR", msg + ": " + sw);
    }
}
