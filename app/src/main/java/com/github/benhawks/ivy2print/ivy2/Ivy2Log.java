package com.github.benhawks.ivy2print.ivy2;

/** Minimal logging hook so the protocol code stays free of Android dependencies. */
public final class Ivy2Log {
    public interface Sink {
        void log(String message);
    }

    private static volatile Sink sink;

    private Ivy2Log() {}

    public static void setSink(Sink s) { sink = s; }

    static void log(String message) {
        Sink s = sink;
        if (s != null)
            s.log(message);
    }
}
