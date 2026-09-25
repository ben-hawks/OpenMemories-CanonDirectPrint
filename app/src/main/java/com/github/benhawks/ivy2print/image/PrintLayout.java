package com.github.benhawks.ivy2print.image;

/**
 * Computes where a photo lands on the sheet.
 *
 * The photo is first laid out on the {@link PaperFormat}'s logical paper
 * canvas, which is then stretched to the printer's output size (and turned
 * upside down for the Ivy 2). This class produces a single affine transform
 * from source pixels straight to output pixels, so the camera only needs one
 * Bitmap draw.
 */
public final class PrintLayout {
    public enum Mode {
        /** Scale to cover the whole sheet, cropping the overflow (borderless). */
        FILL,
        /** Scale to fit inside the sheet, adding white borders. */
        FIT
    }

    private PrintLayout() {}

    /**
     * Total rotation (clockwise degrees) to apply to the decoded source.
     *
     * @param exifDegrees rotation needed to display the image upright
     * @param autoRotate  turn the photo sideways if its orientation does not match the paper
     */
    public static int rotation(int srcWidth, int srcHeight, int exifDegrees, boolean autoRotate, PaperFormat paper) {
        int r = ((exifDegrees % 360) + 360) % 360;
        boolean upright90 = r == 90 || r == 270;
        int w = upright90 ? srcHeight : srcWidth;
        int h = upright90 ? srcWidth : srcHeight;
        if (autoRotate && w != h && (w > h) != paper.isLandscape())
            r = (r + 90) % 360;
        return r;
    }

    /** Maps source pixels onto the paper canvas of size paperWidth x paperHeight. */
    public static Affine toPaper(int srcWidth, int srcHeight, int rotation, Mode mode, PaperFormat paper) {
        Affine t;
        int rw, rh;
        switch (rotation) {
            case 90:
                t = new Affine(0, -1, srcHeight, 1, 0, 0);
                rw = srcHeight; rh = srcWidth;
                break;
            case 180:
                t = new Affine(-1, 0, srcWidth, 0, -1, srcHeight);
                rw = srcWidth; rh = srcHeight;
                break;
            case 270:
                t = new Affine(0, 1, 0, -1, 0, srcWidth);
                rw = srcHeight; rh = srcWidth;
                break;
            case 0:
                t = Affine.identity();
                rw = srcWidth; rh = srcHeight;
                break;
            default:
                throw new IllegalArgumentException("Unsupported rotation " + rotation);
        }
        double sx = (double) paper.paperWidth / rw;
        double sy = (double) paper.paperHeight / rh;
        double s = mode == Mode.FILL ? Math.max(sx, sy) : Math.min(sx, sy);
        return t.then(Affine.scale(s, s))
                .then(Affine.translate((paper.paperWidth - rw * s) / 2, (paper.paperHeight - rh * s) / 2));
    }

    /** Source pixels to the final printer bitmap. */
    public static Affine toPrinter(int srcWidth, int srcHeight, int rotation, Mode mode, PaperFormat paper) {
        Affine t = toPaper(srcWidth, srcHeight, rotation, mode, paper)
                .then(Affine.scale((double) paper.outputWidth / paper.paperWidth, (double) paper.outputHeight / paper.paperHeight));
        if (paper.rotate180)
            t = t.then(new Affine(-1, 0, paper.outputWidth, 0, -1, paper.outputHeight));
        return t;
    }

    /** Source pixels to an upright on-screen preview of the sheet of the given size. */
    public static Affine toPreview(int srcWidth, int srcHeight, int rotation, Mode mode, PaperFormat paper, int width, int height) {
        return toPaper(srcWidth, srcHeight, rotation, mode, paper)
                .then(Affine.scale((double) width / paper.paperWidth, (double) height / paper.paperHeight));
    }

    /**
     * Largest power-of-two BitmapFactory sample size that still gives at least
     * printer resolution, to keep memory use low on the camera.
     */
    public static int sampleSize(int srcWidth, int srcHeight, int rotation, Mode mode, PaperFormat paper) {
        Affine t = toPrinter(srcWidth, srcHeight, rotation, mode, paper);
        // Largest scale factor along either output axis (pixels out per pixel in).
        double k = Math.max(Math.hypot(t.a, t.d), Math.hypot(t.b, t.e));
        int sample = 1;
        while (k * sample * 2 <= 1.0)
            sample *= 2;
        return sample;
    }
}
