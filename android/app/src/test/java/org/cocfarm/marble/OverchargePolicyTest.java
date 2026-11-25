package org.cocfarm.marble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.cocfarm.marble.BuilderRun.ChargeCue;
import org.cocfarm.marble.BuilderRun.Point;
import org.cocfarm.marble.OverchargePolicy.Target;
import org.junit.Test;

public class OverchargePolicyTest {
    private static final Point UNIT = new Point(300, 300);

    private static Target building(int x, int y) { return new Target(new Point(x, y), false, 16); }
    private static Target wall(int x, int y) { return new Target(new Point(x, y), true, 0); }

    @Test public void twoDistinctBuildingsQualifyButRepeatedOrDistantHitsDoNot() {
        assertTrue(OverchargePolicy.hasCluster(UNIT, List.of(building(355, 298), building(294, 336))));
        assertFalse(OverchargePolicy.hasCluster(UNIT, List.of(building(355, 298), building(356, 307))));
        assertFalse(OverchargePolicy.hasCluster(UNIT, List.of(building(355, 298), building(294, 380))));
    }

    @Test public void wallMustBeBetweenTheUnitAndBuilding() {
        assertTrue(OverchargePolicy.hasCluster(UNIT, List.of(building(365, 300), wall(336, 300))));
        assertFalse(OverchargePolicy.hasCluster(UNIT, List.of(building(365, 300), wall(336, 320))));
        assertFalse(OverchargePolicy.hasCluster(UNIT, List.of(wall(325, 300), wall(345, 300))));
    }

    @Test public void lowHealthFallbackRequiresAnUnreadableMarkerAndADeployedHealthBar() {
        assertEquals(ChargeCue.LOW_HP, OverchargePolicy.cue(30, true, false, false));
        assertEquals(ChargeCue.WAIT, OverchargePolicy.cue(31, true, false, false));
        assertEquals(ChargeCue.WAIT, OverchargePolicy.cue(10, true, true, false));
        assertEquals(ChargeCue.NEAR_TARGETS, OverchargePolicy.cue(74, true, true, true));
        assertEquals(ChargeCue.WAIT, OverchargePolicy.cue(5, true, true, true));
    }
}
