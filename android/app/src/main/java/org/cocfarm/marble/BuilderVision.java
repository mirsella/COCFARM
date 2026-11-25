package org.cocfarm.marble;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import org.cocfarm.marble.BuilderRun.HeroCard;
import org.cocfarm.marble.BuilderRun.HeroPower;
import org.cocfarm.marble.BuilderRun.Mark;
import org.cocfarm.marble.BuilderRun.Match;
import org.cocfarm.marble.BuilderRun.Mode;
import org.cocfarm.marble.BuilderRun.Options;
import org.cocfarm.marble.BuilderRun.PekkaHealth;
import org.cocfarm.marble.BuilderRun.Point;

final class BuilderVision implements AutoCloseable {
    private static final int HEIGHT = 920;
    private static final int WIDTH = 1177;
    private static final int HEALTH_BAR_WIDTH = 84;
    private static final int HEALTH_BAR_HEIGHT = 8;
    private final EnumMap<Mark, Mat> templates = new EnumMap<>(Mark.class);
    private final List<Mat> cartBodies = new ArrayList<>();
    private final NumberReader numbers;
    private Mat storageHud;
    private Mat machine;
    private Mat copter;
    private Mat pekka;
    private int rawWidth;
    private int rawHeight;
    private double scale;

    interface Input {
        void tap(Point point) throws IOException;
        void swipe(Point start, Point end) throws IOException;
        boolean clear(Point start, Point end);
    }

    interface Asset {
        Mat load(String name) throws IOException;
    }

    interface NumberReader {
        String read(Mat image) throws IOException;
    }

    record HeroReading(HeroCard state, double machineScore, double copterScore, Point nominalSlot,
                       Point portraitCenter, Integer portraitTop, Integer saturation, boolean healthBar) {}

    private record Hit(Rect bounds, double score) {
        Match match() {
            return new Match(new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2), score);
        }
    }

    BuilderVision(Asset assets, NumberReader numbers) throws IOException {
        this.numbers = numbers;
        Core.setNumThreads(2);
        try {
            templates.put(Mark.BASE, assets.load("battle"));
            templates.put(Mark.FIND, assets.load("find_now"));
            templates.put(Mark.ATTACK_COOLDOWN, assets.load("marble_attack_cooldown"));
            templates.put(Mark.STARTS, assets.load("battle_starts"));
            templates.put(Mark.ENDS, assets.load("battle_ends"));
            templates.put(Mark.ATTACKER, assets.load("marble_attacker"));
            templates.put(Mark.DEFENDER, assets.load("marble_defender"));
            templates.put(Mark.SPEED_1X, assets.load("speed_1x"));
            templates.put(Mark.RETURN, assets.load("return_home"));
            for (String name : List.of("cart_world", "cart_world/empty", "cart_world/ready",
                    "cart_world/marked", "cart_world/filled_alternate")) {
                cartBodies.add(assets.load(name));
            }
            templates.put(Mark.CART_TITLE, assets.load("cart_title"));
            templates.put(Mark.COLLECT, assets.load("cart_collect"));
            templates.put(Mark.CART_CLOSE, assets.load("cart_close"));
            storageHud = assets.load("storage_hud");
            machine = assets.load("hero_machine");
            copter = assets.load("hero_copter");
            pekka = assets.load("power_pekka");
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    Frame frame(byte[] capture, Options options, Input input) throws IOException {
        ScreenPixels header = ScreenPixels.readHeader(capture);
        int w = header.width();
        int h = header.height();
        if (w <= h || (rawWidth != 0 && (w != rawWidth || h != rawHeight))) {
            throw new BuilderRun.ScreenUnavailable("Return to full-screen landscape at the original display size.");
        }
        rawWidth = w;
        rawHeight = h;
        Mat pixels = new Mat(h, w, CvType.CV_8UC4);
        try {
            int offset = ScreenPixels.HEADER_BYTES;
            int count = capture.length - offset;
            if (pixels.put(0, 0, capture, offset, count) != count) {
                throw new IOException("Couldn't copy the full screen capture.");
            }
            if (scale == 0) {
                double toReference = HEIGHT / (double) h;
                Mat normalized = new Mat();
                try {
                    resize(pixels, normalized, toReference);
                    Imgproc.cvtColor(normalized, normalized, Imgproc.COLOR_RGBA2RGB);
                    double anchor = options.mode() == Mode.CART ? calibrate(normalized, Mark.CART_TITLE) : 0;
                    if (anchor == 0) anchor = calibrate(normalized, Mark.BASE);
                    if (anchor == 0 && options.mode() == Mode.BATTLES) {
                        anchor = calibrate(normalized, Mark.ATTACKER);
                        if (anchor == 0) anchor = calibrate(normalized, Mark.DEFENDER);
                    }
                    if (anchor == 0) {
                        throw new BuilderRun.ScreenUnavailable(options.mode() == Mode.CART
                                ? "Open Builder Base or its Elixir Cart in English."
                                : "Open Builder Base or an active Builder Battle in English.");
                    }
                    double candidate = toReference / anchor;
                    Mat check = new Mat();
                    try {
                        resize(pixels, check, candidate);
                        Imgproc.cvtColor(check, check, Imgproc.COLOR_RGBA2RGB);
                        // Verify the anchor before retaining this scale for future frames.
                        Frame board = new Frame(check, options, input);
                        if (board.find(Mark.BASE) == null && (options.mode() == Mode.CART
                                ? board.find(Mark.CART_TITLE) == null
                                : BuilderRun.liveBanner(board) == null)) {
                            throw new BuilderRun.ScreenUnavailable(options.mode() == Mode.CART
                                    ? "Open Builder Base or its Elixir Cart in English."
                                    : "Open Builder Base or an active Builder Battle in English.");
                        }
                        scale = candidate;
                    } finally {
                        check.release();
                    }
                } finally {
                    normalized.release();
                }
            }
            Mat normalized = new Mat();
            try {
                resize(pixels, normalized, scale);
                Imgproc.cvtColor(normalized, normalized, Imgproc.COLOR_RGBA2RGB);
                return new Frame(normalized, options, input);
            } catch (RuntimeException e) {
                normalized.release();
                throw e;
            }
        } finally {
            pixels.release();
        }
    }

    private double calibrate(Mat screen, Mark anchor) {
        Mat template = templates.get(anchor);
        Rect area = area(screen, anchor);
        double bestScale = 1.0;
        double bestScore = 0.0;
        for (int tenth = 6; tenth <= 17; tenth++) {
            double candidate = tenth / 10.0;
            double score = scoreAt(screen, template, area, candidate);
            if (score > bestScore) {
                bestScale = candidate;
                bestScore = score;
            }
        }
        double coarse = bestScale;
        for (int step : new int[]{-2, -1, 1, 2}) {
            double candidate = coarse + step * .02;
            double score = scoreAt(screen, template, area, candidate);
            if (score > bestScore) {
                bestScale = candidate;
                bestScore = score;
            }
        }
        return bestScore >= .85 ? bestScale : 0;
    }

    private static double scoreAt(Mat screen, Mat template, Rect area, double size) {
        Mat scaled = new Mat();
        try {
            resize(template, scaled, size);
            Hit hit = match(screen, scaled, area, 0);
            return hit == null ? 0 : hit.score;
        } finally {
            scaled.release();
        }
    }

    private static void resize(Mat input, Mat output, double factor) {
        Imgproc.resize(input, output, new Size(), factor, factor,
                factor < 1 ? Imgproc.INTER_AREA : Imgproc.INTER_LINEAR);
    }

    private static Hit match(Mat screen, Mat template, Rect area, double threshold) {
        if (area.width < template.cols() || area.height < template.rows()) return null;
        Mat crop = screen.submat(area);
        Mat scores = new Mat();
        try {
            Imgproc.matchTemplate(crop, template, scores, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult best = Core.minMaxLoc(scores);
            if (!Double.isFinite(best.maxVal) || best.maxVal < threshold) return null;
            return new Hit(new Rect((int) best.maxLoc.x + area.x, (int) best.maxLoc.y + area.y,
                    template.cols(), template.rows()), best.maxVal);
        } finally {
            scores.release();
            crop.release();
        }
    }

    private static Hit matchButton(Mat screen, Mat template, Rect area, double threshold) {
        Hit best = match(screen, template, area, threshold);
        if (best != null && best.score >= .90) return best;
        return matchScales(screen, template, area, threshold, best, .94, .97, 1.035, 1.07);
    }

    private static Hit matchScales(Mat screen, Mat template, Rect area, double threshold,
                                   Hit best, double... factors) {
        Mat scaled = new Mat();
        try {
            for (double factor : factors) {
                resize(template, scaled, factor);
                Hit candidate = match(screen, scaled, area, threshold);
                if (candidate != null && (best == null || candidate.score > best.score)) best = candidate;
            }
            return best;
        } finally {
            scaled.release();
        }
    }

    private static void bannerHighlights(Mat rgb, Mat output) {
        Imgproc.cvtColor(rgb, output, Imgproc.COLOR_RGB2GRAY);
        // Emphasize the bright countdown lettering, not the moving map behind it.
        Core.subtract(output, new Scalar(140), output);
    }

    private static Rect area(Mat screen, Mark mark) {
        int w = screen.cols();
        int h = screen.rows();
        return switch (mark) {
            case BASE -> new Rect(0, h * 72 / 100, Math.min(310, w / 3), h - h * 72 / 100);
            case STARTS, ENDS -> new Rect(w / 3, 0, w - 2 * (w / 3), h / 10);
            case ATTACKER -> new Rect(w * 4 / 5, 0, w - w * 4 / 5, h / 12);
            case DEFENDER -> new Rect(0, 0, w / 5, h / 12);
            case SPEED_1X -> new Rect(w * 87 / 100, h * 43 / 100, w - w * 87 / 100, h * 24 / 100);
            case CART_WORLD -> new Rect(w / 6, 0, w - 2 * (w / 6), h * 2 / 3);
            case CART_TITLE -> new Rect(w / 4, h / 20, w / 2, h * 3 / 20);
            case RETURN -> new Rect(w / 3, h * 2 / 3, w - 2 * (w / 3), h - h * 2 / 3);
            case COLLECT -> new Rect(w / 2, h * 3 / 4, w * 3 / 10, h - h * 3 / 4);
            case CART_CLOSE -> new Rect(w * 69 / 100, h * 4 / 100, w * 14 / 100, h * 15 / 100);
            case FIND -> new Rect(w / 5, h / 3, w - 2 * (w / 5), h - h / 3);
            case ATTACK_COOLDOWN -> new Rect(w * 60 / 100, h * 56 / 100, w * 18 / 100, h * 15 / 100);
        };
    }

    final class Frame implements BuilderRun.Board, AutoCloseable {
        private final Mat screen;
        private final Options options;
        private final Input input;
        private final int cardY;
        private final EnumMap<Mark, Match> marks = new EnumMap<>(Mark.class);
        private HeroReading heroReading;
        private Hit heroPortrait;

        Frame(Mat screen, Options options, Input input) {
            this.screen = screen;
            this.options = options;
            this.input = input;
            cardY = screen.rows() - 90;
        }

        @Override public Match find(Mark mark) {
            // Cache misses too; a frame never changes between recognition and input.
            if (!marks.containsKey(mark)) marks.put(mark, detect(mark));
            return marks.get(mark);
        }

        private Match detect(Mark mark) {
            if (mark == Mark.STARTS || mark == Mark.ENDS) return findBanner(mark);
            Rect area = area(screen, mark);
            if ((mark == Mark.COLLECT || mark == Mark.CART_CLOSE) && find(Mark.CART_TITLE) == null) return null;
            if (mark == Mark.CART_WORLD) {
                Hit hit = null;
                for (Mat body : cartBodies) {
                    Hit exact = match(screen, body, area, hit == null ? .85 : hit.score);
                    if (exact != null) hit = exact;
                }
                if (hit == null || hit.score < .90) {
                    for (Mat body : cartBodies) hit = matchCartScales(area, body, hit);
                }
                return hit == null ? null : hit.match();
            }
            Mat template = templates.get(mark);
            double threshold = switch (mark) {
                case BASE, CART_TITLE, ATTACKER, DEFENDER -> .85;
                case CART_CLOSE, ATTACK_COOLDOWN -> .86;
                default -> .80;
            };
            Hit hit = mark == Mark.BASE || mark == Mark.CART_TITLE
                    ? match(screen, template, area, threshold)
                    : matchButton(screen, template, area, threshold);
            if (hit == null) return null;
            if (mark == Mark.BASE
                    && !hasColor(hit.bounds, new Scalar(18, 125, 215), new Scalar(40, 255, 255), .18)) return null;
            if ((mark == Mark.ATTACKER || mark == Mark.DEFENDER)
                    && !hasColor(hit.bounds, new Scalar(22, 70, 205), new Scalar(40, 255, 255), .15)) return null;
            if (mark == Mark.FIND
                    && !hasColor(hit.bounds, new Scalar(30, 65, 100), new Scalar(70, 255, 255), .18)) return null;
            if (mark == Mark.SPEED_1X
                    && !hasColor(hit.bounds, new Scalar(30, 65, 125), new Scalar(75, 255, 255), .20)) return null;
            if (mark == Mark.COLLECT) {
                int[] hsv = medianHsv(hit.bounds);
                if (hsv[0] < 30 || hsv[0] > 70 || hsv[1] <= 60) return null;
            }
            if (mark == Mark.CART_CLOSE
                    && !hasColor(hit.bounds, new Scalar(168, 125, 145), new Scalar(179, 255, 255), .15)
                    && !hasColor(hit.bounds, new Scalar(0, 125, 145), new Scalar(12, 255, 255), .15)) return null;
            return hit.match();
        }

        @Override public StorageMeter.Levels storage() throws IOException {
            int w = screen.cols();
            int h = screen.rows();
            Hit hud = matchButton(screen, storageHud, new Rect(w * 4 / 5, 0, w - w * 4 / 5, h / 5), .90);
            if (hud == null) return null;
            Rect bounds = hud.bounds;
            // Measure relative to the icon crop in the 2400×1080 reference.
            double sx = bounds.width / 62.0;
            double sy = bounds.height / 132.0;
            int x = bounds.x - (int) Math.round(StorageMeter.sampleOffset(options.storageGoal().percent) * sx);
            int sampleWidth = Math.max(3, (int) Math.round(8 * sx));
            int sampleHeight = Math.max(3, (int) Math.round(6 * sy));
            if (x < 0 || !input.clear(physical(new Point(x, bounds.y)),
                    physical(new Point(bounds.x + bounds.width - 1, bounds.y + bounds.height - 1)))) return null;
            Boolean gold = storageBar(true, bounds.y, sy, x, sampleWidth, sampleHeight);
            Boolean elixir = storageBar(false, bounds.y, sy, x, sampleWidth, sampleHeight);
            return gold == null || elixir == null ? null : new StorageMeter.Levels(gold, elixir);
        }

        private Boolean storageBar(boolean gold, int anchorY, double scaleY, int x, int width, int height) {
            int y = anchorY + (int) Math.round((gold ? 16 : 91) * scaleY);
            int lower = y + (int) Math.round(20 * scaleY);
            int[] topPixels = medianHsv(new Rect(x, y, width, height));
            int[] bottomPixels = medianHsv(new Rect(x, lower, width, height));
            return StorageMeter.nearGoal(gold, topPixels, bottomPixels);
        }

        private Hit matchCartScales(Rect area, Mat template, Hit best) {
            return matchScales(screen, template, area, .85, best, .70, .82, .94, 1.07, 1.2, 1.35);
        }

        private Match findBanner(Mark mark) {
            Rect region = area(screen, mark);
            Mat crop = screen.submat(region);
            Mat highlights = new Mat();
            Mat scaled = new Mat();
            Mat template = new Mat();
            try {
                bannerHighlights(crop, highlights);
                Hit best = null;
                Rect local = new Rect(0, 0, region.width, region.height);
                for (double factor : new double[]{1, .94, .97, 1.035, 1.055, 1.07, 1.09}) {
                    resize(templates.get(mark), scaled, factor);
                    bannerHighlights(scaled, template);
                    Hit candidate = match(highlights, template, local, .65);
                    if (candidate != null && (best == null || candidate.score > best.score)) best = candidate;
                }
                if (best == null) return null;
                Match hit = best.match();
                return new Match(new Point(region.x + hit.point().x(), region.y + hit.point().y()), hit.score());
            } finally {
                crop.release();
                highlights.release();
                scaled.release();
                template.release();
            }
        }

        private boolean hasColor(Rect bounds, Scalar lower, Scalar upper, double fraction) {
            Mat crop = screen.submat(bounds);
            Mat hsv = new Mat();
            Mat mask = new Mat();
            try {
                Imgproc.cvtColor(crop, hsv, Imgproc.COLOR_RGB2HSV);
                Core.inRange(hsv, lower, upper, mask);
                return Core.countNonZero(mask) >= fraction * crop.total();
            } finally {
                mask.release();
                hsv.release();
                crop.release();
            }
        }

        @Override public List<Point> ready() {
            List<Point> ready = new ArrayList<>();
            if (cardY < 70) return ready;
            Point hero = heroSlot();
            if (hero != null && heroCard() == HeroCard.READY) ready.add(hero);
            for (int slot = 0; slot < 10; slot++) {
                int x = troopX(slot);
                if (x + 30 > screen.cols()) break;
                if (x < 30) continue;
                if (troopReady(x)) ready.add(new Point(x, cardY));
            }
            return ready;
        }

        private boolean troopReady(int x) {
            int[] hsv = medianHsv(new Rect(x - 30, cardY - 34, 60, 16));
            boolean fresh = hsv[0] >= 130 && hsv[0] <= 155 && hsv[1] > 100 && hsv[2] > 200;
            boolean carried = hsv[0] >= 100 && hsv[0] <= 125 && hsv[1] > 120
                    && hsv[2] >= 60 && hsv[2] <= 170;
            if (!fresh && !carried) return false;
            Mat card = screen.submat(new Rect(x - 30, cardY - 10, 60, 40));
            Mat gray = new Mat();
            MatOfDouble mean = new MatOfDouble();
            MatOfDouble deviation = new MatOfDouble();
            try {
                Imgproc.cvtColor(card, gray, Imgproc.COLOR_RGB2GRAY);
                Core.meanStdDev(gray, mean, deviation);
                return deviation.toArray()[0] >= 25;
            } finally {
                card.release();
                gray.release();
                mean.release();
                deviation.release();
            }
        }

        @Override public HeroCard heroCard() {
            return heroReading().state();
        }

        HeroReading heroReading() {
            if (heroReading != null) return heroReading;
            Point slot = heroSlot();
            if (slot == null) return heroReading = new HeroReading(HeroCard.UNKNOWN, 0, 0, null, null, null, null, false);
            int left = Math.max(0, slot.x() - 60);
            int right = Math.min(screen.cols(), slot.x() + 60);
            Rect search = new Rect(left, cardY - 56, right - left, 102);
            Hit machineHit = match(screen, machine, search, 0);
            Hit copterHit = match(screen, copter, search, 0);
            Hit hero = machineHit == null || (copterHit != null && copterHit.score > machineHit.score)
                    ? copterHit : machineHit;
            HeroCard state = HeroCard.UNKNOWN;
            boolean health = false;
            Point center = hero == null ? null : hero.match().point();
            Integer top = hero == null ? null : hero.bounds.y - cardY;
            Integer saturation = hero == null || hero.score < .68 ? null : medianHsv(hero.bounds)[1];
            if (saturation != null && saturation >= 50) {
                if (top >= -34 && top <= -18 && hero.score >= .74) {
                    state = HeroCard.READY;
                } else if (top <= -38 && hero.bounds.y >= 25 && center.x() >= 38 && center.x() + 40 <= screen.cols()) {
                    health = heroHasHealthBar(hero);
                    if (hero.score >= .74 || health) {
                        state = HeroCard.DEPLOYED;
                        heroPortrait = hero;
                    }
                }
            }
            return heroReading = new HeroReading(state, machineHit == null ? 0 : machineHit.score,
                    copterHit == null ? 0 : copterHit.score, slot, center, top, saturation, health);
        }

        private boolean heroHasHealthBar(Hit hero) {
            int x = hero.match().point().x();
            Mat health = screen.submat(new Rect(x - 38, hero.bounds.y - 20, 78, 26));
            Mat hsv = new Mat();
            Mat low = new Mat();
            Mat high = new Mat();
            try {
                Imgproc.cvtColor(health, hsv, Imgproc.COLOR_RGB2HSV);
                Core.inRange(hsv, new Scalar(0, 161, 191), new Scalar(60, 255, 255), low);
                Core.inRange(hsv, new Scalar(170, 161, 191), new Scalar(179, 255, 255), high);
                return Core.countNonZero(low) + Core.countNonZero(high) > .018 * health.total();
            } finally {
                health.release();
                hsv.release();
                low.release();
                high.release();
            }
        }

        @Override public Point heroSlot() {
            int x = 68 + barLeft();
            return options.hero() && x >= 43 && x + 43 < screen.cols() && cardY >= 71
                    ? new Point(x, cardY) : null;
        }

        @Override public HeroPower heroPower(Point card) {
            if (!card.equals(heroSlot()) || heroCard() != HeroCard.DEPLOYED) return HeroPower.UNKNOWN;
            int x = heroPortrait.match().point().x();
            // Anchor to the raised portrait so small shifts in screen calibration don't offset the meter.
            Rect first = new Rect(x - 34, heroPortrait.bounds.y - 25, 16, 5);
            Rect second = new Rect(x - 10, first.y, 14, 5);
            Rect third = new Rect(x + 12, first.y, 21, 5);
            Scalar purpleMin = new Scalar(135, 121, 151);
            Scalar purpleMax = new Scalar(165, 255, 255);
            Scalar blueMin = new Scalar(87, 101, 131);
            Scalar blueMax = new Scalar(115, 255, 255);
            boolean firstFull = hasColor(first, purpleMin, purpleMax, .60);
            boolean secondFull = firstFull && hasColor(second, purpleMin, purpleMax, .60);
            if (secondFull && hasColor(third, purpleMin, purpleMax, .60)) return HeroPower.READY;
            if ((!firstFull && hasColor(first, blueMin, blueMax, .25))
                    || (firstFull && !secondFull && hasColor(second, blueMin, blueMax, .25))) {
                // Do not rearm from the third bar flashing white when it is already full.
                return HeroPower.CHARGING;
            }
            return HeroPower.UNKNOWN;
        }

        private int barLeft() { return Math.max(0, (screen.cols() - WIDTH) / 2) + options.cardOffset(); }

        private int troopX(int slot) { return (int) Math.round(185 + barLeft() + slot * 103.6); }

        @Override public PekkaHealth pekkaHealth(Point card) {
            int x = card.x();
            int slot = (int) Math.round((x - troopX(0)) / 103.6);
            if (card.y() != cardY || x < 45 || x + 45 >= screen.cols()
                    || slot < 0 || slot >= 10 || troopX(slot) != x) return PekkaHealth.UNKNOWN;
            Rect art = new Rect(x - 41, cardY - 12, 82, 50);
            Hit portrait = match(screen, pekka, art, .86);
            return portrait != null && medianHsv(portrait.bounds)[1] >= 90
                    ? classifyPekkaHealth(deployedHealthWidth(x)) : PekkaHealth.UNKNOWN;
        }

        private int deployedHealthWidth(int x) {
            // The deployed card has a colored, left-aligned health strip.
            Mat crop = screen.submat(new Rect(x - HEALTH_BAR_WIDTH / 2, cardY - 30,
                    HEALTH_BAR_WIDTH, HEALTH_BAR_HEIGHT));
            Mat hsv = new Mat();
            try {
                Imgproc.cvtColor(crop, hsv, Imgproc.COLOR_RGB2HSV);
                byte[] pixels = new byte[HEALTH_BAR_WIDTH * HEALTH_BAR_HEIGHT * 3];
                hsv.get(0, 0, pixels);
                int start = -1;
                int end = -1;
                int filled = 0;
                for (int col = 0; col < HEALTH_BAR_WIDTH; col++) {
                    int colored = 0;
                    for (int row = 0; row < HEALTH_BAR_HEIGHT; row++) {
                        int i = 3 * (HEALTH_BAR_WIDTH * row + col);
                        int hue = pixels[i] & 255;
                        if ((hue <= 85 || hue >= 170) && (pixels[i + 1] & 255) > 120
                                && (pixels[i + 2] & 255) > 100) colored++;
                    }
                    if (colored >= 6) {
                        if (start == -1) start = col;
                        end = col;
                        filled++;
                    }
                }
                return start >= 0 && start <= 6 && filled >= end - start ? filled : 0;
            } finally {
                hsv.release();
                crop.release();
            }
        }

        @Override public BattleScore.Result result() throws IOException {
            // The return button guards these crops. Keep labels and icons outside each number.
            int w = screen.cols();
            int h = screen.rows();
            Integer damage = BattleScore.damage(readNumber(new Rect(w * 44 / 100, h * 40 / 100,
                    w * 12 / 100, h * 8 / 100)));
            Integer gold = BattleScore.gold(readNumber(new Rect(w * 56 / 100, h * 60 / 100,
                    w * 8 / 100, h * 45 / 1000)));
            Integer trophies = BattleScore.trophies(readNumber(new Rect(w * 56 / 100, h * 664 / 1000,
                    w * 8 / 100, h * 42 / 1000)));
            return new BattleScore.Result(gold, damage, trophies);
        }

        private String readNumber(Rect region) throws IOException {
            Point topLeft = physical(new Point(region.x, region.y));
            Point bottomRight = physical(new Point(region.x + region.width - 1, region.y + region.height - 1));
            if (!input.clear(topLeft, bottomRight)) return "";
            Mat crop = screen.submat(region);
            Mat enlarged = new Mat();
            Mat ink = new Mat();
            Mat padded = new Mat();
            try {
                resize(crop, enlarged, 2);
                // The score uses white lettering on a blue, partly transparent panel.
                // Isolate the lettering and add a quiet border for the recognizer.
                Core.inRange(enlarged, new Scalar(220, 220, 220), new Scalar(255, 255, 255), ink);
                Core.bitwise_not(ink, ink);
                Core.copyMakeBorder(ink, padded, 16, 16, 16, 16, Core.BORDER_CONSTANT, new Scalar(255));
                return numbers.read(padded);
            } finally {
                padded.release();
                ink.release();
                enlarged.release();
                crop.release();
            }
        }

        private int[] medianHsv(Rect bounds) {
            Mat crop = screen.submat(bounds);
            Mat hsv = new Mat();
            try {
                Imgproc.cvtColor(crop, hsv, Imgproc.COLOR_RGB2HSV);
                byte[] pixels = new byte[bounds.width * bounds.height * 3];
                hsv.get(0, 0, pixels);
                int[][] bins = new int[3][256];
                for (int i = 0; i < pixels.length; i++) bins[i % 3][pixels[i] & 255]++;
                int[] result = new int[3];
                for (int c = 0; c < 3; c++) {
                    int total = 0;
                    while (result[c] < 255 && (total += bins[c][result[c]]) <= pixels.length / 6) result[c]++;
                }
                return result;
            } finally {
                hsv.release();
                crop.release();
            }
        }

        @Override public Point dropPoint(int lane, int miss) throws IOException {
            // Both lanes stay in the left deployment strip and clear of battle controls.
            Point percent = lane == 1 ? switch (miss) {
                case 0 -> new Point(19, 63);
                case 1 -> new Point(13, 60);
                case 2 -> new Point(20, 58);
                default -> throw new IOException("No more lower-lane drop candidates.");
            } : switch (miss) {
                case 0 -> new Point(19, 49);
                // Wider bases can put every interior retry inside the red deployment boundary.
                case 1 -> new Point(13, 45);
                case 2 -> new Point(17, 53);
                case 3 -> new Point(11, 51);
                case 4 -> new Point(22, 47);
                case 5 -> new Point(24, 42);
                default -> throw new IOException("No more troop drop candidates.");
            };
            int x = screen.cols() * percent.x() / 100;
            int y = screen.rows() * percent.y() / 100;
            if (x < 6 || x >= screen.cols() - 6 || y < 6 || y >= cardY - 85) {
                throw new IOException("The map doesn't fit this screen calibration.");
            }
            return new Point(x, y);
        }

        @Override public void tap(Point point) throws IOException {
            input.tap(physical(point));
        }

        @Override public void panForCart(boolean towardShore) throws IOException {
            // Translate the same drag vector around the overlay, not across it.
            for (int dx : new int[]{0, 16, -16}) {
                Point start = physical(towardShore ? percent(57 + dx, 22) : percent(61 + dx, 62));
                Point end = physical(towardShore ? percent(45 + dx, 72) : percent(32 + dx, 28));
                if (input.clear(start, end)) {
                    input.swipe(start, end);
                    return;
                }
            }
            throw new IOException("Field controls block the shoreline search. Move the panel and restart.");
        }

        private Point percent(int x, int y) {
            return new Point(screen.cols() * x / 100, screen.rows() * y / 100);
        }

        private Point physical(Point point) throws IOException {
            int x = (int) Math.round(point.x() / scale);
            int y = (int) Math.round(point.y() / scale);
            if (x < 0 || y < 0 || x >= rawWidth || y >= rawHeight) {
                throw new IOException("Refusing input outside the landscape screenshot.");
            }
            return new Point(x, y);
        }

        @Override public void close() {
            screen.release();
        }
    }

    static PekkaHealth classifyPekkaHealth(int width) {
        // A nearly empty strip is indistinguishable from a dead or unreadable card.
        if (width < 6 || width > HEALTH_BAR_WIDTH) return PekkaHealth.UNKNOWN;
        return width <= HEALTH_BAR_WIDTH / 2 ? PekkaHealth.AT_OR_BELOW_HALF : PekkaHealth.ABOVE_HALF;
    }

    @Override public void close() {
        templates.values().forEach(Mat::release);
        cartBodies.forEach(Mat::release);
        if (storageHud != null) storageHud.release();
        if (machine != null) machine.release();
        if (copter != null) copter.release();
        if (pekka != null) pekka.release();
    }
}
