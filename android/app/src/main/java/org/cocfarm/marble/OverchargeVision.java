package org.cocfarm.marble;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cocfarm.marble.BuilderRun.Point;
import org.cocfarm.marble.OverchargePolicy.Target;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** Screen-only cues for the experimental Overcharge trigger. */
final class OverchargeVision implements AutoCloseable {
    static final int REF_HEIGHT = 648;
    private static final int DIGITS = 8;
    private static final double TARGET_SCALE = .5;
    private static final String[] BUILDINGS = {
        "archer_1", "archer_2", "cannon_1", "cannon_2", "clock", "crusher", "double_cannon",
        "firecrackers_1", "firecrackers_2", "giant_cannon", "hall", "multimortar", "outpost", "roaster"
    };
    private static final String[] WALLS = {
        "wall_blue_1", "wall_blue_2", "wall_rose_1", "wall_rose_2", "wall_stone_1", "wall_stone_2",
        "wall_wood_1", "wall_wood_2"
    };

    interface Asset { Mat load(String name) throws IOException; }
    interface Visible { boolean clear(Rect area) throws IOException; }
    private record Pattern(Mat image, boolean wall, double threshold) {}
    private record Hit(Target target, double score) {}

    private final List<Mat> digits = new ArrayList<>();
    private final List<Pattern> patterns = new ArrayList<>();

    OverchargeVision(Asset assets) throws IOException {
        try {
            for (int i = 1; i <= DIGITS; i++) {
                Mat rgb = assets.load("overcharge/number_" + i);
                try {
                    Mat bright = new Mat();
                    digits.add(bright);
                    digitLight(rgb, bright);
                } finally {
                    rgb.release();
                }
            }
            loadTargets(assets, BUILDINGS, false, .55);
            loadTargets(assets, WALLS, true, .77);
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    private void loadTargets(Asset assets, String[] names, boolean wall, double threshold) throws IOException {
        for (String name : names) {
            Mat image = assets.load("overcharge/" + name);
            try {
                for (double scale : new double[]{.92, 1, 1.08}) {
                    Mat template = new Mat();
                    patterns.add(new Pattern(template, wall, threshold));
                    Imgproc.resize(image, template, new Size(), TARGET_SCALE * scale, TARGET_SCALE * scale, Imgproc.INTER_AREA);
                }
            } finally {
                image.release();
            }
        }
    }

    Scene scene(Mat screen, Visible visible) throws IOException { return new Scene(screen, visible); }

    final class Scene implements AutoCloseable {
        private final Mat map = new Mat();
        private final Mat small = new Mat();
        private final Visible visible;
        private final Map<Integer, Point> units = new HashMap<>();

        Scene(Mat screen, Visible visible) throws IOException {
            this.visible = visible;
            double s = REF_HEIGHT / (double) screen.rows();
            try {
                Imgproc.resize(screen, map, new Size(), s, s, Imgproc.INTER_AREA);
                Imgproc.resize(map, small, new Size(), TARGET_SCALE, TARGET_SCALE, Imgproc.INTER_AREA);
                readMarkers();
            } catch (IOException | RuntimeException failure) {
                close();
                throw failure;
            }
        }

        Point unit(int slot) { return units.get(slot); }

        boolean hasCluster(Point unit) throws IOException {
            int left = Math.max(0, (unit.x() - 125) / 2);
            int top = Math.max(0, (unit.y() - 193) / 2);
            int right = Math.min(small.cols(), (unit.x() + 125) / 2);
            int bottom = Math.min(small.rows() * 80 / 100, (unit.y() + 76) / 2);
            if (right <= left || bottom <= top) return false;
            Mat crop = small.submat(new Rect(left, top, right - left, bottom - top));
            Mat scores = new Mat();
            var hits = new ArrayList<Hit>();
            try {
                for (Pattern p : patterns) {
                    int w = p.image.cols();
                    int h = p.image.rows();
                    if (w > crop.cols() || h > crop.rows()) continue;
                    Imgproc.matchTemplate(crop, p.image, scores, Imgproc.TM_CCOEFF_NORMED);
                    for (int i = 0; i < 2; i++) {
                        var best = Core.minMaxLoc(scores);
                        if (!Double.isFinite(best.maxVal) || best.maxVal < p.threshold) break;
                        int x = (int) best.maxLoc.x;
                        int y = (int) best.maxLoc.y;
                        Rect rect = new Rect((left + x) * 2, (top + y) * 2, w * 2, h * 2);
                        if (visible.clear(rect)) {
                            int foot = rect.y + (int) Math.round(rect.height * (p.wall ? .66 : .84));
                            hits.add(new Hit(new Target(new Point(rect.x + w, foot),
                                    p.wall, Math.min(25, w / 2)), best.maxVal));
                        }
                        clearPeak(scores, x, y, w / 2, h / 2);
                    }
                }
            } finally {
                crop.release();
                scores.release();
            }
            hits.sort(Comparator.comparingDouble(Hit::score).reversed());
            var targets = new ArrayList<Target>(hits.size());
            for (Hit hit : hits) targets.add(hit.target);
            return OverchargePolicy.hasCluster(unit, targets);
        }

        private void readMarkers() throws IOException {
            // The colored slot badge is followed by a rectangular purple plate;
            // the visible health strip below helps rule out scenery and effects.
            int fieldBottom = map.rows() * 80 / 100;
            Mat field = map.submat(0, fieldBottom, 0, map.cols());
            Mat hsv = new Mat();
            Mat plates = new Mat();
            Mat ids = new Mat();
            Mat stats = new Mat();
            Mat centroids = new Mat();
            Mat bright = new Mat();
            Mat scores = new Mat();
            boolean[] ambiguous = new boolean[DIGITS + 1];
            try {
                Imgproc.cvtColor(field, hsv, Imgproc.COLOR_RGB2HSV);
                Core.inRange(hsv, new Scalar(135, 85, 145), new Scalar(169, 255, 255), plates);
                int count = Imgproc.connectedComponentsWithStats(plates, ids, stats, centroids, 8);
                for (int i = 1; i < count; i++) {
                    int[] part = new int[5];
                    stats.get(i, 0, part);
                    int x = part[0], y = part[1], w = part[2], h = part[3];
                    if (w < 24 || w > 40 || h < 6 || h > 15 || part[4] < w * h * .60
                            || x < 28 || x + 23 >= map.cols() || y < 15 || y + 75 >= map.rows()) continue;
                    Rect badge = new Rect(x - 26, y - 15, 24, 25);
                    Rect strip = new Rect(x - 17, y + 8, 29, 12);
                    if (strip.y + strip.height >= fieldBottom || !visible.clear(badge) || !hasHealth(hsv, strip)) continue;
                    Mat src = map.submat(badge);
                    int slot = 0;
                    double best = .77;
                    double runnerUp = 0;
                    try {
                        digitLight(src, bright);
                        for (int d = 0; d < digits.size(); d++) {
                            Imgproc.matchTemplate(bright, digits.get(d), scores, Imgproc.TM_CCOEFF_NORMED);
                            double value = Core.minMaxLoc(scores).maxVal;
                            if (value > best) {
                                if (slot != 0) runnerUp = best;
                                best = value;
                                slot = d + 1;
                            } else {
                                runnerUp = Math.max(runnerUp, value);
                            }
                        }
                    } finally {
                        src.release();
                    }
                    if (slot == 0 || best - runnerUp < .11 || ambiguous[slot]) continue;
                    Point ground = new Point(x + 5, y + 80);
                    if (!visible.clear(new Rect(x - 5, y + 35, 20, Math.min(48, map.rows() - y - 35)))) continue;
                    if (units.putIfAbsent(slot, ground) != null) {
                        ambiguous[slot] = true;
                        units.remove(slot);
                    }
                }
            } finally {
                field.release();
                hsv.release();
                plates.release();
                ids.release();
                stats.release();
                centroids.release();
                bright.release();
                scores.release();
            }
        }

        @Override public void close() {
            map.release();
            small.release();
        }
    }

    private static boolean hasHealth(Mat hsv, Rect strip) {
        Mat crop = hsv.submat(strip);
        byte[] pixels = new byte[strip.width * strip.height * 3];
        try {
            crop.get(0, 0, pixels);
        } finally {
            crop.release();
        }
        int matches = 0;
        for (int i = 0; i < pixels.length; i += 3) {
            int h = pixels[i] & 255;
            if ((h <= 85 || h >= 170) && (pixels[i + 1] & 255) > 110
                    && (pixels[i + 2] & 255) > 100) matches++;
        }
        return matches >= 22;
    }

    private static void digitLight(Mat rgb, Mat output) {
        Imgproc.cvtColor(rgb, output, Imgproc.COLOR_RGB2GRAY);
        Core.subtract(output, new Scalar(145), output);
    }

    private static void clearPeak(Mat scores, int x, int y, int dx, int dy) {
        int left = Math.max(0, x - dx);
        int top = Math.max(0, y - dy);
        Mat patch = scores.submat(top, Math.min(scores.rows(), y + dy + 1),
                left, Math.min(scores.cols(), x + dx + 1));
        try {
            patch.setTo(new Scalar(-1));
        } finally {
            patch.release();
        }
    }

    @Override public void close() {
        digits.forEach(Mat::release);
        patterns.forEach(p -> p.image.release());
    }
}
