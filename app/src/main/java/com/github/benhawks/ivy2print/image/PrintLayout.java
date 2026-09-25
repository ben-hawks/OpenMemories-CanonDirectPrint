package com.github.benhawks.ivy2print.image;

/**
 * Computes where a photo lands on the 2x3" Zink sheet.
 *
 * The printer expects a 640x1616 JPEG rotated by 180 degrees. Following the
 * reference implementation (dtgreene/ivy2 image.py) the photo is first laid
 * out on a 1280x1920 (2:3) portrait "paper" canvas, which is then squashed to
 * 640x1616. This class produces a single affine transform from source pixels
 * straight to output pixels, so the camera only needs one Bitmap draw.
 */
public final class PrintLayout {
    public enum Mode {
        /** Scale to cover the whole sheet, cropping the overflow (borderless). */
        FILL,
        /** Scale to fit inside the sheet, adding white borders. */
        FIT
    }

    public static final int PAPER_WIDTH = 1280;
    public static final int PAPER_HEIGHT = 1920;
    public static final int PRINT_WIDTH = 640;
    public static final int PRINT_HEIGHT = 1616;

    private PrintLayout() {}

    /**
     * Total rotation (clockwise degrees) to apply to the decoded source.
     *
     * @param exifDegrees rotation needed to display the image upright
     * @param autoRotate  turn landscape photos sideways so they use the whole portrait sheet
     */
    public static int rotation(int srcWidth, int srcHeight, int exifDegrees, boolean autoRotate) {
        int r = ((exifDegrees % 360) + 360) % 360;
        boolean upright90 = r == 90 || r == 270;
        int w = upright90 ? srcHeight : srcWidth;
        int h = upright90 ? srcWidth : srcHeight;
        if (autoRotate && w > h)
            r = (r + 90) % 360;
        return r;
    }

    /** Maps source pixels onto the 2:3 paper canvas of size PAPER_WIDTH x PAPER_HEIGHT. */
    public static Affine toPaper(int srcWidth, int srcHeight, int rotation, Mode mode) {
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
        double sx = (double) PAPER_WIDTH / rw;
        double sy = (double) PAPER_HEIGHT / rh;
        double s = mode == Mode.FILL ? Math.max(sx, sy) : Math.min(sx, sy);
        return t.then(Affine.scale(s, s))
                .then(Affine.translate((PAPER_WIDTH - rw * s) / 2, (PAPER_HEIGHT - rh * s) / 2));
    }

    /** Source pixels to the final printer bitmap (640x1616, upside down). */
    public static Affine toPrinter(int srcWidth, int srcHeight, int rotation, Mode mode) {
        return toPaper(srcWidth, srcHeight, rotation, mode)
                .then(Affine.scale((double) PRINT_WIDTH / PAPER_WIDTH, (double) PRINT_HEIGHT / PAPER_HEIGHT))
                .then(new Affine(-1, 0, PRINT_WIDTH, 0, -1, PRINT_HEIGHT));
    }

    /** Source pixels to an upright on-screen preview of the sheet of the given size. */
    public static Affine toPreview(int srcWidth, int srcHeight, int rotation, Mode mode, int width, int height) {
        return toPaper(srcWidth, srcHeight, rotation, mode)
                .then(Affine.scale((double) width / PAPER_WIDTH, (double) height / PAPER_HEIGHT));
    }

    /**
     * Largest power-of-two BitmapFactory sample size that still gives at least
     * printer resolution, to keep memory use low on the camera.
     */
    public static int sampleSize(int srcWidth, int srcHeight, int rotation, Mode mode) {
        Affine t = toPrinter(srcWidth, srcHeight, rotation, mode);
        // Largest scale factor along either output axis (pixels out per pixel in).
        double k = Math.max(Math.hypot(t.a, t.d), Math.hypot(t.b, t.e));
        int sample = 1;
        while (k * sample * 2 <= 1.0)
            sample *= 2;
        return sample;
    }
}
