package org.cocfarm.marble;

import java.util.ArrayList;
import java.util.List;

import org.cocfarm.marble.BuilderRun.ChargeCue;
import org.cocfarm.marble.BuilderRun.Point;

final class OverchargePolicy {
    // Coordinates are on a 648px-tall reference screen. This is a conservative
    // ground-distance estimate, not the game's exact blast radius.
    private static final double Y_STRETCH = 1.45;
    private static final int REACH = 72;

    record Target(Point ground, boolean wall, int footprint) {}

    static ChargeCue cue(int healthWidth, boolean nearbyMode, boolean visible, boolean cluster) {
        if (healthWidth < 6) return ChargeCue.WAIT;
        if (nearbyMode && visible) return cluster ? ChargeCue.NEAR_TARGETS : ChargeCue.WAIT;
        return healthWidth <= 30 ? ChargeCue.LOW_HP : ChargeCue.WAIT;
    }

    // Input is strongest match first. Merge repeated scales/templates of the
    // same building; walls cannot qualify on their own, regardless of count.
    static boolean hasCluster(Point unit, List<Target> candidates) {
        var buildings = new ArrayList<Target>();
        for (Target hit : candidates) {
            if (hit.wall || distance(unit, hit.ground) > REACH + hit.footprint) continue;
            boolean duplicate = false;
            for (Target other : buildings) {
                if (distance(hit.ground, other.ground) < 42) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) buildings.add(hit);
        }
        if (buildings.size() >= 2) return true;
        if (buildings.isEmpty()) return false;
        Point building = buildings.get(0).ground;
        for (Target wall : candidates) {
            if (wall.wall && distance(unit, wall.ground) <= REACH && blocks(unit, wall.ground, building)) {
                return true;
            }
        }
        return false;
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x() - b.x(), (a.y() - b.y()) * Y_STRETCH);
    }

    private static boolean blocks(Point from, Point wall, Point to) {
        double dx = to.x() - from.x();
        double dy = (to.y() - from.y()) * Y_STRETCH;
        double wx = wall.x() - from.x();
        double wy = (wall.y() - from.y()) * Y_STRETCH;
        double length = Math.hypot(dx, dy);
        if (length < 26) return false;
        double projection = (wx * dx + wy * dy) / (length * length);
        return projection >= .10 && projection <= .78 && Math.abs(wx * dy - wy * dx) / length <= 16;
    }

    private OverchargePolicy() {}
}
