package com.github.benhawks.ivy2print.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import static com.github.benhawks.ivy2print.image.PaperFormat.IVY2;
import static com.github.benhawks.ivy2print.image.PaperFormat.SELPHY_POSTCARD;

public class PrintLayoutTest {
    private static final double EPS = 1e-6;

    private static void assertPoint(double x, double y, double[] p) {
        assertEquals("x", x, p[0], EPS);
        assertEquals("y", y, p[1], EPS);
    }

    @Test
    public void rotationTurnsLandscapeSideways() {
        assertEquals(90, PrintLayout.rotation(1616, 1080, 0, true, IVY2));
        assertEquals(0, PrintLayout.rotation(1616, 1080, 0, false, IVY2));
        assertEquals(0, PrintLayout.rotation(1080, 1616, 0, true, IVY2));
        // Portrait photo stored landscape with an EXIF rotation stays upright.
        assertEquals(90, PrintLayout.rotation(1616, 1080, 90, true, IVY2));
        assertEquals(270, PrintLayout.rotation(1616, 1080, 270, true, IVY2));
        assertEquals(180, PrintLayout.rotation(1080, 1616, 180, true, IVY2));
    }

    @Test
    public void fitPortraitTwoByThreeCoversPaperExactly() {
        Affine t = PrintLayout.toPaper(1080, 1620, 0, PrintLayout.Mode.FIT, IVY2);
        assertPoint(0, 0, t.apply(0, 0));
        assertPoint(1280, 1920, t.apply(1080, 1620));
    }

    @Test
    public void rotatedLandscapeFillsPaper() {
        // 3:2 landscape turned 90 degrees clockwise becomes 2:3 portrait.
        Affine t = PrintLayout.toPaper(1620, 1080, 90, PrintLayout.Mode.FILL, IVY2);
        assertPoint(1280, 0, t.apply(0, 0));      // top-left goes to top-right
        assertPoint(0, 1920, t.apply(1620, 1080)); // bottom-right goes to bottom-left
    }

    @Test
    public void fillCropsAndFitLetterboxes() {
        // Square image on a 2:3 sheet.
        Affine fill = PrintLayout.toPaper(1000, 1000, 0, PrintLayout.Mode.FILL, IVY2);
        assertPoint(-(1920 - 1280) / 2.0, 0, fill.apply(0, 0));
        assertPoint(1280 + (1920 - 1280) / 2.0, 1920, fill.apply(1000, 1000));

        Affine fit = PrintLayout.toPaper(1000, 1000, 0, PrintLayout.Mode.FIT, IVY2);
        assertPoint(0, (1920 - 1280) / 2.0, fit.apply(0, 0));
        assertPoint(1280, 1920 - (1920 - 1280) / 2.0, fit.apply(1000, 1000));
    }

    @Test
    public void printerOutputIsSquashedAndUpsideDown() {
        Affine t = PrintLayout.toPrinter(1080, 1620, 0, PrintLayout.Mode.FIT, IVY2);
        assertPoint(640, 1616, t.apply(0, 0));
        assertPoint(0, 0, t.apply(1080, 1620));
        assertPoint(320, 808, t.apply(540, 810));
    }

    @Test
    public void everyRotationKeepsImageOnPaper() {
        for (int rotation : new int[] { 0, 90, 180, 270 }) {
            Affine t = PrintLayout.toPaper(300, 200, rotation, PrintLayout.Mode.FIT, IVY2);
            for (double[] corner : new double[][] { { 0, 0 }, { 300, 0 }, { 0, 200 }, { 300, 200 } }) {
                double[] p = t.apply(corner[0], corner[1]);
                assertTrue(rotation + ": " + p[0], p[0] > -EPS && p[0] < 1280 + EPS);
                assertTrue(rotation + ": " + p[1], p[1] > -EPS && p[1] < 1920 + EPS);
            }
        }
    }

    @Test
    public void sampleSize() {
        // The camera's 1616x1080 screennail is needed at full resolution.
        assertEquals(1, PrintLayout.sampleSize(1616, 1080, 90, PrintLayout.Mode.FILL, IVY2));
        // A full 24MP A7 image can be decoded at 1/2 (fill) or 1/4 (fit, unrotated) size.
        assertEquals(2, PrintLayout.sampleSize(6000, 4000, 90, PrintLayout.Mode.FILL, IVY2));
        assertEquals(4, PrintLayout.sampleSize(6000, 4000, 0, PrintLayout.Mode.FIT, IVY2));
    }

    @Test
    public void selphyRotatesPortraitPhotosToLandscapePaper() {
        assertEquals(0, PrintLayout.rotation(1616, 1080, 0, true, SELPHY_POSTCARD));
        assertEquals(90, PrintLayout.rotation(1080, 1616, 0, true, SELPHY_POSTCARD));
        // Portrait photo stored sideways with an EXIF turn: upright it is portrait, so turn once more.
        assertEquals(180, PrintLayout.rotation(1616, 1080, 90, true, SELPHY_POSTCARD));
        assertEquals(0, PrintLayout.rotation(1080, 1616, 0, false, SELPHY_POSTCARD));
    }

    @Test
    public void selphyOutputIsUprightAndUnsquashed() {
        // 3:2 photo filling the (slightly narrower) postcard raster: height fits exactly.
        Affine t = PrintLayout.toPrinter(1500, 1000, 0, PrintLayout.Mode.FILL, SELPHY_POSTCARD);
        assertPoint(1808 / 2.0, 1232 / 2.0, t.apply(750, 500));
        assertEquals(0, t.apply(0, 0)[1], EPS);
        assertEquals(1232, t.apply(1500, 1000)[1], EPS);
        assertEquals(t.a, t.e, EPS); // uniform scale, no 180 degree turn
        assertTrue(t.a > 0);
    }

    @Test
    public void selphySampleSize() {
        assertEquals(1, PrintLayout.sampleSize(1616, 1080, 0, PrintLayout.Mode.FILL, SELPHY_POSTCARD));
        assertEquals(2, PrintLayout.sampleSize(6000, 4000, 0, PrintLayout.Mode.FILL, SELPHY_POSTCARD));
    }
}
