package org.cocfarm.marble;

import java.util.Locale;
import java.util.regex.Pattern;

import org.cocfarm.marble.BuilderRun.Strategy;

/** Missing or unreadable fields do not count as zero. */
final class BattleScore {
    private static final Pattern GOLD = Pattern.compile("(?:[0-9]{1,7}|[0-9]{1,3}(?:[, ][0-9]{3}){1,2})");
    private static final Pattern DAMAGE = Pattern.compile("[0-9]{1,3}\\s*%");

    record Result(Integer gold, Integer damage) {
        static final Result UNREAD = new Result(null, null);
    }

    private static final class Arm {
        int battles;
        int goldReads;
        long gold;
        int damageReads;
        int damage;

        void add(Result result) {
            battles++;
            if (result.gold != null) {
                goldReads++;
                gold += result.gold;
            }
            if (result.damage != null) {
                damageReads++;
                damage += result.damage;
            }
        }

        String summary() {
            String reward = goldReads == 0 ? "Gold —" :
                    String.format(Locale.US, "%,d gold", Math.round((double) gold / goldReads));
            String destruction = damageReads == 0 ? "damage —" : Math.round((double) damage / damageReads) + "%";
            String reads = goldReads == battles && damageReads == battles ? "" : "  [" + goldReads + "/" + damageReads + "]";
            return reward + " · " + destruction + reads;
        }
    }

    private final Arm focus = new Arm();
    private final Arm split = new Arm();

    void add(Strategy strategy, Result result) {
        switch (strategy) {
            case FOCUS -> focus.add(result);
            case SPLIT -> split.add(result);
            case COMPARE -> throw new IllegalArgumentException("A score needs a battle strategy");
        }
    }

    String summary() {
        return "A/B averages per battle\nFocus (" + focus.battles + "): " + focus.summary()
                + "\nSplit (" + split.battles + "): " + split.summary();
    }

    static Integer gold(String text) {
        text = text.strip();
        if (!GOLD.matcher(text).matches()) return null;
        return Integer.parseInt(text.replace(",", "").replace(" ", ""));
    }

    static Integer damage(String text) {
        text = text.strip();
        if (!DAMAGE.matcher(text).matches()) return null;
        int value = Integer.parseInt(text.substring(0, text.length() - 1).stripTrailing());
        return value <= 200 ? value : null;
    }
}
