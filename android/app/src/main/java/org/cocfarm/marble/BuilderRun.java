package org.cocfarm.marble;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cocfarm.marble.BattleScore.Entry;
import org.cocfarm.marble.BattleScore.Journal;
import org.cocfarm.marble.BattleScore.Plan;
import org.cocfarm.marble.StorageMeter.Goal;

/** One fresh board and at most one input per step. No Android/UI dependencies. */
final class BuilderRun {
    enum Mode { BATTLES, CART }
    enum Overcharge {
        GAME("Game"), HALF_HP("50% HP"),
        // Kept for reading existing battle history, not valid run settings.
        LOW_HP("Low HP (old)"), NEAR_TARGETS("Nearby (old)");

        final String label;
        Overcharge(String label) { this.label = label; }

        static Overcharge fromPreference(String stored, boolean legacyEnabled) {
            return switch (stored) {
                case "HALF_HP", "LOW_HP", "NEAR_TARGETS" -> HALF_HP;
                case "GAME" -> GAME;
                default -> legacyEnabled ? HALF_HP : GAME;
            };
        }
    }
    enum HeroCard { UNKNOWN, READY, DEPLOYED }
    enum HeroPower { UNKNOWN, CHARGING, READY }
    enum Strategy {
        FOCUS("Focus"), SPLIT("Split"), COMPARE("Compare");

        final String label;

        Strategy(String label) { this.label = label; }
    }
    enum Mark { BASE, FIND, ATTACK_COOLDOWN, STARTS, ENDS, ATTACKER, DEFENDER, SPEED_1X, RETURN,
        CART_WORLD, CART_TITLE, COLLECT, CART_CLOSE }
    private enum Phase { READY, MENU, SEARCH, DEPLOY, WATCH, RESULT_PENDING, RESULT, RETURNING, CART_FIND,
        CART_OPEN, CART_VERIFY, CART_CLOSE, CART_RETURN }

    private static final int CART_PANS = 5;
    private static final long MENU_WAIT = 8_000;
    private static final long MAX_ATTACK_COOLDOWN = 120_000;
    private static final long RESULT_SETTLE = 2_500;
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
            if (overcharge != Overcharge.GAME && overcharge != Overcharge.HALF_HP) {
                throw new IllegalArgumentException("Choose GAME or HALF_HP for Overcharge.");
            }
        }

        Options(Mode mode, int limit, boolean secondStage, boolean hero, int cardOffset, boolean verify) {
            this(mode, limit, secondStage, hero, cardOffset, verify, Strategy.FOCUS, false);
        }

        Options(Mode mode, int limit, boolean secondStage, boolean hero, int cardOffset, boolean verify,
                Strategy strategy, boolean overcharge) {
            this(mode, limit, secondStage, hero, cardOffset, verify, strategy,
                    overcharge ? Overcharge.HALF_HP : Overcharge.GAME, 0, Goal.BATTLE_LIMIT);
        }

        boolean hasStorageGoal() { return storageGoal != Goal.BATTLE_LIMIT; }
        int cap() { return hasStorageGoal() ? STORAGE_BATTLE_CAP : limit; }
        BattleScore.Profile profile() { return new BattleScore.Profile(hero, secondStage, cardOffset); }

        Options forSession(Mode sessionMode, boolean readOnly) {
            return new Options(sessionMode, limit, secondStage, hero, cardOffset, readOnly,
                    strategy, overcharge, cartEvery, storageGoal);
        }
    }

    record Point(int x, int y) {}
    record Match(Point point, double score) {}
    enum PekkaHealth { UNKNOWN, ABOVE_HALF, AT_OR_BELOW_HALF }
    record Status(String text, boolean finished, long delayMs, boolean goalReached) {
        Status(String text, boolean finished, long delayMs) { this(text, finished, delayMs, false); }
    }
    static final class ScreenUnavailable extends IOException {
        ScreenUnavailable(String message) { super(message); }
    }
    record Diagnostic(String phase, int battle, int stage, Plan plan, Point selected, Point attempted,
                      Point drop, List<Point> deployed, int heroMisses, boolean heroRecovery, int lane) {}

    interface Board {
        Match find(Mark mark);
        List<Point> ready();
        default Point heroSlot() { return null; }
        default HeroCard heroCard() { return HeroCard.UNKNOWN; }
        default HeroPower heroPower(Point card) { return HeroPower.UNKNOWN; }
        Point dropPoint(int lane, int miss) throws IOException;
        default PekkaHealth pekkaHealth(Point card) { return PekkaHealth.UNKNOWN; }
        default BattleScore.Result result() throws IOException { return BattleScore.Result.UNREAD; }
        default StorageMeter.Levels storage() throws IOException { return null; }
        void tap(Point point) throws IOException;
        void panForCart(boolean towardShore) throws IOException;
    }

    private Options options;
    private Options requested;
    private final Journal journal;
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
    private boolean recheck;
    private boolean partial;
    private boolean speedTapped;
    private final Map<Point, Long> deployed = new HashMap<>();
    private final Set<Point> charged = new HashSet<>();
    private final Point[] workingPlaces = new Point[2];
    private Plan plan;
    private long battleStarted;
    private long resultSeen;
    private int healthTriggered;
    private int heroPowers;
    private int splitFallbacks;
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

    BuilderRun(Options options, Journal journal) {
        this.options = requested = options;
        this.journal = journal;
    }

    Options applySettings(Options edited) {
        if (!options.verify) {
            requested = edited.forSession(options.mode, false);
            if (phase == Phase.READY) useRequestedSettings();
        }
        return options;
    }

    private void useRequestedSettings() {
        if (options.storageGoal != requested.storageGoal) storageDeadline = 0;
        options = requested;
    }

    void resume() {
        recheck = true;
    }

    Diagnostic diagnostic() {
        return new Diagnostic(phase.name(), completed + 1, stage, plan, selected, attempted, place,
                List.copyOf(deployed.keySet()), heroMisses, lateHero, lane);
    }

    Status step(Board board, long now) throws IOException {
        if (recheck) {
            Status status = recheckScreen(board, now);
            recheck = false;
            if (status != null) return status;
        }
        return switch (phase) {
            case READY -> begin(board, now);
            case MENU -> menu(board, now);
            case SEARCH -> search(board, now);
            case DEPLOY, WATCH -> battle(board, now);
            case RESULT_PENDING -> readResult(board, now);
            case RESULT -> leaveResult(board, now);
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
        Match base = board.find(Mark.BASE);
        if (base == null) {
            Mark live = liveBanner(board);
            if (live == null) throw new ScreenUnavailable("Open Builder Base or an active Builder Battle.");
            if (options.verify) return done("Live Builder Battle recognized. No game input sent.");
            startPlan();
            partial = true;
            battleStarted = now;
            enterStage(0, live, now);
            return running("Taking over the current Builder Battle");
        }
        if (options.verify && !options.hasStorageGoal()) return done("Builder Base recognized. No taps sent.");
        if (!options.verify && cartDue()) {
            cartPans = 0;
            phase = Phase.CART_FIND;
            return findCart(board, now);
        }
        if (completed >= options.cap()) return finishBattles(options.hasStorageGoal() ? "Safety cap reached." : "");
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
                return finishBattles("Goal reached: " + storage.summary(options.storageGoal.percent), true);
            }
        }
        startPlan();
        board.tap(base.point);
        phase = Phase.MENU;
        deadline = now + MENU_WAIT;
        cooldownLimit = now + MAX_ATTACK_COOLDOWN;
        return running("Opening Battle menu");
    }

    private void startPlan() throws IOException {
        plan = options.strategy == Strategy.COMPARE ? BattleScore.nextPlan(journal.comparisonCount(options.profile()))
                : new Plan(options.strategy, options.overcharge);
        healthTriggered = heroPowers = splitFallbacks = 0;
        partial = false;
    }

    private Status recheckScreen(Board board, long now) throws IOException {
        // The game clock kept running, and the user could have changed the card selection.
        // Never confirm or send a map tap using a selection from before the interruption.
        selected = attempted = null;
        if (phase == Phase.DEPLOY || phase == Phase.WATCH) {
            partial = true;
            if (board.find(Mark.RETURN) != null) return waitForResult(now);
            if (board.find(Mark.BASE) != null) {
                phase = Phase.READY;
                useRequestedSettings();
                return running("Back at Builder Base; no result was visible");
            }
            Mark live = liveBanner(board);
            if (live == null) throw new ScreenUnavailable("Return to the active Builder Battle or its result.");
            boolean nextStage = live == Mark.STARTS && sawEnds && stage != 2;
            // Either banner may belong to stage two if we missed the transition.
            int number = nextStage || stage == 2 ? 2 : 0;
            var tapped = new HashSet<>(charged);
            boolean spent = heroPowerSpent;
            boolean spedUp = speedTapped;
            var confirmed = new HashMap<Point, Long>();
            if (!nextStage && number == 2) confirmed.putAll(deployed);
            else if (!nextStage && options.secondStage && plan.overcharge() == Overcharge.HALF_HP) {
                // An unknown stage may have a new troop bar. Keep only positively re-read units.
                for (Point card : deployed.keySet()) {
                    if (board.pekkaHealth(card) != PekkaHealth.UNKNOWN) confirmed.put(card, now);
                }
            }
            enterStage(number, live, now);
            if (!nextStage) {
                charged.addAll(tapped);
                heroPowerSpent = spent;
                speedTapped = spedUp;
            }
            deployed.putAll(confirmed);
            return running("Resuming " + stageLabel());
        }
        if (phase == Phase.READY) {
            storageDeadline = 0;
            return begin(board, now);
        }
        if (phase == Phase.SEARCH || phase == Phase.MENU) {
            if (board.find(Mark.BASE) != null) {
                phase = Phase.READY;
                useRequestedSettings();
                return running("Back at Builder Base");
            }
            Mark live = liveBanner(board);
            if (live != null) {
                partial = true;
                battleStarted = now;
                enterStage(0, live, now);
                return running("Taking over the current Builder Battle");
            }
            if (phase == Phase.SEARCH && board.find(Mark.RETURN) != null) {
                partial = true;
                stage = 0;
                battleStarted = now;
                return waitForResult(now);
            }
            if (board.find(Mark.FIND) != null || board.find(Mark.ATTACK_COOLDOWN) != null) {
                phase = Phase.MENU;
                deadline = now + MENU_WAIT;
                cooldownLimit = now + MAX_ATTACK_COOLDOWN;
                return running("Builder Battle menu recognized");
            }
            throw new ScreenUnavailable("Return to Builder Base, the battle menu, or the active battle.");
        }
        if (phase == Phase.RESULT_PENDING) {
            if (board.find(Mark.RETURN) != null) return null;
            if (board.find(Mark.BASE) != null) {
                phase = Phase.READY;
                useRequestedSettings();
                return running("Back at Builder Base; no result was saved");
            }
            throw new ScreenUnavailable("Return to the battle result or Builder Base.");
        }
        if (phase == Phase.RESULT || phase == Phase.RETURNING) {
            if (board.find(Mark.BASE) != null) return returnToBase(board, now);
            if (phase == Phase.RESULT && board.find(Mark.RETURN) != null) return null;
            throw new ScreenUnavailable("Return to Builder Base or finish leaving the result screen.");
        }
        if (board.find(Mark.CART_TITLE) != null) {
            if (phase == Phase.CART_RETURN) throw new ScreenUnavailable("Close the Elixir Cart to continue.");
            if (phase == Phase.CART_FIND || phase == Phase.CART_OPEN || phase == Phase.CART_CLOSE) {
                deadline = now + 4_000;
            }
            return null;
        }
        if (board.find(Mark.BASE) != null) {
            if (phase == Phase.CART_VERIFY || phase == Phase.CART_CLOSE || phase == Phase.CART_RETURN) {
                return options.mode == Mode.CART ? done("Collect tapped. Back at Builder Base.") : cartFinished();
            }
            phase = Phase.CART_FIND;
            return running("Back at Builder Base; looking for the cart");
        }
        throw new ScreenUnavailable("Return to Builder Base or its Elixir Cart.");
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
        if (board.find(Mark.BASE) == null) {
            throw new ScreenUnavailable("Open Builder Base or its Elixir Cart.");
        }
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
            battleStarted = now;
            enterStage(1, banner, now);
            return running("Reading stage 1");
        }
        before(now, "No Builder Battle appeared. Take over in the game.");
        return running("Finding opponent");
    }

    private Status battle(Board board, long now) throws IOException {
        if (board.find(Mark.RETURN) != null) return waitForResult(now);
        Mark banner = partial ? liveBanner(board) : banner(board);
        if (banner == Mark.STARTS && sawEnds && stage != 2) {
            enterStage(2, banner, now);
            return running(options.secondStage ? "Reading stage 2" : "Watching stage 2");
        }
        if (banner == Mark.ENDS) sawEnds = true;
        before(now, "No Builder Battle result appeared. Take over in the game.");
        boolean deployAllowed = stage == 1 || options.secondStage;
        Point hero = options.hero && deployAllowed ? board.heroSlot() : null;
        if (partial && banner != null && hero != null && !hero.equals(attempted)
                && board.heroCard() == HeroCard.DEPLOYED) deployed.putIfAbsent(hero, now);
        if (phase == Phase.WATCH) {
            Status ability = deployAllowed && banner == Mark.ENDS ? combatAbility(board, hero, now) : null;
            if (ability != null) return ability;
            boolean recoverHero = banner == Mark.ENDS && hero != null && !deployed.containsKey(hero)
                    && !lateHero && heroMisses < HERO_DROPS && board.heroCard() == HeroCard.READY;
            if (!recoverHero) {
                if (banner == Mark.ENDS && !speedTapped) {
                    Match speed = board.find(Mark.SPEED_1X);
                    if (speed != null) {
                        board.tap(speed.point);
                        speedTapped = true;
                        return running("Battle speed: tapped 1× for 4×");
                    }
                }
                return running("Watching " + stageLabel());
            }
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
                    splitFallbacks++;
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
            lane = plan.deployment() == Strategy.SPLIT && !primaryOnly && !heroAction ? deployedTroops % 2 : 0;
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
        return deploying(heroAction ? "Deploying hero in " + stageLabel() : "Deploying " + stageLabel());
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
                heroPowers++;
                return deploying("Hero ability: fully charged");
            }
        }
        if (plan.overcharge() != Overcharge.HALF_HP) return null;
        // Wait for a fresh combat frame after confirming deployment, not a timer.
        for (var deployment : deployed.entrySet()) {
            Point card = deployment.getKey();
            if (now <= deployment.getValue() || charged.contains(card)
                    || board.pekkaHealth(card) != PekkaHealth.AT_OR_BELOW_HALF) continue;
            board.tap(card);
            healthTriggered++;
            charged.add(card);
            return deploying("Overcharge: 50% HP");
        }
        return null;
    }
    private Status stopDeploying(String failure) throws IOException {
        if (deployed.isEmpty() && !partial) throw new IOException(failure);
        phase = Phase.WATCH;
        return running("Watching " + stageLabel());
    }

    private Status waitForResult(long now) {
        // Return Home is visible before the damage percentage finishes counting up.
        resultSeen = now;
        phase = Phase.RESULT_PENDING;
        deadline = now + 18_000;
        return progress("Waiting for the score to settle", RESULT_SETTLE);
    }

    private Status readResult(Board board, long now) throws IOException {
        long remaining = resultSeen + RESULT_SETTLE - now;
        if (remaining > 0) return progress("Waiting for the score to settle", remaining);
        if (board.find(Mark.RETURN) == null) {
            before(now, "Builder Battle result disappeared before it could be saved.");
            return running("Looking for the battle result");
        }
        journal.record(new Entry(plan, options.strategy == Strategy.COMPARE && !partial,
                options.profile(), stage, resultSeen - battleStarted, healthTriggered, 0, heroPowers, splitFallbacks,
                board.result(), partial));
        phase = Phase.RESULT;
        deadline = now + 18_000;
        return running("Battle result saved");
    }

    private Status leaveResult(Board board, long now) throws IOException {
        Match home = board.find(Mark.RETURN);
        if (home != null) {
            board.tap(home.point);
            phase = Phase.RETURNING;
            deadline = now + 18_000;
            return running("Returning to Builder Base");
        }
        return returnToBase(board, now);
    }

    private Status returnToBase(Board board, long now) throws IOException {
        if (board.find(Mark.BASE) != null) {
            completed++;
            useRequestedSettings();
            if (completed >= options.cap() && !cartDue()) {
                return finishBattles(options.hasStorageGoal() ? "Safety cap reached." : "");
            }
            phase = Phase.READY; // Verify the village again before another tap.
            if (options.hasStorageGoal() || cartDue()) {
                return new Status("Battle " + completed + " complete", false, 900);
            }
            return running("Battle " + completed + " complete");
        }
        if (now >= deadline) {
            throw new ScreenUnavailable("Builder Base didn't reappear. Check for an in-game dialog.");
        }
        return running("Returning to Builder Base");
    }

    private Status finishBattles(String reason) {
        return finishBattles(reason, false);
    }

    private Status finishBattles(String reason, boolean goalReached) {
        String summary = "Finished " + completed + (completed == 1 ? " battle." : " battles.");
        return new Status(reason.isEmpty() ? summary : summary + "\n" + reason, true, 0, goalReached);
    }

    private void enterStage(int number, Mark banner, long now) {
        stage = number;
        sawEnds = banner == Mark.ENDS;
        speedTapped = false;
        deployed.clear();
        charged.clear();
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

    static Mark liveBanner(Board board) {
        Mark banner = banner(board);
        return banner != null && (board.find(Mark.ATTACKER) != null || board.find(Mark.DEFENDER) != null)
                ? banner : null;
    }

    private String stageLabel() {
        return stage == 0 ? "current stage" : "stage " + stage;
    }

    private void before(long time, String message) throws IOException {
        if (time > deadline) throw new IOException(message);
    }

    private Status running(String detail) {
        return progress(detail, 340);
    }

    private Status deploying(String detail) { return progress(detail, 70); }

    private Status progress(String detail, long delayMs) {
        String treatment = options.strategy == Strategy.COMPARE && plan != null ? " · " + plan.label() : "";
        return new Status((completed + 1) + "/" + options.cap() + " · " + detail + treatment, false, delayMs);
    }

    private static Status done(String detail) { return new Status(detail, true, 0); }
}
