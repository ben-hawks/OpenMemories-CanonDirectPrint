package com.github.benhawks.ivy2print.image;

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
    public static Bitmap decodeForPrint(StreamSource source, int exifDegrees, boolean autoRotate, PrintLayout.Mode mode) throws IOException {
        int[] size = decodeBounds(source);
        int rotation = PrintLayout.rotation(size[0], size[1], exifDegrees, autoRotate);
        return decode(source, PrintLayout.sampleSize(size[0], size[1], rotation, mode));
    }

    private static Bitmap render(Bitmap src, Affine transform, int width, int height) {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.WHITE);
        Matrix matrix = new Matrix();
        matrix.setValues(transform.toMatrixValues());
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(src, matrix, paint);
        return out;
    }

    /** Upright preview of the sheet (2:3 portrait) at the given size. */
    public static Bitmap renderPreview(Bitmap src, int exifDegrees, boolean autoRotate, PrintLayout.Mode mode, int width, int height) {
        int rotation = PrintLayout.rotation(src.getWidth(), src.getHeight(), exifDegrees, autoRotate);
        return render(src, PrintLayout.toPreview(src.getWidth(), src.getHeight(), rotation, mode, width, height), width, height);
    }

    /** Printer-ready JPEG: 640x1616, rotated by 180 degrees. */
    public static byte[] renderForPrinter(Bitmap src, int exifDegrees, boolean autoRotate, PrintLayout.Mode mode, int jpegQuality) {
        int rotation = PrintLayout.rotation(src.getWidth(), src.getHeight(), exifDegrees, autoRotate);
        Bitmap out = render(src, PrintLayout.toPrinter(src.getWidth(), src.getHeight(), rotation, mode),
                PrintLayout.PRINT_WIDTH, PrintLayout.PRINT_HEIGHT);
        try {
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(512 * 1024);
            out.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpeg);
            return jpeg.toByteArray();
        } finally {
            out.recycle();
        }
    }
}
