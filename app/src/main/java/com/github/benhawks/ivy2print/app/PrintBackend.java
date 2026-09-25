package com.github.benhawks.ivy2print.app;

import android.content.Context;

import com.github.benhawks.ivy2print.image.PrintLayout;

import java.io.IOException;

/** Connects to one kind of printer and prints a rendered JPEG on it. Blocking. */
public abstract class PrintBackend {
    public interface Listener {
        void onStatus(String message);
        void onProgress(int value, int max);
    }

    public static PrintBackend create(Context context, AppSettings settings) {
        switch (settings.printerType) {
            case SELPHY:
                return new SelphyBackend(context, settings);
            case IVY2:
            default:
                return new Ivy2Backend(context, settings);
        }
    }

    /** Prints one copy. */
    public abstract void print(byte[] jpeg, PrintLayout.Mode mode, String jobName, Listener listener) throws IOException;

    /** Multi-line status for the printer screen. */
    public abstract String describe(Listener listener) throws IOException;

    /** True if a copy must not be sent until the previous one has physically printed. */
    public abstract boolean needsPauseBetweenCopies();

    /** Aborts a running operation from another thread. */
    public abstract void cancel();
}
