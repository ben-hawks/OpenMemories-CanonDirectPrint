package com.github.benhawks.canonprint.util;

/** Minimal logging hook so the protocol code stays free of Android dependencies. */
public final class PrintLog {
    public interface Sink {
        void log(String message);
    }

    private static volatile Sink sink;

    private PrintLog() {}

    public static void setSink(Sink s) { sink = s; }

    public static void log(String message) {
        Sink s = sink;
        if (s != null)
            s.log(message);
    }
}
