package org.cocfarm.marble;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.cocfarm.marble.BattleScore.Entry;
import org.cocfarm.marble.BattleScore.Plan;
import org.cocfarm.marble.BattleScore.Profile;
import org.cocfarm.marble.BattleScore.Result;
import org.cocfarm.marble.BuilderRun.Overcharge;
import org.cocfarm.marble.BuilderRun.Strategy;

/** Private on-device log. A result is committed before the runner leaves the result screen. */
final class BattleHistory extends SQLiteOpenHelper implements BattleScore.Journal {
    record Row(long id, long time, Entry entry, boolean previousComparison) {}
    record Mean(int reads, double value) {}
    record Summary(Plan plan, int battles, Mean gold, Mean damage, Mean trophies) {}

    private static final String COMPARISON_PROFILE =
            "comparison = 1 AND hero = ? AND stage_two = ? AND card_offset = ? AND comparison_version = ?";
    private static final String SUMMARIES = """
            SELECT deployment, overcharge, COUNT(*),
                   COUNT(gold), AVG(gold), COUNT(damage), AVG(damage), COUNT(trophies), AVG(trophies)
            FROM battles WHERE
            """ + COMPARISON_PROFILE + " GROUP BY deployment, overcharge";

    BattleHistory(Context context) {
        super(context.getApplicationContext(), "builder-battles.db", null, 3);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("""
                CREATE TABLE battles (
                    id INTEGER PRIMARY KEY, played_at INTEGER NOT NULL,
                    deployment TEXT NOT NULL, overcharge TEXT NOT NULL, comparison INTEGER NOT NULL,
                    hero INTEGER NOT NULL, stage_two INTEGER NOT NULL, card_offset INTEGER NOT NULL,
                    stages INTEGER NOT NULL, duration_ms INTEGER NOT NULL,
                    low_hp INTEGER NOT NULL, nearby INTEGER NOT NULL,
                    hero_powers INTEGER NOT NULL, split_fallbacks INTEGER NOT NULL,
                    gold INTEGER, damage INTEGER, trophies INTEGER, partial INTEGER NOT NULL DEFAULT 0,
                    comparison_version INTEGER NOT NULL DEFAULT 1
                )
                """);
        db.execSQL("CREATE INDEX battles_comparison ON battles(comparison, hero, stage_two, card_offset, deployment, overcharge)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if ((from != 1 && from != 2) || to != 3) {
            throw new IllegalStateException("No battle history migration from " + from + " to " + to);
        }
        if (from == 1) db.execSQL("ALTER TABLE battles ADD COLUMN partial INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE battles ADD COLUMN comparison_version INTEGER NOT NULL DEFAULT 1");
    }

    @Override public long comparisonCount(Profile profile) throws IOException {
        try {
            return DatabaseUtils.queryNumEntries(getReadableDatabase(), "battles", COMPARISON_PROFILE, args(profile));
        } catch (SQLException e) {
            throw new IOException("Couldn't read the battle history. No battle started.", e);
        }
    }

    @Override public void record(Entry entry) throws IOException {
        ContentValues fields = new ContentValues();
        fields.put("played_at", System.currentTimeMillis());
        fields.put("deployment", entry.plan().deployment().name());
        fields.put("overcharge", entry.plan().overcharge().name());
        fields.put("comparison", entry.comparison());
        fields.put("comparison_version", BattleScore.COMPARISON_VERSION);
        fields.put("hero", entry.profile().hero());
        fields.put("stage_two", entry.profile().stageTwo());
        fields.put("card_offset", entry.profile().cardOffset());
        fields.put("stages", entry.stages());
        fields.put("duration_ms", entry.durationMs());
        fields.put("low_hp", entry.lowHp());
        fields.put("nearby", entry.nearby());
        fields.put("hero_powers", entry.heroPowers());
        fields.put("split_fallbacks", entry.splitFallbacks());
        fields.put("gold", entry.result().gold());
        fields.put("damage", entry.result().damage());
        fields.put("trophies", entry.result().trophies());
        fields.put("partial", entry.partial());
        try {
            getWritableDatabase().insertOrThrow("battles", null, fields);
        } catch (SQLException e) {
            throw new IOException("Couldn't save the battle result. The result screen was left open.", e);
        }
    }

    List<Row> rowsBefore(long before, int limit) {
        try (Cursor rows = getReadableDatabase().rawQuery("""
                SELECT id, played_at, deployment, overcharge, comparison, hero, stage_two, card_offset,
                       stages, duration_ms, low_hp, nearby, hero_powers, split_fallbacks, gold, damage, trophies, partial,
                       comparison_version
                FROM battles WHERE id < ? ORDER BY id DESC LIMIT ?
                """, new String[]{Long.toString(before), Integer.toString(limit)})) {
            List<Row> result = new ArrayList<>();
            while (rows.moveToNext()) {
                var profile = new Profile(rows.getInt(5) != 0, rows.getInt(6) != 0, rows.getInt(7));
                Entry entry = new Entry(plan(rows, 2), rows.getInt(4) != 0, profile, rows.getInt(8), rows.getLong(9),
                        rows.getInt(10), rows.getInt(11), rows.getInt(12), rows.getInt(13),
                        new Result(number(rows, 14), number(rows, 15), number(rows, 16)), rows.getInt(17) != 0);
                result.add(new Row(rows.getLong(0), rows.getLong(1), entry,
                        entry.comparison() && rows.getInt(18) != BattleScore.COMPARISON_VERSION));
            }
            return result;
        }
    }

    List<Summary> comparison(Profile profile) {
        try (Cursor rows = getReadableDatabase().rawQuery(SUMMARIES, args(profile))) {
            List<Summary> result = new ArrayList<>();
            while (rows.moveToNext()) {
                result.add(new Summary(plan(rows, 0), rows.getInt(2), mean(rows, 3), mean(rows, 5), mean(rows, 7)));
            }
            return result;
        }
    }

    private static String[] args(Profile profile) {
        return new String[]{profile.hero() ? "1" : "0", profile.stageTwo() ? "1" : "0",
                Integer.toString(profile.cardOffset()), Integer.toString(BattleScore.COMPARISON_VERSION)};
    }

    private static Plan plan(Cursor rows, int index) {
        return new Plan(Strategy.valueOf(rows.getString(index)), Overcharge.valueOf(rows.getString(index + 1)));
    }

    private static Integer number(Cursor rows, int index) {
        return rows.isNull(index) ? null : rows.getInt(index);
    }

    private static Mean mean(Cursor rows, int index) {
        return new Mean(rows.getInt(index), rows.getDouble(index + 1));
    }
}
