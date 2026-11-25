package org.cocfarm.marble;

/** Estimates whether each right-to-left HUD fill has reached the selected mark. */
final class StorageMeter {
    enum Goal {
        EITHER_95(95, "either"),
        BOTH_95(95, "both"),
        BOTH_90(90, "both"),
        BATTLE_LIMIT(0, "battle limit");

        static final Goal DEFAULT = EITHER_95;

        final int percent;
        private final String target;

        Goal(int percent, String target) {
            this.percent = percent;
            this.target = target;
        }

        Goal next() {
            return switch (this) {
                case EITHER_95 -> BOTH_95;
                case BOTH_95 -> BOTH_90;
                case BOTH_90 -> BATTLE_LIMIT;
                case BATTLE_LIMIT -> DEFAULT;
            };
        }

        String label() {
            return "Stop: " + target + (this == BATTLE_LIMIT ? "" : " ≈" + percent + "%");
        }

        boolean reached(Levels levels) {
            return switch (this) {
                case EITHER_95 -> levels.goldNear || levels.elixirNear;
                case BOTH_95, BOTH_90 -> levels.goldNear && levels.elixirNear;
                case BATTLE_LIMIT -> throw new IllegalStateException("No storage goal for a fixed battle limit");
            };
        }
    }

    record Levels(boolean goldNear, boolean elixirNear) {
        String summary(int goal) {
            return "Gold " + level(goldNear, goal) + " · Elixir " + level(elixirNear, goal) + ".";
        }

        private static String level(boolean near, int goal) { return near ? "≈" + goal + "%+" : "below goal"; }
    }

    private StorageMeter() {}

    static int sampleOffset(int goal) {
        // Raw reference pixels left of the icon in the 2400×1080 HUD.
        return switch (goal) {
            case 90 -> 309;
            case 95 -> 327;
            default -> throw new IllegalArgumentException("No storage mark for " + goal);
        };
    }
    static Boolean nearGoal(boolean gold, int[] gloss, int[] lower) {
        if (filled(gold, gloss) && filled(gold, lower)) return true;
        // The unfilled bar has a translucent light top over a much darker bottom.
        // Reject flat/dim patches rather than treating an unreadable bar as empty.
        if (gloss[2] >= 90 && gloss[2] <= 190 && lower[2] <= 150 && gloss[2] - lower[2] >= 35) return false;
        return null;
    }

    private static boolean filled(boolean gold, int[] hsv) {
        int minHue = gold ? 18 : 120;
        int maxHue = gold ? 40 : 151;
        return hsv[0] >= minHue && hsv[0] <= maxHue && hsv[1] >= 90 && hsv[2] >= 205;
    }
}
