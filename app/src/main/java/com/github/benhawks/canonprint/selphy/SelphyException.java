package com.github.benhawks.canonprint.selphy;

import java.io.IOException;

/** A printer-side problem reported by a SELPHY (no paper, no ink, ...). */
public class SelphyException extends IOException {
    public SelphyException(String message) {
        super(message);
    }
}
