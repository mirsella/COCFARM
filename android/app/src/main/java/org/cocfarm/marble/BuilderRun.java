package org.cocfarm.marble;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cocfarm.marble.StorageMeter.Goal;

/** One fresh board and at most one input per step. No Android/UI dependencies. */
final class BuilderRun {
    enum Mode { BATTLES, CART }
    enum Overcharge { GAME, LOW_HP, NEAR_TARGETS }
    enum ChargeCue { WAIT, NEAR_TARGETS, LOW_HP }
    enum HeroCard { UNKNOWN, READY, DEPLOYED }
    enum HeroPower { UNKNOWN, CHARGING, READY }
    enum Strategy {
        FOCUS("Focus"), SPLIT("Split"), COMPARE("A/B");

        final String label;

        Strategy(String label) { this.label = label; }
    }
    enum Mark { BASE, FIND, ATTACK_COOLDOWN, STARTS, ENDS, RETURN, CART_WORLD, CART_TITLE, COLLECT, CART_CLOSE }
    private enum Phase { READY, MENU, SEARCH, DEPLOY, WATCH, RETURNING, CART_FIND, CART_OPEN, CART_VERIFY,
        CART_CLOSE, CART_RETURN }

    private static final int CART_PANS = 5;
    private static final long MENU_WAIT = 8_000;
    private static final long MAX_ATTACK_COOLDOWN = 120_000;
    private static final int HERO_DROPS = 6;
    static final int STORAGE_BATTLE_CAP = 100;

    record Options(Mode mode, int limit, boolean secondStage, boolean hero, int cardOffset, boolean verify,
                   Strategy strategy, Overcharge overcharge, int cartEvery, Goal storageGoal) {
        Options {
            if (limit < 1 || limit > 20 || cardOffset < -80 || cardOffset > 360
                    || cartEvery < 0 || cartEvery > 20 || storageGoal == null
                    || (storageGoal != Goal.BATTLE_LIMIT && cartEvery == 0)) {
                throw new IllegalArgumentException("Run settings out of range");
            }
            if (mode == Mode.BATTLES && !verify && storageGoal == Goal.BATTLE_LIMIT
                    && strategy == Strategy.COMPARE && limit % 2 != 0) {
                throw new IllegalArgumentException("A/B requires an even battle limit");
            }
        }

        Options(Mode mode, int limit, boolean secondStage, boolean hero, int cardOffset, boolean verify) {
            this(mode, limit, secondStage, hero, cardOffset, verify, Strategy.FOCUS, false);
        }

        Options(Mode mode, int limit, boolean secondStage, boolean hero, int cardOffset, boolean verify,
                Strategy strategy, boolean overcharge) {
            this(mode, limit, secondStage, hero, cardOffset, verify, strategy,
                    overcharge ? Overcharge.LOW_HP : Overcharge.GAME, 0, Goal.BATTLE_LIMIT);
        }

        boolean hasStorageGoal() { return storageGoal != Goal.BATTLE_LIMIT; }
        int cap() { return hasStorageGoal() ? STORAGE_BATTLE_CAP : limit; }
    }

    record Point(int x, int y) {}
    record Match(Point point, double score) {}
    record Status(String text, boolean finished, long delayMs) {}

    interface Board {
        Match find(Mark mark);
        List<Point> ready();
        default Point heroSlot() { return null; }
        default HeroCard heroCard() { return HeroCard.UNKNOWN; }
        default HeroPower heroPower(Point card) { return HeroPower.UNKNOWN; }
        Point dropPoint(int lane, int miss) throws IOException;
        default ChargeCue overchargeCue(Point card) throws IOException { return ChargeCue.WAIT; }
        default BattleScore.Result result() { return BattleScore.Result.UNREAD; }
        default StorageMeter.Levels storage() throws IOException { return null; }
        void tap(Point point) throws IOException;
        void panForCart(boolean towardShore) throws IOException;
    }

    final Options options;
    private Phase phase = Phase.READY;
    private long deadline;
    private long readyUntil;
    private int completed;
    private int stage;
    private int cartPans;
    private int lastCartBattle;
    private long storageDeadline;
    private long cooldownLimit;
    private boolean sawEnds;
    private final Map<Point, Long> deployed = new HashMap<>();
    private final Set<Point> charged = new HashSet<>();
    private final Set<Point> nearbyLastFrame = new HashSet<>();
    private final Point[] workingPlaces = new Point[2];
    private final BattleScore scores = new BattleScore();
    private BattleScore.Result pendingScore;
    private Point selected;
    private Point attempted;
    private Point place;
    private int heroMisses;
    private long heroSettleUntil;
    private boolean lateHero;
    private boolean heroPowerSpent;
    private boolean primaryOnly;
    private int lane;
    private int misses;

    BuilderRun(Options options) { this.options = options; }

    void resume() {
        // A user might have changed the selection while paused. Never resume with a map tap.
        selected = null;
        nearbyLastFrame.clear();
    }

    Status step(Board board, long now) throws IOException {
        return switch (phase) {
            case READY -> begin(board, now);
            case MENU -> menu(board, now);
            case SEARCH -> search(board, now);
            case DEPLOY, WATCH -> battle(board, now);
            case RETURNING -> returnToBase(board, now);
            case CART_FIND -> findCart(board, now);
            case CART_OPEN -> openCart(board, now);
            case CART_VERIFY -> verifyCollection(board, now);
            case CART_CLOSE -> closeCart(board, now);
            case CART_RETURN -> cartReturned(board, now);
        };
    }

    private Status begin(Board board, long now) throws IOException {
        if (options.mode == Mode.CART) return findCart(board, now);
        Match base = need(board.find(Mark.BASE), "Open Builder Base and dismiss any dialogs.");
        if (options.verify && !options.hasStorageGoal()) return done("Builder Base recognized. No taps sent.");
        if (!options.verify && cartDue()) {
            cartPans = 0;
            phase = Phase.CART_FIND;
            return findCart(board, now);
        }
        if (completed == options.cap()) return finishBattles(options.hasStorageGoal() ? "Safety cap reached." : "");
        if (options.hasStorageGoal()) {
            StorageMeter.Levels storage = board.storage();
            if (storage == null) {
                if (storageDeadline == 0) storageDeadline = now + 2_500;
                if (now >= storageDeadline) {
                    throw new IOException("Couldn't read both storage bars. Uncover the top-right HUD and retry.");
                }
                return new Status("Reading Builder Gold and Elixir bars", false, 450);
            }
            storageDeadline = 0;
            if (options.verify) return done(storage.summary(options.storageGoal.percent) + " No game input sent.");
            if (options.storageGoal.reached(storage)) {
                return finishBattles("Goal reached: " + storage.summary(options.storageGoal.percent));
            }
        }
        board.tap(base.point);
        phase = Phase.MENU;
        deadline = now + MENU_WAIT;
        cooldownLimit = now + MAX_ATTACK_COOLDOWN;
        return running("Opening Battle menu");
    }

    private boolean cartDue() {
        return options.cartEvery > 0 && completed > lastCartBattle && completed % options.cartEvery == 0;
    }

    private Status findCart(Board board, long now) throws IOException {
        if (board.find(Mark.CART_TITLE) != null) {
            if (options.verify) return done("Elixir Cart recognized. No game input sent.");
            deadline = now + 2_500;
            return openCart(board, now);
        }
        need(board.find(Mark.BASE), "Open Builder Base and dismiss any dialogs.");
        Match cart = board.find(Mark.CART_WORLD);
        if (options.verify) return done(cart != null
                ? "Shoreline cart recognized. No game input sent."
                : "Builder Base recognized. Cart off-screen; no game input sent.");
        if (cart != null) {
            board.tap(cart.point);
            phase = Phase.CART_OPEN;
            deadline = now + 3_500;
            return new Status("Opening Elixir Cart", false, 400);
        }
        if (cartPans == CART_PANS) throw new IOException("Cart not found. Center the first Builder Base area.");
        board.panForCart(cartPans == 0 || cartPans == CART_PANS - 1);
        cartPans++;
        return new Status("Looking along the shoreline", false, 450);
    }

    private Status openCart(Board board, long now) throws IOException {
        if (board.find(Mark.CART_TITLE) != null) {
            Match collect = board.find(Mark.COLLECT);
            if (collect != null) {
                board.tap(collect.point);
                phase = Phase.CART_VERIFY;
                deadline = now + 2_700;
                return new Status("Collecting defense elixir", false, 2_700);
            }
            if (now >= deadline) return finishCart(board, now, "No active Collect button. Check cart and storage.");
        }
        before(now, "Elixir Cart didn't open. Nothing was collected.");
        phase = Phase.CART_OPEN;
        return new Status("Reading Elixir Cart", false, 350);
    }

    private Status verifyCollection(Board board, long now) throws IOException {
        if (now < deadline) return new Status("Collecting defense elixir", false, deadline - now);
        String detail = board.find(Mark.CART_TITLE) == null ? "Collect tapped. Cart could not be rechecked."
                : board.find(Mark.COLLECT) == null ? "Collect tapped. The cart button is now inactive."
                : "Collect tapped. Rewards remain; check storage.";
        return finishCart(board, now, detail);
    }

    private Status finishCart(Board board, long now, String detail) throws IOException {
        if (options.mode == Mode.CART) return done(detail);
        phase = Phase.CART_CLOSE;
        deadline = now + 4_000;
        return closeCart(board, now);
    }

    private Status closeCart(Board board, long now) throws IOException {
        if (board.find(Mark.CART_TITLE) != null) {
            Match close = board.find(Mark.CART_CLOSE);
            if (close != null) {
                board.tap(close.point);
                phase = Phase.CART_RETURN;
                deadline = now + 4_000;
                return new Status("Closing Elixir Cart", false, 600);
            }
        } else if (board.find(Mark.BASE) != null) {
            return cartFinished();
        }
        before(now, "Couldn't close the Elixir Cart. Close it manually and restart.");
        return new Status("Looking for the Elixir Cart close button", false, 450);
    }

    private Status cartReturned(Board board, long now) throws IOException {
        if (board.find(Mark.CART_TITLE) == null && board.find(Mark.BASE) != null) return cartFinished();
        before(now, "Builder Base didn't reappear after the cart. No new battle started.");
        return new Status("Waiting for Builder Base after cart check", false, 450);
    }

    private Status cartFinished() {
        lastCartBattle = completed;
        phase = Phase.READY;
        return new Status("Back at Builder Base after cart check", false, 600);
    }

    private Status menu(Board board, long now) throws IOException {
        before(now, "Find Now didn't appear. No dialog was dismissed.");
        Match match = board.find(Mark.FIND);
        if (match != null) {
            board.tap(match.point);
            phase = Phase.SEARCH;
            deadline = now + 60_000;
            return running("Finding opponent");
        }
        if (board.find(Mark.ATTACK_COOLDOWN) != null) {
            if (now >= cooldownLimit) throw new IOException("Builder Battle cooldown didn't clear in time.");
            deadline = Math.min(now + MENU_WAIT, cooldownLimit);
            return progress("Waiting for the in-game attack cooldown", 1_000);
        }
        return running("Opening Battle menu");
    }

    private Status search(Board board, long now) throws IOException {
        Mark banner = banner(board);
        if (banner != null) {
            enterStage(1, banner, now);
            return running("Reading stage 1");
        }
        before(now, "No Builder Battle appeared. Take over in the game.");
        return running("Finding opponent");
    }

    private Status battle(Board board, long now) throws IOException {
        Match home = board.find(Mark.RETURN);
        if (home != null) {
            if (options.strategy == Strategy.COMPARE) pendingScore = board.result();
            board.tap(home.point);
            phase = Phase.RETURNING;
            deadline = now + 18_000;
            return running("Returning to Builder Base");
        }
        Mark banner = banner(board);
        if (banner == Mark.STARTS && sawEnds && stage == 1) {
            enterStage(2, banner, now);
            return running(options.secondStage ? "Reading stage 2" : "Watching stage 2");
        }
        if (banner == Mark.ENDS) sawEnds = true;
        if (banner != Mark.ENDS || selected != null) nearbyLastFrame.clear();
        before(now, "No Builder Battle result appeared. Take over in the game.");
        Point hero = options.hero ? board.heroSlot() : null;
        if (phase == Phase.WATCH) {
            Status ability = banner == Mark.ENDS ? combatAbility(board, hero, now) : null;
            if (ability != null) return ability;
            if (banner != Mark.ENDS || hero == null || deployed.containsKey(hero) || lateHero
                    || heroMisses == HERO_DROPS || (stage == 2 && !options.secondStage)
                    || board.heroCard() != HeroCard.READY) return running("Watching stage " + stage);
            // If selection or deployment hid the hero's card earlier, give only that card a recovery window.
            lateHero = true;
            readyUntil = now + 12_000;
            phase = Phase.DEPLOY;
        }
        if (banner == null) {
            selected = null;
            if (now > readyUntil) return stopDeploying("Battle banner lost during deployment.");
            return running("Looking for the battle banner");
        }
        List<Point> ready = board.ready();
        if (attempted != null) {
            boolean heroDrop = attempted.equals(hero);
            boolean confirmed = heroDrop ? board.heroCard() == HeroCard.DEPLOYED : !ready.contains(attempted);
            if (confirmed) {
                if (!heroDrop || workingPlaces[lane] == null) workingPlaces[lane] = place;
                deployed.put(attempted, now);
                misses = 0;
            } else if (heroDrop) {
                if (now < heroSettleUntil) return progress("Confirming hero deployment", 180);
                heroMisses++;
            } else {
                workingPlaces[lane] = null;
                if (++misses >= (lane == 1 ? 3 : 6)) {
                    if (lane == 0) return stopDeploying("No legal troop drop found. Take over in the game.");
                    // Keep deploying with the confirmed primary lane if this layout blocks the split.
                    primaryOnly = true;
                    misses = 0;
                }
            }
            attempted = null;
        }
        if (selected == null && banner == Mark.ENDS) {
            Status ability = combatAbility(board, hero, now);
            if (ability != null) return ability;
        }
        if (hero != null && hero.equals(selected) && !ready.contains(hero) && now < heroSettleUntil) {
            return progress("Reading selected hero", 180);
        }
        Point next = nextCard(ready, hero);
        if (next == null) {
            selected = null;
            if (!deployed.isEmpty() || now > readyUntil) {
                return stopDeploying("No ready Builder cards. Check the troop-bar offset.");
            }
            return deploying("Looking for ready cards");
        }
        if (now > readyUntil) return stopDeploying("Deployment timed out. Take over in the game.");
        boolean heroAction;
        if (selected == null || !ready.contains(selected)) {
            board.tap(next);
            selected = next;
            heroAction = selected.equals(hero);
            if (heroAction) heroSettleUntil = now + 1_500;
        } else {
            heroAction = selected.equals(hero);
            // Keep the hero behind the opening troop, without advancing the Split rotation.
            int deployedTroops = deployed.size() - (hero != null && deployed.containsKey(hero) ? 1 : 0);
            lane = strategy() == Strategy.SPLIT && !primaryOnly && !heroAction ? deployedTroops % 2 : 0;
            Point workingPlace = workingPlaces[lane];
            int candidate = heroAction ? heroMisses : misses;
            Point target = workingPlace != null && (!heroAction || candidate == 0)
                    ? workingPlace : board.dropPoint(lane, candidate);
            board.tap(target);
            if (heroAction) heroSettleUntil = now + 1_500;
            place = target;
            attempted = selected;
            selected = null;
        }
        return deploying(heroAction ? "Deploying hero in stage " + stage : "Deploying stage " + stage);
    }

    private Point nextCard(List<Point> ready, Point hero) {
        Point troop = null;
        boolean heroReady = false;
        for (Point card : ready) {
            // A used card can look ready again when its unit dies.
            if (deployed.containsKey(card)) continue;
            if (card.equals(hero)) heroReady = heroMisses < HERO_DROPS;
            else if (!lateHero && troop == null) troop = card;
        }
        return heroReady && (!deployed.isEmpty() || troop == null) ? hero : troop;
    }

    private Status combatAbility(Board board, Point hero, long now) throws IOException {
        if (hero != null && deployed.containsKey(hero)) {
            HeroPower power = board.heroPower(hero);
            if (power == HeroPower.CHARGING) heroPowerSpent = false;
            if (power == HeroPower.READY && !heroPowerSpent) {
                board.tap(hero);
                heroPowerSpent = true;
                nearbyLastFrame.clear();
                return deploying("Hero ability: fully charged");
            }
        }
        ChargeCue cue = activateOvercharge(board, now);
        return cue == ChargeCue.WAIT ? null : overchargeStatus(cue);
    }

    private ChargeCue activateOvercharge(Board board, long now) throws IOException {
        if (options.overcharge == Overcharge.GAME) return ChargeCue.WAIT;
        // Only inspect confirmed, already-deployed slots; never guess from elapsed battle time alone.
        for (var deployment : deployed.entrySet()) {
            Point card = deployment.getKey();
            if (now - deployment.getValue() < 4_000 || charged.contains(card)) continue;
            ChargeCue cue = board.overchargeCue(card);
            if (cue == ChargeCue.WAIT) nearbyLastFrame.remove(card);
            // Require two consecutive reads of a cluster before spending the one-shot ability.
            if (cue == ChargeCue.WAIT || (cue == ChargeCue.NEAR_TARGETS && nearbyLastFrame.add(card))) continue;
            board.tap(card);
            charged.add(card);
            nearbyLastFrame.clear();
            return cue;
        }
        return ChargeCue.WAIT;
    }

    private Status overchargeStatus(ChargeCue cue) {
        return deploying(cue == ChargeCue.NEAR_TARGETS ? "Overcharge: near targets" : "Overcharge: low HP");
    }
    private Status stopDeploying(String failure) throws IOException {
        if (deployed.isEmpty()) throw new IOException(failure);
        phase = Phase.WATCH;
        return running("Watching stage " + stage);
    }

    private Status returnToBase(Board board, long now) throws IOException {
        if (board.find(Mark.BASE) != null) {
            if (options.strategy == Strategy.COMPARE) {
                scores.add(strategy(), pendingScore);
                pendingScore = null;
            }
            completed++;
            if (completed == options.cap() && !cartDue()) {
                return finishBattles(options.hasStorageGoal() ? "Safety cap reached." : "");
            }
            phase = Phase.READY; // Verify the village again before another tap.
            if (options.hasStorageGoal() || cartDue()) {
                return new Status("Battle " + completed + " complete", false, 900);
            }
            if (options.strategy == Strategy.COMPARE) return new Status(scores.summary(), false, 340);
            return running("Battle " + completed + " complete");
        }
        before(now, "Builder Base didn't reappear. Check for an in-game dialog.");
        return running("Returning to Builder Base");
    }

    private Status finishBattles(String reason) {
        String summary = options.strategy == Strategy.COMPARE && completed > 0 ? scores.summary()
                : "Finished " + completed + (completed == 1 ? " battle." : " battles.");
        return done(reason.isEmpty() ? summary : summary + "\n" + reason);
    }

    private void enterStage(int number, Mark banner, long now) {
        stage = number;
        sawEnds = banner == Mark.ENDS;
        deployed.clear();
        charged.clear();
        nearbyLastFrame.clear();
        heroMisses = 0;
        lateHero = false;
        heroPowerSpent = false;
        workingPlaces[0] = workingPlaces[1] = null;
        selected = attempted = place = null;
        primaryOnly = false;
        lane = 0;
        misses = 0;
        phase = number == 1 || options.secondStage ? Phase.DEPLOY : Phase.WATCH;
        readyUntil = now + 40_000;
        deadline = now + 270_000;
    }

    private static Mark banner(Board board) {
        Match starts = board.find(Mark.STARTS);
        Match ends = board.find(Mark.ENDS);
        if (starts == null && ends == null) return null;
        if (ends == null || (starts != null && starts.score > ends.score)) return Mark.STARTS;
        return Mark.ENDS;
    }

    private static Match need(Match match, String message) throws IOException {
        if (match == null) throw new IOException(message);
        return match;
    }

    private void before(long time, String message) throws IOException {
        if (time > deadline) throw new IOException(message);
    }

    private Status running(String detail) {
        return progress(detail, 340);
    }

    private Status deploying(String detail) { return progress(detail, 70); }

    private Status progress(String detail, long delayMs) {
        String tag = options.strategy == Strategy.COMPARE ? "  " + strategy().label : "";
        return new Status((completed + 1) + "/" + options.cap() + tag + "  ·  " + detail, false, delayMs);
    }

    private Strategy strategy() {
        if (options.strategy != Strategy.COMPARE) return options.strategy;
        // Reverse the order every other pair to balance gradual changes in matchmaking.
        return completed % 4 == 0 || completed % 4 == 3 ? Strategy.FOCUS : Strategy.SPLIT;
    }

    private static Status done(String detail) { return new Status(detail, true, 0); }
}
