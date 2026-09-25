package com.github.benhawks.canonprint.ivy2;

import java.io.IOException;

/** A printer-side problem that the user can fix (paper, cover, battery, ...). */
public class Ivy2Exception extends IOException {
    public enum Reason {
        LOW_BATTERY("Printer battery too low"),
        COVER_OPEN("Printer paper cover is open"),
        NO_PAPER("Printer is out of paper"),
        WRONG_SMART_SHEET("Wrong Smart Sheet loaded (insert the blue sheet)"),
        TIMEOUT("Printer did not respond"),
        PROTOCOL("Unexpected reply from printer"),
        DISCONNECTED("Connection to printer lost (is it on and in range of the bridge?)");

        public final String message;

        Reason(String message) { this.message = message; }
    }

    public final Reason reason;

    public Ivy2Exception(Reason reason) {
        this(reason, reason.message);
    }

    public Ivy2Exception(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
