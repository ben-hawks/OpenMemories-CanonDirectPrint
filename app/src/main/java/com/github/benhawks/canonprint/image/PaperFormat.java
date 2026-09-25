package com.github.benhawks.canonprint.image;

/**
 * Describes how a photo has to be rendered for a printer.
 *
 * The photo is laid out on a logical "paper" canvas (which fixes the aspect
 * ratio of the sheet), and that canvas is then stretched to the output bitmap
 * size the printer expects, optionally rotated by 180 degrees.
 */
public final class PaperFormat {
    /**
     * Canon Ivy 2, 2x3" Zink. Following dtgreene/ivy2 image.py: 1280x1920 (2:3)
     * canvas, squashed to 640x1616 and turned upside down.
     */
    public static final PaperFormat IVY2 = new PaperFormat("Ivy 2 (2x3\")", 1280, 1920, 640, 1616, true);

    /**
     * Canon SELPHY, postcard / 4x6" (100x148 mm). 1808x1232 is the printer's
     * native raster including the borderless bleed (CPNP plane header). The
     * printer decodes and scales the JPEG itself.
     */
    public static final PaperFormat SELPHY_POSTCARD = new PaperFormat("SELPHY postcard (4x6\")", 1808, 1232, 1808, 1232, false);

    public final String name;
    public final int paperWidth;
    public final int paperHeight;
    public final int outputWidth;
    public final int outputHeight;
    public final boolean rotate180;

    public PaperFormat(String name, int paperWidth, int paperHeight, int outputWidth, int outputHeight, boolean rotate180) {
        this.name = name;
        this.paperWidth = paperWidth;
        this.paperHeight = paperHeight;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.rotate180 = rotate180;
    }

    public boolean isLandscape() {
        return paperWidth > paperHeight;
    }

    /** Same format with a different output bitmap size (e.g. to save memory). */
    public PaperFormat withOutputSize(int width, int height) {
        return new PaperFormat(name, paperWidth, paperHeight, width, height, rotate180);
    }
}
