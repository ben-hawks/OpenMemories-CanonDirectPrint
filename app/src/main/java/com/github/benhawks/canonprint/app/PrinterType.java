package com.github.benhawks.canonprint.app;

import com.github.benhawks.canonprint.image.PaperFormat;

/** The printers the app can drive. */
public enum PrinterType {
    /** Canon Ivy 2 (Zink 2x3"), Bluetooth only: needs a Wi-Fi/Bluetooth bridge. */
    IVY2("Canon Ivy 2 (via bridge)", PaperFormat.IVY2, 240, 360),
    /** Canon SELPHY CP900 and later Wi-Fi models, reached directly over Wi-Fi. */
    SELPHY("Canon SELPHY (Wi-Fi)", PaperFormat.SELPHY_POSTCARD, 360, 245);

    public final String label;
    public final PaperFormat paper;
    public final int previewWidth;
    public final int previewHeight;

    PrinterType(String label, PaperFormat paper, int previewWidth, int previewHeight) {
        this.label = label;
        this.paper = paper;
        this.previewWidth = previewWidth;
        this.previewHeight = previewHeight;
    }

    public PrinterType next() {
        PrinterType[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
