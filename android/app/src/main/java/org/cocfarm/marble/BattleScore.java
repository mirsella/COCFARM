package org.cocfarm.marble;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import org.cocfarm.marble.BuilderRun.Overcharge;
import org.cocfarm.marble.BuilderRun.Strategy;

/** Missing or unreadable fields do not count as zero. */
final class BattleScore {
    private static final Pattern GOLD = Pattern.compile("(?:[0-9]{1,7}|[0-9]{1,3}(?:[, ][0-9]{3}){1,2})");
    private static final Pattern DAMAGE = Pattern.compile("[0-9]{1,3}\\s*%");
    private static final Pattern TROPHIES = Pattern.compile("[+−-]?[0-9]{1,3}");

    record Result(Integer gold, Integer damage, Integer trophies) {
        static final Result UNREAD = new Result(null, null, null);
    }

    record Plan(Strategy deployment, Overcharge overcharge) {
        String label() { return deployment.label + " / " + overcharge.label; }
    }

    static final int COMPARISON_VERSION = 2;

    // Each block covers all four cells. Alternate the order across blocks and
    // resume from the number of recorded comparisons, even after app restarts.
    static final List<Plan> PLANS = List.of(
            new Plan(Strategy.FOCUS, Overcharge.GAME),
            new Plan(Strategy.SPLIT, Overcharge.HALF_HP),
            new Plan(Strategy.SPLIT, Overcharge.GAME),
            new Plan(Strategy.FOCUS, Overcharge.HALF_HP));

    static Plan nextPlan(long recorded) {
        int index = (int) (recorded % PLANS.size());
        return PLANS.get(recorded / PLANS.size() % 2 == 0 ? index : PLANS.size() - 1 - index);
    }

    record Profile(boolean hero, boolean stageTwo, int cardOffset) {}

    // Keep the legacy counter names in the trace format; Half HP rows use lowHp for health-triggered taps.
    record Entry(Plan plan, boolean comparison, Profile profile,
                  int stages, long durationMs, int lowHp, int nearby, int heroPowers, int splitFallbacks,
                  Result result, boolean partial) {}

    interface Journal {
        long comparisonCount(Profile profile) throws IOException;
        void record(Entry entry) throws IOException;
    }

    static Integer trophies(String text) {
        text = text.strip();
        return TROPHIES.matcher(text).matches() ? Integer.valueOf(text.replace('−', '-')) : null;
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
