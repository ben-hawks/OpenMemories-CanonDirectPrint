package com.github.benhawks.canondirectprint.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Turns a photo into the bitmap / JPEG the Ivy 2 expects. */
public final class PrintRenderer {
    public interface StreamSource {
        /** Returns a fresh stream of the encoded source image on every call. */
        InputStream open() throws IOException;
    }

    public static final int DEFAULT_JPEG_QUALITY = 95;

    private PrintRenderer() {}

    public static int exifDegrees(int exifOrientation) {
        switch (exifOrientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: return 90;
            case ExifInterface.ORIENTATION_ROTATE_180: return 180;
            case ExifInterface.ORIENTATION_ROTATE_270: return 270;
            default: return 0;
        }
    }

    /** Returns {width, height} of the encoded image. */
    public static int[] decodeBounds(StreamSource source) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        InputStream in = source.open();
        try {
            BitmapFactory.decodeStream(in, null, options);
        } finally {
            in.close();
        }
        if (options.outWidth <= 0 || options.outHeight <= 0)
            throw new IOException("Cannot decode image");
        return new int[] { options.outWidth, options.outHeight };
    }

    public static Bitmap decode(StreamSource source, int sampleSize) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        InputStream in = source.open();
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (bitmap == null)
                throw new IOException("Cannot decode image");
            return bitmap;
        } finally {
            in.close();
        }
    }

    /**
     * Decodes the source at the smallest size that still gives full print
     * resolution. The returned bitmap should be recycled by the caller.
     */
    public static Bitmap decodeForPrint(StreamSource source, int exifDegrees, boolean autoRotate, PrintLayout.Mode mode, PaperFormat paper) throws IOException {
        int[] size = decodeBounds(source);
        int rotation = PrintLayout.rotation(size[0], size[1], exifDegrees, autoRotate, paper);
        return decode(source, PrintLayout.sampleSize(size[0], size[1], rotation, mode, paper));
    }

    private static Bitmap render(Bitmap src, Affine transform, int width, int height, Bitmap.Config config) {
        Bitmap out = Bitmap.createBitmap(width, height, config);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.WHITE);
        Matrix matrix = new Matrix();
        matrix.setValues(transform.toMatrixValues());
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(src, matrix, paint);
        return out;
    }

    /** Upright preview of the sheet at the given size (which should match the paper's aspect ratio). */
    public static Bitmap renderPreview(Bitmap src, int exifDegrees, boolean autoRotate, PrintLayout.Mode mode, PaperFormat paper, int width, int height) {
        int rotation = PrintLayout.rotation(src.getWidth(), src.getHeight(), exifDegrees, autoRotate, paper);
        return render(src, PrintLayout.toPreview(src.getWidth(), src.getHeight(), rotation, mode, paper, width, height),
                width, height, Bitmap.Config.ARGB_8888);
    }

    /**
     * Printer-ready JPEG of paper.outputWidth x paper.outputHeight.
     * Falls back to a 16-bit bitmap if the camera runs out of memory.
     */
    public static byte[] renderForPrinter(Bitmap src, int exifDegrees, boolean autoRotate, PrintLayout.Mode mode, PaperFormat paper, int jpegQuality) {
        int rotation = PrintLayout.rotation(src.getWidth(), src.getHeight(), exifDegrees, autoRotate, paper);
        Affine transform = PrintLayout.toPrinter(src.getWidth(), src.getHeight(), rotation, mode, paper);
        Bitmap out;
        try {
            out = render(src, transform, paper.outputWidth, paper.outputHeight, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            System.gc();
            out = render(src, transform, paper.outputWidth, paper.outputHeight, Bitmap.Config.RGB_565);
        }
        try {
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(512 * 1024);
            out.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpeg);
            return jpeg.toByteArray();
        } finally {
            out.recycle();
        }
    }

    /** Source rows (before subsampling) decoded per strip. */
    private static final int STRIP_ROWS = 256;

    /**
     * Printer-ready JPEG rendered from the full-resolution photo.
     *
     * A 24 MP image does not fit in the camera's app memory, so it is decoded
     * in horizontal strips with BitmapRegionDecoder (subsampled as far as the
     * printer's resolution allows) and each strip is drawn straight into the
     * output bitmap. Memory use is the output bitmap plus one strip.
     *
     * @throws IOException or OutOfMemoryError if the image cannot be decoded
     */
    public static byte[] renderForPrinterFromFullImage(StreamSource source, int exifDegrees, boolean autoRotate,
                                                       PrintLayout.Mode mode, PaperFormat paper, int jpegQuality) throws IOException {
        int[] size = decodeBounds(source);
        int width = size[0], height = size[1];
        int rotation = PrintLayout.rotation(width, height, exifDegrees, autoRotate, paper);
        int sample = PrintLayout.sampleSize(width, height, rotation, mode, paper);
        Affine toOutput = PrintLayout.toPrinter(width, height, rotation, mode, paper);
        List<PrintLayout.Strip> strips = PrintLayout.planStrips(width, height, toOutput,
                paper.outputWidth, paper.outputHeight, STRIP_ROWS, 2 * sample);

        BitmapRegionDecoder decoder;
        InputStream in = source.open();
        try {
            decoder = BitmapRegionDecoder.newInstance(in, false);
        } finally {
            in.close();
        }
        if (decoder == null)
            throw new IOException("Full image cannot be decoded in parts");

        Bitmap out = null;
        try {
            out = Bitmap.createBitmap(paper.outputWidth, paper.outputHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Matrix matrix = new Matrix();
            for (PrintLayout.Strip strip : strips) {
                Bitmap tile = decoder.decodeRegion(new Rect(0, strip.decodeTop, width, strip.decodeBottom), options);
                if (tile == null)
                    throw new IOException("Decoding rows " + strip.decodeTop + "-" + strip.decodeBottom + " failed");
                try {
                    // Tile pixels -> full-resolution source pixels -> output pixels.
                    Affine tileToOutput = Affine.scale((double) width / tile.getWidth(),
                                    (double) (strip.decodeBottom - strip.decodeTop) / tile.getHeight())
                            .then(Affine.translate(0, strip.decodeTop))
                            .then(toOutput);
                    matrix.setValues(tileToOutput.toMatrixValues());
                    canvas.save();
                    canvas.clipRect(strip.left, strip.top, strip.right, strip.bottom);
                    canvas.drawBitmap(tile, matrix, paint);
                    canvas.restore();
                } finally {
                    tile.recycle();
                }
            }
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(1024 * 1024);
            out.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpeg);
            return jpeg.toByteArray();
        } finally {
            decoder.recycle();
            if (out != null)
                out.recycle();
        }
    }
}
