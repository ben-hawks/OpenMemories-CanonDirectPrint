package com.github.benhawks.ivy2print.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.github.benhawks.ivy2print.image.PrintLayout;
import com.github.benhawks.ivy2print.ivy2.Ivy2Printer;
import com.github.benhawks.ivy2print.ivy2.TcpConnection;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

/**
 * User settings. Print options are remembered in SharedPreferences; the
 * advanced options can be set in IVY2PRNT/CONFIG.TXT on the memory card
 * (typing on the camera is painful), e.g.:
 * <pre>
 * bridge_host=192.168.4.1
 * bridge_port=9100
 * jpeg_quality=95
 * chunk_delay_ms=20
 * </pre>
 */
public class AppSettings {
    private static final String PREFS = "settings";

    public PrintLayout.Mode mode = PrintLayout.Mode.FILL;
    public boolean autoRotate = true;

    /** Fixed bridge address, or null to auto-detect. */
    public String bridgeHost = null;
    public int bridgePort = TcpConnection.DEFAULT_PORT;
    public int jpegQuality = 95;
    public int chunkDelayMs = Ivy2Printer.DEFAULT_CHUNK_DELAY_MS;
    /** Last bridge that worked, tried first next time. */
    public String lastBridgeHost = null;
    public int lastBridgePort = TcpConnection.DEFAULT_PORT;

    public static File getConfigFile() {
        return new File(Logger.getDirectory(), "CONFIG.TXT");
    }

    public static AppSettings load(Context context) {
        AppSettings s = new AppSettings();
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            s.mode = PrintLayout.Mode.valueOf(p.getString("mode", s.mode.name()));
        } catch (IllegalArgumentException e) {
            // keep default
        }
        s.autoRotate = p.getBoolean("autoRotate", s.autoRotate);
        s.lastBridgeHost = p.getString("lastBridgeHost", null);
        s.lastBridgePort = p.getInt("lastBridgePort", s.lastBridgePort);
        s.readConfigFile();
        return s;
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("mode", mode.name())
                .putBoolean("autoRotate", autoRotate)
                .putString("lastBridgeHost", lastBridgeHost)
                .putInt("lastBridgePort", lastBridgePort)
                .apply();
    }

    private void readConfigFile() {
        File file = getConfigFile();
        if (!file.exists())
            return;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    int eq = line.indexOf('=');
                    if (line.startsWith("#") || eq < 0)
                        continue;
                    apply(line.substring(0, eq).trim().toLowerCase(Locale.US), line.substring(eq + 1).trim());
                }
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            Logger.error("Cannot read " + file, e);
        }
    }

    private void apply(String key, String value) {
        try {
            if (key.equals("bridge_host"))
                bridgeHost = value.length() > 0 ? value : null;
            else if (key.equals("bridge_port"))
                bridgePort = Integer.parseInt(value);
            else if (key.equals("jpeg_quality"))
                jpegQuality = Math.max(50, Math.min(100, Integer.parseInt(value)));
            else if (key.equals("chunk_delay_ms"))
                chunkDelayMs = Math.max(0, Integer.parseInt(value));
            else
                Logger.info("Unknown config key " + key);
        } catch (NumberFormatException e) {
            Logger.error("Invalid value for " + key + ": " + value);
        }
    }
}
