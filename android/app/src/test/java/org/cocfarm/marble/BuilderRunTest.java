package org.cocfarm.marble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import org.cocfarm.marble.BuilderRun.ChargeCue;
import org.cocfarm.marble.BuilderRun.HeroCard;
import org.cocfarm.marble.BuilderRun.HeroPower;
import org.cocfarm.marble.BuilderRun.Mark;
import org.cocfarm.marble.BuilderRun.Match;
import org.cocfarm.marble.BuilderRun.Mode;
import org.cocfarm.marble.BuilderRun.Options;
import org.cocfarm.marble.BuilderRun.Overcharge;
import org.cocfarm.marble.BuilderRun.Point;
import org.cocfarm.marble.BuilderRun.Strategy;
import org.junit.Test;

public class BuilderRunTest {
    private static final Point CARD = new Point(185, 830);
    private static final Point DROP = new Point(636, 468);

    private static final class Game implements BuilderRun.Board {
        final EnumMap<Mark, Match> marks = new EnumMap<>(Mark.class);
        final List<Point> taps = new ArrayList<>();
        final List<Boolean> pans = new ArrayList<>();
        List<Point> cards = List.of();
        List<Point> lowHealthPekkas = List.of();
        List<Point> nearbyPekkas = List.of();
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
        @Override public ChargeCue overchargeCue(Point card) {
            if (nearbyPekkas.contains(card)) return ChargeCue.NEAR_TARGETS;
            return lowHealthPekkas.contains(card) ? ChargeCue.LOW_HP : ChargeCue.WAIT;
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
        return new BuilderRun(new Options(Mode.BATTLES, limit, second, true, 0, false));
    }

    private static BuilderRun cart(boolean check) {
        return new BuilderRun(new Options(Mode.CART, 1, true, true, 0, check));
    }

    private static BuilderRun farm(int limit, int every, StorageMeter.Goal goal, boolean check) {
        return new BuilderRun(new Options(Mode.BATTLES, limit, true, true, 0, check,
                Strategy.FOCUS, Overcharge.GAME, every, goal));
    }

    private static void matchBattle(BuilderRun run, Game game, long start) throws IOException {
        run.step(game.show(Mark.BASE), start);
        run.step(game.show(Mark.FIND), start + 1);
        run.step(game.show(Mark.STARTS), start + 2);
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
            BuilderRun check = new BuilderRun(new Options(mode, 1, true, true, 0, true));
            game.show(mode == Mode.BATTLES ? Mark.BASE : Mark.CART_TITLE, Mark.COLLECT);
            assertTrue(check.step(game, 0).finished());
            assertEquals(List.of(), game.taps);
        }
    }

    @Test public void neverCollectsWithoutRecognizedCartTitle() {
        Game game = new Game().show(Mark.COLLECT);
        BuilderRun run = new BuilderRun(new Options(Mode.CART, 1, true, true, 0, false));
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
        assertFalse(run.step(game.show(Mark.RETURN), 9).finished());
        assertTrue(run.step(game.show(Mark.BASE), 10).finished());
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
        assertFalse(run.step(game.show(Mark.RETURN), 120_000).finished());
        assertTrue(run.step(game.show(Mark.BASE), 120_001).finished());
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP, second, at(Mark.RETURN)),
                game.taps);
    }

    @Test public void splitAlternatesDropsAndFallsBackIfTheSecondaryLaneIsBlocked() throws IOException {
        Game game = new Game();
        BuilderRun run = new BuilderRun(new Options(Mode.BATTLES, 1, true, true, 0, false, Strategy.SPLIT, false));
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
        BuilderRun run = new BuilderRun(new Options(Mode.BATTLES, 1, true, true, 0, false, Strategy.SPLIT, false));
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
        BuilderRun run = new BuilderRun(new Options(Mode.BATTLES, 1, true, true, 0, false, Strategy.SPLIT, false));
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
        BuilderRun run = new BuilderRun(new Options(Mode.BATTLES, 1, true, true, 0, false, Strategy.FOCUS, true));
        Point notDeployed = new Point(289, 830);
        matchBattle(run, game, 0);
        game.cards = List.of(CARD);
        run.step(game, 3);
        run.step(game, 4);
        game.cards = List.of();
        game.lowHealthPekkas = List.of(CARD, notDeployed);
        run.step(game.show(Mark.ENDS), 5);
        run.step(game, 3_999);
        run.step(game.show(), 4_999);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP), game.taps);
        run.step(game.show(Mark.ENDS), 5_001);
        run.step(game, 6_001);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP, CARD), game.taps);
    }

    @Test public void nearbyOverchargeRequiresConsecutiveSightings() throws IOException {
        Game game = new Game();
        BuilderRun run = new BuilderRun(new Options(Mode.BATTLES, 1, true, true, 0, false,
                Strategy.FOCUS, Overcharge.NEAR_TARGETS, 0, StorageMeter.Goal.BATTLE_LIMIT));
        matchBattle(run, game, 0);
        game.cards = List.of(CARD);
        run.step(game, 3);
        run.step(game, 4);
        game.cards = List.of();
        run.step(game.show(Mark.ENDS), 5);
        game.nearbyPekkas = List.of(CARD);
        run.step(game, 4_100);
        run.step(game.show(), 4_200);
        run.step(game.show(Mark.ENDS), 4_300);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP), game.taps);
        assertTrue(run.step(game, 4_400).text().contains("Overcharge: near targets"));
        run.step(game, 4_500);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, DROP, CARD), game.taps);
    }

    @Test public void compareBalancesOrderAndAveragesOnlyObservedResults() throws IOException {
        Game game = new Game();
        BuilderRun run = new BuilderRun(new Options(Mode.BATTLES, 4, true, true, 0, false, Strategy.COMPARE, false));
        String[] sequence = {"Focus", "Split", "Split", "Focus"};
        for (int i = 0; i < sequence.length; i++) {
            long now = 10L * i;
            assertTrue(run.step(game.show(Mark.BASE), now).text().contains(sequence[i]));
            run.step(game.show(Mark.FIND), now + 1);
            run.step(game.show(Mark.STARTS), now + 2);
            game.result = switch (i) {
                case 0 -> new BattleScore.Result(76_000, 83);
                case 1 -> BattleScore.Result.UNREAD;
                case 2 -> new BattleScore.Result(90_000, 180);
                default -> new BattleScore.Result(80_000, 101);
            };
            run.step(game.show(Mark.RETURN), now + 3);
            var result = run.step(game.show(Mark.BASE), now + 4);
            if (i == 3) {
                assertTrue(result.finished());
                assertTrue(result.text().contains("Focus (2): 78,000 gold · 92%"));
                assertTrue(result.text().contains("Split (2): 90,000 gold · 180%  [1/1]"));
            }
        }
    }

    @Test public void respectsBattleLimitAndReadsTheBaseBeforeStartingAgain() throws IOException {
        Game game = new Game();
        BuilderRun run = battles(2, true);
        matchBattle(run, game, 0);
        run.step(game.show(Mark.RETURN), 3);
        assertFalse(run.step(game.show(Mark.BASE), 4).finished());
        matchBattle(run, game, 5);
        run.step(game.show(Mark.RETURN), 8);
        assertTrue(run.step(game.show(Mark.BASE), 9).finished());
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN),
                at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN)), game.taps);
    }

    @Test public void checksCartOnScheduleAndStartsAnotherBattleOnlyAfterItsVerifiedReturn() throws IOException {
        Game game = new Game();
        BuilderRun run = farm(3, 2, StorageMeter.Goal.BATTLE_LIMIT, false);
        playToBase(run, game, 0);
        playToBase(run, game, 10);
        run.step(game.show(Mark.BASE), 20);
        run.step(game.show(Mark.BASE, Mark.CART_WORLD), 21);
        run.step(game.show(Mark.CART_TITLE, Mark.COLLECT), 22);
        run.step(game.show(Mark.CART_CLOSE), 2_750); // A close lookalike without the cart title.
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN),
                at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN), at(Mark.CART_WORLD), at(Mark.COLLECT)), game.taps);
        run.step(game.show(Mark.CART_TITLE, Mark.CART_CLOSE), 3_000);
        run.step(game.show(Mark.BASE, Mark.CART_TITLE), 3_800);
        assertEquals(at(Mark.CART_CLOSE), game.taps.get(game.taps.size() - 1));
        run.step(game.show(Mark.BASE), 4_100);
        assertEquals(9, game.taps.size()); // First confirm the village, then match on a fresh frame.
        assertTrue(playToBase(run, game, 4_800).finished());
        assertEquals(List.of(true), game.pans);
    }

    @Test public void closesAnEmptyCartEvenWhenTheBattleLimitHasBeenReached() throws IOException {
        Game game = new Game();
        BuilderRun run = farm(1, 1, StorageMeter.Goal.BATTLE_LIMIT, false);
        assertFalse(playToBase(run, game, 0).finished());
        run.step(game.show(Mark.BASE, Mark.CART_WORLD), 10);
        run.step(game.show(Mark.CART_TITLE, Mark.CART_CLOSE), 3_511);
        run.step(game.show(Mark.BASE), 4_200);
        assertTrue(run.step(game, 4_900).finished());
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), at(Mark.RETURN),
                at(Mark.CART_WORLD), at(Mark.CART_CLOSE)), game.taps);
    }

    @Test public void storageGoalWaitsForBothAndChecksAgainAfterCartBeforeMatchmaking() throws IOException {
        Game game = new Game();
        BuilderRun run = farm(1, 1, StorageMeter.Goal.BOTH_95, false);
        game.storage = new StorageMeter.Levels(true, false);
        assertFalse(playToBase(run, game, 0).finished()); // Goal supersedes the ordinary limit.
        run.step(game.show(Mark.BASE, Mark.CART_WORLD), 10);
        run.step(game.show(Mark.CART_TITLE, Mark.COLLECT), 11);
        run.step(game.show(Mark.CART_TITLE, Mark.CART_CLOSE), 2_712);
        game.storage = new StorageMeter.Levels(true, true);
        run.step(game.show(Mark.BASE), 3_500);
        var end = run.step(game, 4_200);
        assertTrue(end.finished());
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
            assertTrue(end.text().contains(goldFirst ? "Gold ≈95%+" : "Elixir ≈95%+"));
            assertTrue(game.taps.isEmpty());
        }
    }

    @Test public void defaultStorageGoalContinuesBelow95AndStopsAfterCartFillsElixir() throws IOException {
        Game game = new Game();
        BuilderRun run = farm(1, 1, StorageMeter.Goal.DEFAULT, false);
        game.storage = new StorageMeter.Levels(false, false);
        assertFalse(playToBase(run, game, 0).finished());
        run.step(game.show(Mark.BASE, Mark.CART_WORLD), 10);
        run.step(game.show(Mark.CART_TITLE, Mark.COLLECT), 11);
        run.step(game.show(Mark.CART_TITLE, Mark.CART_CLOSE), 2_712);
        game.storage = new StorageMeter.Levels(false, true);
        run.step(game.show(Mark.BASE), 3_500);
        var end = run.step(game, 4_200);
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
        BuilderRun run = new BuilderRun(new Options(Mode.BATTLES, 1, true, true, 0, true,
                Strategy.FOCUS, Overcharge.GAME, 3, StorageMeter.Goal.BOTH_95));
        assertTrue(run.step(game, 0).text().contains("Gold ≈95%+ · Elixir ≈95%+"));
        assertTrue(game.taps.isEmpty());
        assertTrue(StorageMeter.sampleOffset(95) > StorageMeter.sampleOffset(90));
    }

    private static BuilderRun.Status playToBase(BuilderRun run, Game game, long time) throws IOException {
        matchBattle(run, game, time);
        run.step(game.show(Mark.RETURN), time + 3);
        return run.step(game.show(Mark.BASE), time + 4);
    }

    @Test public void resumeRechecksTheBannerAndReselectsTheTroopBeforeDropping() throws IOException {
        Game game = new Game();
        BuilderRun run = battles(1, true);
        matchBattle(run, game, 0);
        game.cards = List.of(CARD);
        run.step(game, 3);
        run.resume();
        run.step(game.show(), 4);
        run.step(game.show(Mark.ENDS), 5);
        run.step(game, 6);
        assertEquals(List.of(at(Mark.BASE), at(Mark.FIND), CARD, CARD, DROP), game.taps);
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
