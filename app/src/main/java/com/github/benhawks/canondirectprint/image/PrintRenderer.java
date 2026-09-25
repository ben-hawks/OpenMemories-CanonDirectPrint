package com.github.benhawks.canondirectprint.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

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
}
