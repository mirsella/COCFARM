package org.cocfarm.marble;

import android.content.Context;
import android.database.SQLException;
import android.text.format.DateFormat;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.cocfarm.marble.BattleHistory.Mean;
import org.cocfarm.marble.BattleHistory.Row;
import org.cocfarm.marble.BattleHistory.Summary;
import org.cocfarm.marble.BattleScore.Profile;

final class HistoryView extends LinearLayout {
    private static final int PAGE_SIZE = 20;
    private final LinearLayout comparisons;
    private final LinearLayout rounds;
    private final Button more;
    private final TextView configuration;
    private long before;

    HistoryView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        Ui.line(this, Ui.text(context, "01  /  STRATEGY COMPARISON", 11, Ui.LIME, true), 6);
        configuration = Ui.text(context, "", 13, Ui.TEXT, false);
        Ui.line(this, configuration, 0);
        Ui.line(this, Ui.text(context, "Four-way comparison. Scores are OCR estimates; n counts readings, not verified results.",
                13, Ui.MUTED, false), 3);
        comparisons = Ui.stack(context);
        Ui.line(this, comparisons, 4);
        Ui.line(this, Ui.text(context, "02  /  BATTLE LOG", 11, Ui.LIME, true), 14);
        rounds = Ui.stack(context);
        Ui.line(this, rounds, 4);
        more = Ui.button(context, "Load older battles", false, v -> loadOlder());
        Ui.line(this, more, 6);
    }

    void refresh(Profile profile) {
        configuration.setText(setup(profile));
        comparisons.removeAllViews();
        rounds.removeAllViews();
        before = Long.MAX_VALUE;
        try (BattleHistory history = new BattleHistory(getContext())) {
            List<Summary> summaries = history.comparison(profile);
            for (var plan : BattleScore.PLANS) {
                Summary arm = null;
                for (Summary candidate : summaries) if (plan.equals(candidate.plan())) arm = candidate;
                var row = card(comparisons, plan.label());
                if (arm == null) {
                    detail(row, "No battles recorded");
                } else {
                    detail(row, arm.battles() + (arm.battles() == 1 ? " battle" : " battles"));
                    detail(row, "Gold " + mean(arm.gold(), "%,.0f") + "  ·  Damage " + mean(arm.damage(), "%.1f%%"));
                    detail(row, "Trophies " + mean(arm.trophies(), "%+.1f"));
                }
            }
            load(history);
        } catch (SQLException e) {
            error();
        }
    }

    private void loadOlder() {
        try (BattleHistory history = new BattleHistory(getContext())) {
            load(history);
        } catch (SQLException e) {
            error();
        }
    }

    private void load(BattleHistory history) {
        List<Row> rows = history.rowsBefore(before, PAGE_SIZE + 1);
        if (before == Long.MAX_VALUE && rows.isEmpty()) detail(rounds, "No battles saved yet.");
        for (int i = 0; i < Math.min(PAGE_SIZE, rows.size()); i++) {
            Row row = rows.get(i);
            before = row.id();
            var battle = row.entry();
            var result = battle.result();
            var item = card(rounds, "#" + row.id() + "   " + battle.plan().label());
            Date time = new Date(row.time());
            String origin = battle.partial() ? "Joined / resumed" : row.previousComparison() ? "Compare (old rotation)"
                    : battle.comparison() ? "Compare" : "Manual plan";
            detail(item, DateFormat.getDateFormat(getContext()).format(time) + " · "
                    + DateFormat.getTimeFormat(getContext()).format(time)
                    + " · " + origin);
            detail(item, "Gold " + number(result.gold(), "%,d") + "  ·  Damage " + number(result.damage(), "%d%%")
                    + "  ·  Trophies " + number(result.trophies(), "%+d"));
            String stage = battle.stages() == 0 ? "Stage unknown" : battle.stages() + " stage(s)";
            detail(item, stage + " · " + (battle.durationMs() / 1_000) + "s tracked");
            detail(item, setup(battle.profile()));
            String pekka = switch (battle.plan().overcharge()) {
                case HALF_HP -> battle.lowHp() + " at 50% HP, ";
                case LOW_HP -> battle.lowHp() + " at low HP, ";
                case NEAR_TARGETS -> battle.lowHp() + " at low HP, " + battle.nearby() + " nearby, ";
                case GAME -> "";
            };
            detail(item, "Ability taps: " + pekka + battle.heroPowers() + " hero · Split fallbacks: " + battle.splitFallbacks());
        }
        more.setVisibility(rows.size() > PAGE_SIZE ? VISIBLE : GONE);
    }

    private LinearLayout card(LinearLayout parent, String title) {
        Context ctx = getContext();
        LinearLayout card = Ui.stack(ctx);
        int inset = Ui.dp(ctx, 8);
        card.setPadding(inset, inset, inset, inset);
        card.setBackground(Ui.surface(ctx, Ui.PANEL, Ui.EDGE, 5));
        Ui.line(parent, card, 5);
        Ui.line(card, Ui.text(ctx, title, 13, Ui.TEXT, true), 0);
        return card;
    }

    private void detail(LinearLayout parent, String text) {
        Ui.line(parent, Ui.text(getContext(), text, 13, Ui.MUTED, false), 3);
    }

    private void error() {
        detail(rounds, "Couldn't open the battle log. Close it and try again.");
        more.setVisibility(GONE);
    }

    private static String number(Integer value, String format) {
        return value == null ? "—" : String.format(Locale.US, format, value);
    }

    private static String mean(Mean value, String format) {
        return value.reads() == 0 ? "—" : String.format(Locale.US, format, value.value()) + " (n=" + value.reads() + ")";
    }

    private static String setup(Profile profile) {
        return "Hero " + (profile.hero() ? "on" : "off") + " · Stage 2 " + (profile.stageTwo() ? "on" : "off")
                + " · Bar " + profile.cardOffset();
    }
}
