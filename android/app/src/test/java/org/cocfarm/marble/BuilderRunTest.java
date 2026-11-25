package org.cocfarm.marble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import org.cocfarm.marble.BuilderRun.HeroCard;
import org.cocfarm.marble.BuilderRun.HeroPower;
import org.cocfarm.marble.BuilderRun.Mark;
import org.cocfarm.marble.BuilderRun.Match;
import org.cocfarm.marble.BuilderRun.Mode;
import org.cocfarm.marble.BuilderRun.Options;
import org.cocfarm.marble.BuilderRun.Overcharge;
import org.cocfarm.marble.BuilderRun.PekkaHealth;
import org.cocfarm.marble.BuilderRun.Point;
import org.cocfarm.marble.BuilderRun.Strategy;
import org.junit.Test;

public class BuilderRunTest {
    private static final Point CARD = new Point(185, 830);
    private static final Point DROP = new Point(636, 468);

    private static final class Journal implements BattleScore.Journal {
        final List<BattleScore.Entry> entries = new ArrayList<>();
        IOException failure;

        @Override public long comparisonCount(BattleScore.Profile profile) {
            return entries.stream().filter(entry -> entry.comparison() && entry.profile().equals(profile)).count();
        }

        @Override public void record(BattleScore.Entry entry) throws IOException {
            if (failure != null) throw failure;
            entries.add(entry);
        }
    }

    private static BuilderRun runner(Options options) {
        return new BuilderRun(options, new Journal());
    }

    private static final class Game implements BuilderRun.Board {
        final EnumMap<Mark, Match> marks = new EnumMap<>(Mark.class);
        final List<Point> taps = new ArrayList<>();
        final List<Boolean> pans = new ArrayList<>();
        List<Point> cards = List.of();
        List<Point> halfHealthPekkas = List.of();
        List<Point> aboveHalfPekkas = List.of();
        Point hero;
        HeroCard heroCard = HeroCard.UNKNOWN;
        HeroPower heroPower = HeroPower.UNKNOWN;
        BattleScore.Result result = BattleScore.Result.UNREAD;
        StorageMeter.Levels storage;
        IOException failure;

        Game show(Mark... visible) {
            marks.clear();
            for (Mark mark : visible) marks.put(mark, new Match(at(mark), .95));
            return this;
        }

        @Override public Match find(Mark mark) { return marks.get(mark); }
        @Override public List<Point> ready() { return cards; }
        @Override public Point heroSlot() { return hero; }
        @Override public HeroCard heroCard() { return heroCard; }
        @Override public HeroPower heroPower(Point card) { return heroPower; }
        @Override public Point dropPoint(int lane, int miss) {
            return new Point(DROP.x() + miss, DROP.y() + 100 * lane);
        }
        @Override public PekkaHealth pekkaHealth(Point card) {
            if (halfHealthPekkas.contains(card)) return PekkaHealth.AT_OR_BELOW_HALF;
            return aboveHalfPekkas.contains(card) ? PekkaHealth.ABOVE_HALF : PekkaHealth.UNKNOWN;
        }
        @Override public BattleScore.Result result() { return result; }
        @Override public StorageMeter.Levels storage() { return storage; }
        @Override public void tap(Point point) throws IOException {
            if (failure != null) throw failure;
            taps.add(point);
        }

        @Override public void panForCart(boolean towardShore) { pans.add(towardShore); }
    }

    private static Point at(Mark mark) { return new Point(mark.ordinal(), 1); }

    private static BuilderRun battles(int limit, boolean second) {
        return runner(new Options(Mode.BATTLES, limit, second, true, 0, false));
    }

    private static BuilderRun cart(boolean check) {
        return runner(new Options(Mode.CART, 1, true, true, 0, check));
    }

    private static BuilderRun farm(int limit, int every, StorageMeter.Goal goal, boolean check) {
        return runner(new Options(Mode.BATTLES, limit, true, true, 0, check,
                Strategy.FOCUS, Overcharge.GAME, every, goal));
    }

    private static void matchBattle(BuilderRun run, Game game, long start) throws IOException {
        run.step(game.show(Mark.BASE), start);
        run.step(game.show(Mark.FIND), start + 1);
        run.step(game.show(Mark.STARTS), start + 2);
    }

    @Test public void enablesFourTimesSpeedAfterDeploymentOncePerStageAndNeverOnTheResult() throws IOException {
        Game game = new Game();
        BuilderRun run = battles(1, true);
        matchBattle(run, game, 0);
        game.cards = List.of(CARD);
        run.step(game.show(Mark.ENDS, Mark.SPEED_1X), 3);
        run.step(game, 4);
        game.cards = List.of();
        run.step(game, 5);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP), game.taps);
        assertTrue(run.step(game, 6).text().contains("4×"));
        run.step(game, 7);
        run.step(game.show(Mark.STARTS, Mark.SPEED_1X), 8);
        game.cards = List.of(CARD);
        run.step(game.show(Mark.ENDS, Mark.SPEED_1X), 9);
        run.step(game, 10);
        game.cards = List.of();
        run.step(game, 11);
        run.step(game, 12);
        long settle = run.step(game.show(Mark.RETURN, Mark.ENDS, Mark.SPEED_1X), 13).delayMs();
        run.step(game, 13 + settle);
        run.step(game, 14 + settle);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP, at(Mark.SPEED_1X),
                CARD, DROP, at(Mark.SPEED_1X), at(Mark.RETURN)), game.taps);
    }

    @Test public void waitsForRecognizedCooldownWithoutTappingUntilFindNow() throws IOException {
        BuilderRun run = battles(1, true);
        Game game = new Game();
        run.step(game.show(Mark.BASE), 0);
        assertTrue(run.step(game.show(Mark.ATTACK_COOLDOWN), 7_000).text().contains("cooldown"));
        run.step(game, 14_000);
        assertEquals(List.of(at(Mark.BASE)), game.taps);
        run.step(game.show(Mark.FIND), 14_500);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND)), game.taps);
    }

    @Test public void recognizedCooldownStillHasABoundedWait() throws IOException {
        BuilderRun run = battles(1, true);
        Game game = new Game();
        run.step(game.show(Mark.BASE), 0);
        game.show(Mark.ATTACK_COOLDOWN);
        for (int now = 5_000; now < 120_000; now += 5_000) run.step(game, now);
        assertThrows(IOException.class, () -> run.step(game, 120_000));
        assertEquals(List.of(at(Mark.BASE)), game.taps);
    }

    @Test public void verifiesBaseAndCartWithoutSendingAnyTaps() throws IOException {
        Game game = new Game();
        for (Mode mode : Mode.values()) {
            BuilderRun check = runner(new Options(mode, 1, true, true, 0, true));
            game.show(mode == Mode.BATTLES ? Mark.BASE : Mark.CART_TITLE, Mark.COLLECT);
            assertTrue(check.step(game, 0).finished());
            assertEquals(List.of(), game.taps);
        }
    }

    @Test public void neverCollectsWithoutRecognizedCartTitle() {
        Game game = new Game().show(Mark.COLLECT);
        BuilderRun run = runner(new Options(Mode.CART, 1, true, true, 0, false));
        assertThrows(IOException.class, () -> run.step(game, 0));
        assertTrue(game.taps.isEmpty());
    }

    @Test public void noInputForInactiveCartAndOnlyOneForActiveCart() throws IOException {
        Game game = new Game().show(Mark.CART_TITLE);
        BuilderRun empty = cart(false);
        assertFalse(empty.step(game, 0).finished());
        assertTrue(empty.step(game, 2_501).finished());
        assertTrue(game.taps.isEmpty());
        BuilderRun filled = cart(false);
        assertFalse(filled.step(game.show(Mark.CART_TITLE, Mark.COLLECT), 0).finished());
        assertTrue(filled.step(game.show(Mark.CART_TITLE), 2_701).text().contains("now inactive"));
        assertEquals(List.of(at(Mark.COLLECT)), game.taps);
    }

    @Test public void findsCartFromBaseAndWaitsForTheDialogBeforeCollecting() throws IOException {
        Game game = new Game();
        BuilderRun run = cart(false);
        run.step(game.show(Mark.BASE), 0);
        assertEquals(List.of(true), game.pans);
        run.step(game.show(Mark.BASE, Mark.CART_WORLD), 1_000);
        run.step(game.show(Mark.COLLECT), 1_400);
        assertEquals(List.of(at(Mark.CART_WORLD)), game.taps);
        run.step(game.show(Mark.CART_TITLE, Mark.COLLECT), 1_750);
        assertTrue(run.step(game, 4_451).text().contains("Rewards remain"));
        assertEquals(List.of(at(Mark.CART_WORLD), at(Mark.COLLECT)), game.taps);
    }

    @Test public void cartCheckDoesNotPanAndCartSearchIsBounded() throws IOException {
        Game game = new Game().show(Mark.BASE);
        assertTrue(cart(true).step(game, 0).text().contains("off-screen"));
        assertTrue(game.pans.isEmpty());
        BuilderRun run = cart(false);
        for (int i = 0; i < 5; i++) run.step(game, i * 1_000);
        assertThrows(IOException.class, () -> run.step(game, 5_000));
        assertEquals(List.of(true, false, false, false, true), game.pans);
        assertTrue(game.taps.isEmpty());
    }

    @Test public void clearsNoDialogIfMatchmakingIsBlocked() throws IOException {
        Game game = new Game();
        BuilderRun run = battles(1, true);
        run.step(game.show(Mark.BASE), 0);
        assertThrows(IOException.class, () -> run.step(game.show(), 8_001));
        assertEquals(List.of(at(Mark.BASE)), game.taps);
    }

    @Test public void fixedBattleLimitRequiresAnActiveBaseBeforeAnyTap() {
        BuilderRun run = battles(1, true);
        Game game = new Game().show(Mark.CART_TITLE, Mark.CART_CLOSE);
        assertThrows(IOException.class, () -> run.step(game, 0));
        assertTrue(game.taps.isEmpty());
        assertTrue(game.pans.isEmpty());
    }

    @Test public void playsBothStagesAndFinishesOnlyAfterReturningToBase() throws IOException {
        Game game = new Game();
        BuilderRun run = battles(1, true);
        matchBattle(run, game, 0);
        game.cards = List.of(CARD);
        run.step(game, 3);
        run.step(game, 4);
        game.cards = List.of();
        run.step(game.show(Mark.ENDS), 5);
        run.step(game.show(Mark.STARTS), 6);
        game.cards = List.of(CARD);
        run.step(game, 7);
        run.step(game, 8);
        long base = returnFromResult(run, game, 9);
        assertTrue(run.step(game.show(Mark.BASE), base).finished());
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP, CARD, DROP, at(Mark.RETURN)),
                game.taps);
    }

    @Test public void leavesSecondStageAloneWhenToggleIsOff() throws IOException {
        Game game = new Game();
        BuilderRun run = battles(1, false);
        matchBattle(run, game, 0);
        run.step(game.show(Mark.ENDS), 3);
        game.cards = List.of(CARD);
        assertTrue(run.step(game.show(Mark.STARTS), 4).text().contains("Watching stage 2"));
        run.step(game, 5);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND)), game.taps);
    }

    @Test public void triesANewDropWhenTheCardStaysReadyThenReusesAConfirmedDrop() throws IOException {
        Game game = new Game();
        BuilderRun run = battles(1, true);
        Point second = new Point(289, 830);
        Point alternate = new Point(DROP.x() + 1, DROP.y());
        matchBattle(run, game, 0);
        game.cards = List.of(CARD, second);
        run.step(game, 3); // select first card
        run.step(game, 4); // first drop rejected
        run.step(game, 5); // first card still ready
        run.step(game, 6); // next candidate
        game.cards = List.of(second);
        run.step(game.show(Mark.ENDS), 7);
        run.step(game, 8);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP, CARD, alternate, second, alternate),
                game.taps);
    }

    @Test public void ignoresAUsedCardThatLooksReadyAgain() throws IOException {
        Game game = new Game();
        BuilderRun run = battles(1, true);
        Point second = new Point(289, 830);
        matchBattle(run, game, 0);
        game.cards = List.of(CARD, second);
        run.step(game, 3);
        run.step(game, 4);
        game.cards = List.of(second);
        run.step(game.show(Mark.ENDS), 5);
        game.cards = List.of(CARD, second);
        run.step(game, 6);
        game.cards = List.of(CARD);
        assertTrue(run.step(game, 7).text().contains("Watching stage 1"));
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP, second, DROP), game.taps);
    }

    @Test public void waitsForTheResultAfterPartialDeploymentTimesOut() throws IOException {
        Game game = new Game();
        BuilderRun run = battles(1, true);
        Point second = new Point(289, 830);
        matchBattle(run, game, 0);
        game.cards = List.of(CARD, second);
        run.step(game, 3);
        run.step(game, 4);
        game.cards = List.of(second);
        run.step(game.show(Mark.ENDS), 5);
        assertTrue(run.step(game, 40_003).text().contains("Watching stage 1"));
        long base = returnFromResult(run, game, 120_000);
        assertTrue(run.step(game.show(Mark.BASE), base).finished());
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP, second, at(Mark.RETURN)),
                game.taps);
    }

    @Test public void splitAlternatesDropsAndFallsBackIfTheSecondaryLaneIsBlocked() throws IOException {
        Game game = new Game();
        BuilderRun run = runner(new Options(Mode.BATTLES, 1, true, true, 0, false, Strategy.SPLIT, false));
        Point second = new Point(289, 830);
        matchBattle(run, game, 0);
        game.cards = List.of(CARD, second);
        assertTrue(run.step(game, 3).delayMs() < 340);
        run.step(game, 4);
        game.cards = List.of(second);
        for (int i = 5; i <= 12; i++) run.step(game.show(Mark.ENDS), i);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP,
                second, new Point(DROP.x(), DROP.y() + 100),
                second, new Point(DROP.x() + 1, DROP.y() + 100),
                second, new Point(DROP.x() + 2, DROP.y() + 100),
                second, DROP), game.taps);
    }

    @Test public void splitSendsTheThirdUnitBackToTheFirstLane() throws IOException {
        Game game = new Game();
        BuilderRun run = runner(new Options(Mode.BATTLES, 1, true, true, 0, false, Strategy.SPLIT, false));
        Point second = new Point(289, 830);
        Point third = new Point(393, 830);
        matchBattle(run, game, 0);
        game.cards = List.of(CARD, second, third);
        run.step(game, 3);
        run.step(game, 4);
        game.cards = List.of(second, third);
        run.step(game.show(Mark.ENDS), 5);
        run.step(game, 6);
        game.cards = List.of(third);
        run.step(game, 7);
        run.step(game, 8);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP,
                second, new Point(DROP.x(), DROP.y() + 100), third, DROP), game.taps);
    }

    @Test public void heroFollowsFirstSuccessfulTroopDropInTheSameLaneEvenInSplit() throws IOException {
        Game game = new Game();
        game.hero = new Point(68, 830);
        Point secondTroop = new Point(289, 830);
        Point primary = new Point(DROP.x() + 1, DROP.y());
        BuilderRun run = runner(new Options(Mode.BATTLES, 1, true, true, 0, false, Strategy.SPLIT, false));
        matchBattle(run, game, 0);
        game.cards = List.of(game.hero, CARD, secondTroop);
        run.step(game, 3); // select the troop, even though the hero is the first visible card
        run.step(game, 4); // rejected drop
        run.step(game, 5);
        run.step(game, 6); // successful retry
        game.cards = List.of(game.hero, secondTroop);
        run.step(game.show(Mark.ENDS), 7);
        run.step(game, 8);
        game.cards = List.of(secondTroop);
        game.heroCard = HeroCard.DEPLOYED;
        run.step(game, 9);
        run.step(game, 10);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP, CARD, primary,
                game.hero, primary, secondTroop, new Point(DROP.x(), DROP.y() + 100)), game.taps);
    }

    @Test public void heroPowerFiresOnlyAfterDeploymentAndOnceUntilTheMeterRecharges() throws IOException {
        Game game = new Game();
        game.hero = new Point(68, 830);
        BuilderRun run = battles(1, true);
        matchBattle(run, game, 0);
        game.heroPower = HeroPower.READY;
        run.step(game, 3); // readiness alone cannot tap an undeployed hero
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND)), game.taps);
        game.cards = List.of(game.hero);
        run.step(game.show(Mark.STARTS), 4);
        run.step(game, 5);
        game.cards = List.of();
        game.heroCard = HeroCard.DEPLOYED;
        run.step(game, 6); // no ability tap during the stage-start countdown
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), game.hero, DROP), game.taps);
        assertTrue(run.step(game.show(Mark.ENDS), 7).text().contains("Hero ability"));
        game.heroPower = HeroPower.UNKNOWN; // obscured or flashing is not a recharge
        run.step(game, 8);
        game.heroPower = HeroPower.READY;
        run.step(game, 9);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), game.hero, DROP, game.hero), game.taps);
        game.heroPower = HeroPower.CHARGING;
        run.step(game, 10);
        game.heroPower = HeroPower.READY;
        assertTrue(run.step(game, 11).text().contains("Hero ability"));
        run.step(game, 12);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), game.hero, DROP, game.hero, game.hero), game.taps);
    }

    @Test public void retriesAnUnconfirmedHeroDropAndRecoversAReadyHeroDuringCombat() throws IOException {
        Game game = new Game();
        game.hero = new Point(68, 830);
        BuilderRun run = battles(1, true);
        matchBattle(run, game, 0);
        game.cards = List.of(CARD);
        run.step(game, 3);
        run.step(game, 4);
        game.cards = List.of();
        assertTrue(run.step(game.show(Mark.ENDS), 5).text().contains("Watching stage 1"));

        game.heroCard = HeroCard.READY;
        game.cards = List.of(game.hero, CARD);
        run.step(game, 100);
        run.step(game, 200);
        game.heroCard = HeroCard.UNKNOWN;
        game.cards = List.of();
        game.heroPower = HeroPower.READY;
        assertTrue(run.step(game, 1_000).text().contains("Confirming hero deployment"));
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP, game.hero, DROP), game.taps);

        game.heroCard = HeroCard.READY;
        game.cards = List.of(game.hero);
        run.step(game, 1_750);
        run.step(game, 1_900);
        game.heroCard = HeroCard.DEPLOYED;
        game.cards = List.of();
        assertTrue(run.step(game, 2_100).text().contains("Hero ability"));
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP,
                game.hero, DROP, game.hero, new Point(DROP.x() + 1, DROP.y()), game.hero), game.taps);
    }

    @Test public void overchargeOnlyTapsConfirmedDeployedPekkasOnceAndDuringCombat() throws IOException {
        Game game = new Game();
        BuilderRun run = runner(new Options(Mode.BATTLES, 1, true, true, 0, false, Strategy.FOCUS, true));
        Point notDeployed = new Point(289, 830);
        matchBattle(run, game, 0);
        game.cards = List.of(CARD);
        run.step(game, 3);
        run.step(game, 4);
        game.cards = List.of();
        game.halfHealthPekkas = List.of(CARD, notDeployed);
        run.step(game.show(Mark.ENDS), 5);
        run.step(game.show(), 6);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP), game.taps);
        assertTrue(run.step(game.show(Mark.ENDS), 7).text().contains("Overcharge: 50% HP"));
        run.step(game, 8);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP, CARD), game.taps);
    }

    @Test public void legacyAbilitySettingsMigrateWithoutSelectingRetiredModes() {
        assertEquals(Overcharge.HALF_HP, Overcharge.fromPreference("NEAR_TARGETS", false));
        assertEquals(Overcharge.HALF_HP, Overcharge.fromPreference("LOW_HP", false));
        assertEquals(Overcharge.HALF_HP, Overcharge.fromPreference("", true));
        assertEquals(Overcharge.GAME, Overcharge.fromPreference("GAME", true));
        assertThrows(IllegalArgumentException.class, () -> new Options(Mode.BATTLES, 1, true, true, 0, false,
                Strategy.FOCUS, Overcharge.NEAR_TARGETS, 0, StorageMeter.Goal.BATTLE_LIMIT));
    }

    @Test public void resumeReconfirmsAnAlreadyDeployedPekkaBeforeUsingItsAbility() throws IOException {
        Game game = new Game();
        BuilderRun run = runner(new Options(Mode.BATTLES, 1, true, true, 0, false, Strategy.FOCUS, true));
        matchBattle(run, game, 0);
        game.cards = List.of(CARD);
        run.step(game, 3);
        run.step(game, 4);
        game.cards = List.of();
        game.aboveHalfPekkas = List.of(CARD);
        run.step(game.show(Mark.ENDS, Mark.ATTACKER), 5);

        run.resume();
        assertTrue(run.step(game, 6).text().contains("Resuming"));
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP), game.taps);
        game.aboveHalfPekkas = List.of();
        game.halfHealthPekkas = List.of(CARD);
        assertTrue(run.step(game, 7).text().contains("Overcharge: 50% HP"));
        run.resume();
        run.step(game, 8);
        run.step(game, 9);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP, CARD), game.taps);
    }

    @Test public void compareRecordsAllFourCombinationsAndContinuesAcrossRuns() throws IOException {
        Game game = new Game();
        Journal journal = new Journal();
        Options opts = new Options(Mode.BATTLES, 1, true, true, 0, false, Strategy.COMPARE, false);
        for (int i = 0; i < 8; i++) {
            BuilderRun run = new BuilderRun(opts, journal);
            long now = 3_000L * i;
            matchBattle(run, game, now);
            game.result = i == 1 ? BattleScore.Result.UNREAD : new BattleScore.Result(76_000 + i, 83, 14);
            long base = returnFromResult(run, game, now + 3);
            assertTrue(run.step(game.show(Mark.BASE), base).finished());
            var entry = journal.entries.get(i);
            assertTrue(entry.comparison());
            assertEquals(game.result, entry.result());
        }
        var first = journal.entries.subList(0, 4).stream().map(BattleScore.Entry::plan).toList();
        var second = journal.entries.subList(4, 8).stream().map(BattleScore.Entry::plan).toList();
        assertEquals(4, first.stream().distinct().count());
        for (int i = 0; i < 4; i++) assertEquals(first.get(i), second.get(3 - i));

        var otherProfile = new Options(Mode.BATTLES, 1, true, false, 0, false, Strategy.COMPARE, false);
        BuilderRun another = new BuilderRun(otherProfile, journal);
        matchBattle(another, game, 24_000);
        returnFromResult(another, game, 24_003);
        var entry = journal.entries.get(8);
        assertEquals(otherProfile.profile(), entry.profile());
        assertEquals(first.get(0), entry.plan());
    }

    @Test public void compareUsesAndRecordsTheAssignedAbilityInsteadOfTheManualSetting() throws IOException {
        Journal journal = new Journal();
        Options opts = new Options(Mode.BATTLES, 1, true, true, 0, false, Strategy.COMPARE, false);
        for (int i = 0; i < 3; i++) {
            Game game = new Game();
            BuilderRun run = new BuilderRun(opts, journal);
            matchBattle(run, game, 0);
            game.cards = List.of(CARD);
            run.step(game, 3);
            run.step(game, 4);
            game.cards = List.of();
            run.step(game.show(Mark.ENDS), 5);
            game.halfHealthPekkas = List.of(CARD);
            run.step(game, 6);
            run.step(game, 7);
            returnFromResult(run, game, 8);
        }
        assertEquals(List.of(Overcharge.GAME, Overcharge.HALF_HP, Overcharge.GAME),
                journal.entries.stream().map(entry -> entry.plan().overcharge()).toList());
        assertEquals(List.of(0, 1, 0), journal.entries.stream().map(BattleScore.Entry::lowHp).toList());
    }

    @Test public void liveEditsKeepTheCurrentBattleSnapshotAndApplyBeforeTheNext() throws IOException {
        Game game = new Game();
        Journal journal = new Journal();
        var initial = new Options(Mode.BATTLES, 1, true, true, 0, false);
        var edited = new Options(Mode.CART, 2, false, false, 30, false, Strategy.SPLIT,
                Overcharge.HALF_HP, 0, StorageMeter.Goal.BATTLE_LIMIT);
        BuilderRun run = new BuilderRun(initial, journal);
        matchBattle(run, game, 0);
        assertEquals(initial, run.applySettings(edited));
        long base = returnFromResult(run, game, 3);
        assertFalse(run.step(game.show(Mark.BASE), base).finished());
        assertEquals(edited.forSession(Mode.BATTLES, false), run.applySettings(edited));
        matchBattle(run, game, base + 1);
        long nextBase = returnFromResult(run, game, base + 4);
        assertTrue(run.step(game.show(Mark.BASE), nextBase).finished());
        var before = journal.entries.get(0);
        var after = journal.entries.get(1);
        assertEquals(new BattleScore.Plan(Strategy.FOCUS, Overcharge.GAME), before.plan());
        assertTrue(before.profile().hero());
        assertEquals(new BattleScore.Plan(Strategy.SPLIT, Overcharge.HALF_HP), after.plan());
        assertEquals(edited.profile(), after.profile());
    }

    @Test public void neverLeavesResultIfSavingFailsAndSavesBeforeReturnTap() throws IOException {
        Game game = new Game();
        Journal journal = new Journal();
        BuilderRun run = new BuilderRun(new Options(Mode.BATTLES, 1, true, true, 0, false), journal);
        matchBattle(run, game, 0);
        journal.failure = new IOException("Can't save");
        long settledAt = 3 + run.step(game.show(Mark.RETURN), 3).delayMs();
        assertThrows(IOException.class, () -> run.step(game, settledAt));
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND)), game.taps);
        journal.failure = null;
        assertTrue(run.step(game, settledAt + 1).text().contains("result saved"));
        assertEquals(1, journal.entries.size());
        assertEquals(2, game.taps.size());
        run.step(game, settledAt + 2);
        assertEquals(at(Mark.RETURN), game.taps.get(2));
        run.step(game, settledAt + 3); // A lingering result frame must not be saved or tapped again.
        assertEquals(1, journal.entries.size());
        assertEquals(3, game.taps.size());
    }

    @Test public void ignoresTheAnimatedOpeningScoreAndSavesOnlyAFreshSettledResult() throws IOException {
        Game game = new Game();
        Journal journal = new Journal();
        BuilderRun run = new BuilderRun(new Options(Mode.BATTLES, 1, true, true, 0, false), journal);
        matchBattle(run, game, 0);
        game.result = new BattleScore.Result(148_000, 0, 32);
        long settledAt = 3 + run.step(game.show(Mark.RETURN), 3).delayMs();
        run.step(game, settledAt - 1);
        assertTrue(journal.entries.isEmpty());
        run.step(game.show(), settledAt);
        assertTrue(journal.entries.isEmpty());
        game.result = new BattleScore.Result(148_000, 152, 32);
        run.step(game.show(Mark.RETURN), settledAt + 1);
        assertEquals(game.result, journal.entries.get(0).result());
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND)), game.taps);
        run.step(game, settledAt + 2);
        assertEquals(at(Mark.RETURN), game.taps.get(2));
    }

    @Test public void respectsBattleLimitAndReadsTheBaseBeforeStartingAgain() throws IOException {
        Game game = new Game();
        BuilderRun run = battles(2, true);
        matchBattle(run, game, 0);
        long base = returnFromResult(run, game, 3);
        assertFalse(run.step(game.show(Mark.BASE), base).finished());
        matchBattle(run, game, base + 1);
        long nextBase = returnFromResult(run, game, base + 4);
        assertTrue(run.step(game.show(Mark.BASE), nextBase).finished());
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN),
                at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN)), game.taps);
    }

    @Test public void checksCartOnScheduleAndStartsAnotherBattleOnlyAfterItsVerifiedReturn() throws IOException {
        Game game = new Game();
        BuilderRun run = farm(3, 2, StorageMeter.Goal.BATTLE_LIMIT, false);
        playToBase(run, game, 0);
        playToBase(run, game, 3_000);
        run.step(game.show(Mark.BASE), 6_000);
        run.step(game.show(Mark.BASE, Mark.CART_WORLD), 6_001);
        run.step(game.show(Mark.CART_TITLE, Mark.COLLECT), 6_002);
        run.step(game.show(Mark.CART_CLOSE), 8_750); // A close lookalike without the cart title.
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN),
                at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN), at(Mark.CART_WORLD), at(Mark.COLLECT)), game.taps);
        run.step(game.show(Mark.CART_TITLE, Mark.CART_CLOSE), 9_000);
        run.step(game.show(Mark.BASE, Mark.CART_TITLE), 9_800);
        assertEquals(at(Mark.CART_CLOSE), game.taps.get(game.taps.size() - 1));
        run.step(game.show(Mark.BASE), 10_100);
        assertEquals(9, game.taps.size()); // First confirm the village, then match on a fresh frame.
        assertTrue(playToBase(run, game, 10_800).finished());
        assertEquals(List.of(true), game.pans);
    }

    @Test public void closesAnEmptyCartEvenWhenTheBattleLimitHasBeenReached() throws IOException {
        Game game = new Game();
        BuilderRun run = farm(1, 1, StorageMeter.Goal.BATTLE_LIMIT, false);
        assertFalse(playToBase(run, game, 0).finished());
        run.step(game.show(Mark.BASE, Mark.CART_WORLD), 3_000);
        run.step(game.show(Mark.CART_TITLE, Mark.CART_CLOSE), 6_501);
        run.step(game.show(Mark.BASE), 7_200);
        assertTrue(run.step(game, 7_900).finished());
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN),
                at(Mark.CART_WORLD), at(Mark.CART_CLOSE)), game.taps);
    }

    @Test public void storageGoalWaitsForBothAndChecksAgainAfterCartBeforeMatchmaking() throws IOException {
        Game game = new Game();
        BuilderRun run = farm(1, 1, StorageMeter.Goal.BOTH_95, false);
        game.storage = new StorageMeter.Levels(true, false);
        assertFalse(playToBase(run, game, 0).finished()); // Goal supersedes the ordinary limit.
        run.step(game.show(Mark.BASE, Mark.CART_WORLD), 3_000);
        run.step(game.show(Mark.CART_TITLE, Mark.COLLECT), 3_001);
        run.step(game.show(Mark.CART_TITLE, Mark.CART_CLOSE), 5_702);
        game.storage = new StorageMeter.Levels(true, true);
        run.step(game.show(Mark.BASE), 6_500);
        var end = run.step(game, 7_200);
        assertTrue(end.finished());
        assertTrue(end.goalReached());
        assertTrue(end.text().contains("Goal reached"));
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN),
                at(Mark.CART_WORLD), at(Mark.COLLECT), at(Mark.CART_CLOSE)), game.taps);
    }

    @Test public void defaultStorageGoalStopsBeforeMatchmakingWhenEitherResourceReaches95Percent() throws IOException {
        for (boolean goldFirst : new boolean[]{true, false}) {
            Game game = new Game().show(Mark.BASE);
            game.storage = new StorageMeter.Levels(goldFirst, !goldFirst);
            BuilderRun run = farm(1, 3, StorageMeter.Goal.DEFAULT, false);
            var end = run.step(game, 0);
            assertTrue(end.finished());
            assertTrue(end.goalReached());
            assertTrue(end.text().contains(goldFirst ? "Gold ≈95%+" : "Elixir ≈95%+"));
            assertTrue(game.taps.isEmpty());
        }
    }

    @Test public void defaultStorageGoalContinuesBelow95AndStopsAfterCartFillsElixir() throws IOException {
        Game game = new Game();
        BuilderRun run = farm(1, 1, StorageMeter.Goal.DEFAULT, false);
        game.storage = new StorageMeter.Levels(false, false);
        assertFalse(playToBase(run, game, 0).finished());
        run.step(game.show(Mark.BASE, Mark.CART_WORLD), 3_000);
        run.step(game.show(Mark.CART_TITLE, Mark.COLLECT), 3_001);
        run.step(game.show(Mark.CART_TITLE, Mark.CART_CLOSE), 5_702);
        game.storage = new StorageMeter.Levels(false, true);
        run.step(game.show(Mark.BASE), 6_500);
        var end = run.step(game, 7_200);
        assertTrue(end.finished());
        assertTrue(end.text().contains("Gold below goal · Elixir ≈95%+"));
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN),
                at(Mark.CART_WORLD), at(Mark.COLLECT), at(Mark.CART_CLOSE)), game.taps);
    }

    @Test public void readsStorageWithoutInputAndStopsRatherThanGuessingIfUnreadable() throws IOException {
        Game game = new Game().show(Mark.BASE);
        BuilderRun unreadable = farm(1, 3, StorageMeter.Goal.DEFAULT, false);
        assertFalse(unreadable.step(game, 0).finished());
        assertThrows(IOException.class, () -> unreadable.step(game, 2_501));
        game.storage = new StorageMeter.Levels(false, true);
        assertTrue(farm(1, 3, StorageMeter.Goal.BOTH_90, true).step(game, 0).text().contains("Elixir ≈90%+"));
        game.storage = new StorageMeter.Levels(true, true);
        assertTrue(farm(1, 3, StorageMeter.Goal.BOTH_90, false).step(game, 0).finished());
        assertTrue(game.taps.isEmpty());
        assertTrue(game.pans.isEmpty());
    }

    @Test public void reportsSelected95PercentGoalInsteadOf90Percent() throws IOException {
        Game game = new Game().show(Mark.BASE);
        game.storage = new StorageMeter.Levels(true, true);
        BuilderRun run = runner(new Options(Mode.BATTLES, 1, true, true, 0, true,
                Strategy.FOCUS, Overcharge.GAME, 3, StorageMeter.Goal.BOTH_95));
        assertTrue(run.step(game, 0).text().contains("Gold ≈95%+ · Elixir ≈95%+"));
        assertTrue(game.taps.isEmpty());
        assertTrue(StorageMeter.sampleOffset(95) > StorageMeter.sampleOffset(90));
    }

    private static BuilderRun.Status playToBase(BuilderRun run, Game game, long time) throws IOException {
        matchBattle(run, game, time);
        long base = returnFromResult(run, game, time + 3);
        return run.step(game.show(Mark.BASE), base);
    }

    private static long returnFromResult(BuilderRun run, Game game, long time) throws IOException {
        var wait = run.step(game.show(Mark.RETURN), time);
        assertFalse(wait.finished());
        long settled = time + wait.delayMs();
        assertFalse(run.step(game, settled).finished());
        assertFalse(run.step(game, settled + 1).finished());
        return settled + 2;
    }

    @Test public void resumeRechecksTheBannerAndReselectsTheTroopBeforeDropping() throws IOException {
        Game game = new Game();
        BuilderRun run = battles(1, true);
        matchBattle(run, game, 0);
        game.cards = List.of(CARD);
        run.step(game, 3);
        run.resume();
        assertThrows(BuilderRun.ScreenUnavailable.class, () -> run.step(game.show(Mark.ENDS), 4));
        run.step(game.show(Mark.ENDS, Mark.ATTACKER), 5);
        run.step(game, 6);
        run.step(game, 7);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, CARD, DROP), game.taps);
    }

    @Test public void startTakesOverAnActiveBattleAndOnlyDeploysRecognizedCards() throws IOException {
        Journal journal = new Journal();
        BuilderRun run = new BuilderRun(new Options(Mode.BATTLES, 1, true, true, 0, false,
                Strategy.COMPARE, false), journal);
        Game game = new Game().show(Mark.ENDS, Mark.DEFENDER);
        game.hero = new Point(68, 830);
        game.heroCard = HeroCard.READY;
        game.cards = List.of(game.hero, CARD);
        assertTrue(run.step(game, 0).text().contains("Taking over"));
        assertTrue(game.taps.isEmpty());
        run.step(game, 1);
        run.step(game, 2);
        game.cards = List.of(game.hero);
        run.step(game, 3);
        run.step(game, 4);
        game.cards = List.of();
        game.heroCard = HeroCard.DEPLOYED;
        run.step(game, 5);
        long base = returnFromResult(run, game, 40_000);
        assertTrue(run.step(game.show(Mark.BASE), base).finished());
        assertEquals(List.of(CARD, DROP, game.hero, DROP, at(Mark.RETURN)), game.taps);
        assertEquals(1, journal.entries.size());
        assertTrue(journal.entries.get(0).partial());
        assertFalse(journal.entries.get(0).comparison());
        assertEquals(0, journal.entries.get(0).stages());
    }

    @Test public void checksAnActiveBattleWithoutInputAndRejectsABareCountdown() throws IOException {
        Game game = new Game().show(Mark.STARTS);
        BuilderRun check = runner(new Options(Mode.BATTLES, 1, true, true, 0, true));
        assertThrows(BuilderRun.ScreenUnavailable.class, () -> check.step(game, 0));
        assertTrue(check.step(game.show(Mark.STARTS, Mark.DEFENDER), 1).finished());
        assertTrue(game.taps.isEmpty());
    }

    @Test public void resumeAfterBattleEndsSavesOnceAndDoesNotReplayReturnHome() throws IOException {
        Journal journal = new Journal();
        BuilderRun run = new BuilderRun(new Options(Mode.BATTLES, 1, true, true, 0, false), journal);
        Game game = new Game();
        matchBattle(run, game, 0);
        run.resume();
        long settled = 300_000 + run.step(game.show(Mark.RETURN), 300_000).delayMs();
        run.step(game, settled);
        assertEquals(1, journal.entries.size());
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND)), game.taps);
        run.step(game, settled + 1);
        run.resume();
        assertThrows(BuilderRun.ScreenUnavailable.class, () -> run.step(game, 340_000));
        assertTrue(run.step(game.show(Mark.BASE), 340_001).finished());
        assertTrue(journal.entries.get(0).partial());
        assertEquals(1, journal.entries.size());
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN)), game.taps);
    }

    @Test public void resumeOnTheSecondCountdownRespectsStageTwoOff() throws IOException {
        BuilderRun run = battles(1, false);
        Game game = new Game();
        matchBattle(run, game, 0);
        game.cards = List.of(CARD);
        run.step(game.show(Mark.ENDS), 3);
        run.resume();
        game.show(Mark.STARTS, Mark.DEFENDER);
        assertTrue(run.step(game, 200_000).text().contains("stage 2"));
        run.step(game, 200_001);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD), game.taps);
    }

    @Test public void takeoverWithStageTwoOffDoesNotGuessWhichStageIsRunning() throws IOException {
        BuilderRun run = battles(1, false);
        Game game = new Game().show(Mark.ENDS, Mark.DEFENDER);
        game.cards = List.of(CARD);
        run.step(game, 0);
        assertTrue(run.step(game, 1).text().contains("Watching current stage"));
        assertTrue(game.taps.isEmpty());
    }

    @Test public void resumeDoesNotAssumeStageOneIfCombatHappenedWhilePaused() throws IOException {
        BuilderRun run = battles(1, false);
        Game game = new Game();
        matchBattle(run, game, 0);
        run.resume();
        game.cards = List.of(CARD);
        game.show(Mark.STARTS, Mark.DEFENDER);
        assertTrue(run.step(game, 90_000).text().contains("current stage"));
        assertTrue(run.step(game, 90_001).text().contains("Watching current stage"));
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND)), game.taps);
    }

    @Test public void resumeAfterCollectDoesNotTapItAgainEvenWhenItStillLooksActive() throws IOException {
        BuilderRun run = farm(1, 1, StorageMeter.Goal.BATTLE_LIMIT, false);
        Game game = new Game();
        playToBase(run, game, 0);
        run.step(game.show(Mark.BASE, Mark.CART_WORLD), 3_000);
        run.step(game.show(Mark.CART_TITLE, Mark.COLLECT), 3_001);
        run.resume();
        run.step(game.show(Mark.CART_TITLE, Mark.COLLECT, Mark.CART_CLOSE), 30_000);
        run.step(game.show(Mark.BASE), 31_000);
        assertTrue(run.step(game, 32_000).finished());
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN), at(Mark.CART_WORLD),
                at(Mark.COLLECT), at(Mark.CART_CLOSE)), game.taps);
    }

    @Test public void canceledInputDoesNotAdvanceTheBattle() throws IOException {
        Game game = new Game().show(Mark.BASE);
        BuilderRun run = battles(1, true);
        game.failure = new RunControl.Changed();
        assertThrows(RunControl.Changed.class, () -> run.step(game, 0));
        game.failure = null;
        run.step(game, 1);
        assertEquals(List.of(at(Mark.BASE)), game.taps);
    }

    @Test public void staleEpochCannotStartAnInputProcess() {
        RunControl control = new RunControl();
        int epoch = control.epoch();
        control.invalidate();
        assertThrows(RunControl.Changed.class, () -> control.issue(epoch, () -> {
            throw new AssertionError("Issued a tap after pause or stop");
        }));
    }
}
