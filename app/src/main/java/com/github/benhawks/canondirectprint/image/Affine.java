package com.github.benhawks.canondirectprint.image;

/**
 * 2D affine transform x' = a*x + b*y + c, y' = d*x + e*y + f.
 * Pure Java so the layout maths can be unit tested off-device.
 */
public final class Affine {
    public final double a, b, c, d, e, f;

    public Affine(double a, double b, double c, double d, double e, double f) {
        this.a = a; this.b = b; this.c = c;
        this.d = d; this.e = e; this.f = f;
    }

    public static Affine identity() { return new Affine(1, 0, 0, 0, 1, 0); }
    public static Affine translate(double tx, double ty) { return new Affine(1, 0, tx, 0, 1, ty); }
    public static Affine scale(double sx, double sy) { return new Affine(sx, 0, 0, 0, sy, 0); }

    /** Returns the transform that applies {@code this} first, then {@code next}. */
    public Affine then(Affine next) {
        return new Affine(
                next.a * a + next.b * d, next.a * b + next.b * e, next.a * c + next.b * f + next.c,
                next.d * a + next.e * d, next.d * b + next.e * e, next.d * c + next.e * f + next.f);
    }

    public double[] apply(double x, double y) {
        return new double[] { a * x + b * y + c, d * x + e * y + f };
    }

    /** Values in android.graphics.Matrix#setValues order. */
    public float[] toMatrixValues() {
        return new float[] { (float) a, (float) b, (float) c, (float) d, (float) e, (float) f, 0, 0, 1 };
    }

    @Override
    public String toString() {
        return "[" + a + " " + b + " " + c + "; " + d + " " + e + " " + f + "]";
    }
}
