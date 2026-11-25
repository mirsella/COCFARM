package org.cocfarm.marble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class StorageMeterTest {
    @Test public void distinguishesBothFillColorsFromTheTranslucentEmptyBar() {
        assertEquals(true, StorageMeter.nearGoal(true, hsv(25, 148, 242), hsv(25, 240, 231)));
        assertEquals(true, StorageMeter.nearGoal(false, hsv(133, 137, 255), hsv(132, 215, 255)));
        assertEquals(false, StorageMeter.nearGoal(true, hsv(102, 58, 128), hsv(102, 200, 51)));
        assertEquals(false, StorageMeter.nearGoal(false, hsv(103, 66, 135), hsv(103, 192, 61)));
    }

    @Test public void refusesAnOccludedDimmedOrAmbiguousSample() {
        assertNull(StorageMeter.nearGoal(true, hsv(60, 61, 21), hsv(60, 61, 21)));
        assertNull(StorageMeter.nearGoal(false, hsv(133, 137, 152), hsv(132, 215, 152)));
        assertNull(StorageMeter.nearGoal(true, hsv(25, 148, 242), hsv(102, 200, 51)));
    }

    private static int[] hsv(int h, int s, int v) { return new int[]{h, s, v}; }
}
