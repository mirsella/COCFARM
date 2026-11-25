package org.cocfarm.marble;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.cocfarm.marble.BuilderRun.Mark;
import org.cocfarm.marble.BuilderRun.Options;
import org.cocfarm.marble.BuilderRun.Point;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/** Host-operated capture, analysis, and replay for the APK's battle controller. */
final class HostRun {
    private static final int MAX_CAPTURE = 32 * 1024 * 1024;
    private static final int MAX_FRAMES = 1_800;

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || Arrays.asList(args).contains("--help")) {
            System.out.println("""
                    android/host/run --out DIR --serial ADB_NAME [--wireless --hardware PHONE_SERIAL]
                                     (--check | --live | --observe SECONDS)
                    android/host/run --out DIR --replay SESSION [--inspect --anchor BASE.png]
                    Options: --battles N (default 1), --plan FOCUS|SPLIT|COMPARE,
                             --overcharge GAME|HALF_HP, --offset X, --no-hero, --one-stage,
                             --either-95 [--cart-every N], --comparison-start N (prior four-way samples),
                             --minutes N (live recording budget, default 15, max 120),
                             --tessdata DIR (English Tesseract data; otherwise use local installation).
                    Only --live sends inputs. Replay uses recorded frames and never contacts ADB.
                    """);
            return;
        }
        try {
            run(args);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Stopped: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Map<String, String> flags = new LinkedHashMap<>();
        var toggles = List.of("--wireless", "--check", "--live", "--inspect", "--no-hero", "--one-stage", "--either-95");
        var values = List.of("--out", "--serial", "--hardware", "--observe", "--replay", "--anchor", "--battles",
                "--plan", "--overcharge", "--offset", "--tessdata", "--cart-every", "--comparison-start", "--minutes");
        for (int i = 3; i < args.length; i++) {
            String key = args[i];
            if (toggles.contains(key)) flags.put(key, "");
            else if (values.contains(key) && i + 1 < args.length) flags.put(key, args[++i]);
            else throw new IllegalArgumentException("Unknown or incomplete option: " + key);
        }
        if (!flags.containsKey("--out")) throw new IllegalArgumentException("Pass --out with a new directory.");
        boolean live = flags.containsKey("--live");
        boolean check = flags.containsKey("--check");
        boolean observe = flags.containsKey("--observe");
        boolean replay = flags.containsKey("--replay");
        boolean inspect = flags.containsKey("--inspect");
        if ((live ? 1 : 0) + (check ? 1 : 0) + (observe ? 1 : 0) + (replay ? 1 : 0) != 1
                || (!replay && (inspect || flags.containsKey("--anchor")))
                || (inspect && !flags.containsKey("--anchor"))) {
            throw new IllegalArgumentException("Choose exactly one mode. --inspect needs --replay and --anchor.");
        }
        int seconds = Integer.parseInt(flags.getOrDefault("--observe", "0"));
        if (observe && (seconds < 1 || seconds > 600)) throw new IllegalArgumentException("Observe 1–600 seconds.");
        int minutes = Integer.parseInt(flags.getOrDefault("--minutes", "15"));
        if (minutes < 1 || minutes > 120 || (!live && flags.containsKey("--minutes"))) {
            throw new IllegalArgumentException("--minutes sets a 1–120 minute budget for --live only.");
        }
        boolean storageGoal = flags.containsKey("--either-95");
        if (storageGoal && flags.containsKey("--battles")) {
            throw new IllegalArgumentException("Choose --either-95 or --battles, not both.");
        }
        var strategy = BuilderRun.Strategy.valueOf(flags.getOrDefault("--plan", "FOCUS"));
        long previousSamples = Long.parseLong(flags.getOrDefault("--comparison-start", "0"));
        if (previousSamples < 0 || previousSamples > Integer.MAX_VALUE
                || (flags.containsKey("--comparison-start") && strategy != BuilderRun.Strategy.COMPARE)) {
            throw new IllegalArgumentException("--comparison-start needs --plan COMPARE and a nonnegative count.");
        }
        Path source = replay ? Path.of(flags.get("--replay")) : null;
        if (!replay && !flags.containsKey("--serial")) throw new IllegalArgumentException("Select the phone with --serial.");
        Options options = new Options(BuilderRun.Mode.BATTLES, Integer.parseInt(flags.getOrDefault("--battles", "1")),
                !flags.containsKey("--one-stage"), !flags.containsKey("--no-hero"),
                Integer.parseInt(flags.getOrDefault("--offset", "0")), check, strategy,
                BuilderRun.Overcharge.valueOf(flags.getOrDefault("--overcharge", "GAME")),
                Integer.parseInt(flags.getOrDefault("--cart-every", storageGoal ? "3" : "0")),
                storageGoal ? StorageMeter.Goal.EITHER_95 : StorageMeter.Goal.BATTLE_LIMIT);
        Path out = Path.of(flags.get("--out")).toAbsolutePath();
        Files.createDirectories(out.getParent());
        Files.createDirectory(out);
        if (!replay) Files.createDirectory(out.resolve("frames"));
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        String tessdata = flags.get("--tessdata");
        try (var trace = Files.newBufferedWriter(out.resolve("trace.jsonl"));
             var results = Files.newBufferedWriter(out.resolve("battles.jsonl"));
             var vision = new BuilderVision(name -> template(Path.of(args[0]), name), mat -> ocr(mat, tessdata))) {
            write(trace, map("type", "session", "at", Instant.now().toString(), "commit", args[2], "build", args[1],
                    "opencv", Core.VERSION, "ocr", "tesseract-eng", "options", options, "previous_samples", previousSamples,
                    "comparison_version", BattleScore.COMPARISON_VERSION,
                    "source", source == null ? null : source.toAbsolutePath().toString(),
                    "mode", replay ? (inspect ? "inspect" : "replay") : observe ? "observe" : live ? "live" : "check"));
            var journal = new Journal(results, options.profile(), previousSamples);
            var controller = new BuilderRun(options, journal);
            var input = new Input(live);
            String previous = null;
            long blockedSince = -1;
            long start = System.nanoTime();
            try {
                if (!replay) input.phone = new AdbPhone(flags.get("--serial"), flags.get("--hardware"),
                        flags.containsKey("--wireless"), live);
                if (!replay) write(trace, map("type", "capture_format", "format", input.phone.usb ? "rgba" : "png"));
                if (flags.containsKey("--anchor")) {
                    try (var anchor = vision.frame(loadFrame(Path.of(flags.get("--anchor"))), options, input)) {
                        if (anchor.find(Mark.BASE) == null) throw new IOException("Calibration image isn't active Builder Base.");
                    }
                }
                List<Path> frames = List.of();
                if (replay) {
                    try (var paths = Files.list(source.resolve("frames"))) {
                        frames = paths.filter(path -> path.getFileName().toString().matches("\\d{6}-\\d{9}\\.png"))
                                .sorted().toList();
                    }
                    if (frames.isEmpty()) throw new IOException("No recorded frames in " + source);
                }
                int frameBudget = replay ? frames.size() : live ? Math.max(MAX_FRAMES, minutes * 120) : MAX_FRAMES;
                for (int index = 0; index < frameBudget; index++) {
                    byte[] capture;
                    Path framePath;
                    long now;
                    long started = System.nanoTime();
                    long captureMs = 0;
                    if (replay) {
                        framePath = frames.get(index);
                        now = Long.parseLong(framePath.getFileName().toString().substring(7, 16));
                        capture = loadFrame(framePath);
                    } else {
                        if (elapsed(start) >= (observe ? seconds * 1_000L : minutes * 60_000L)) break;
                        if (Files.getFileStore(out).getUsableSpace() < 512L * 1024 * 1024) {
                            throw new IOException("Less than 512 MiB remains for recording.");
                        }
                        byte[] image;
                        try {
                            image = input.phone.capture(MAX_CAPTURE);
                        } catch (AdbPhone.ScreenInterrupted e) {
                            if (!live) throw e;
                            now = elapsed(start);
                            if (blockedSince < 0) blockedSince = now;
                            if (now - blockedSince >= 60_000) throw e;
                            controller.resume();
                            String waiting = "Waiting for a clear game screen: " + e.getMessage();
                            write(trace, map("type", "capture_interrupted", "t_ms", now, "message", e.getMessage()));
                            if (!waiting.equals(previous)) {
                                previous = waiting;
                                System.out.println(previous);
                            }
                            Thread.sleep(1_000);
                            continue;
                        }
                        captureMs = elapsed(started);
                        capture = input.phone.usb ? image : decodePng(image);
                        ScreenPixels.readHeader(capture);
                        now = elapsed(start);
                        framePath = out.resolve("frames").resolve(String.format(Locale.ROOT, "%06d-%09d.png", index, now));
                        if (input.phone.usb) saveFrame(framePath, capture);
                        else Files.write(framePath, image);
                    }
                    long captured = elapsed(started);
                    input.action = null;
                    journal.frame = framePath.getFileName().toString();
                    Map<String, Object> event = map("type", "frame", "frame", journal.frame, "t_ms", now,
                            "capture_ms", captureMs, "capture_and_io_ms", captured);
                    BuilderRun.Status status = null;
                    try {
                        if (!observe) {
                            try (var board = vision.frame(capture, options, input)) {
                                var before = controller.diagnostic();
                                event.put("before", before);
                                var starts = board.find(Mark.STARTS);
                                var ends = board.find(Mark.ENDS);
                                event.put("starts", starts);
                                event.put("ends", ends);
                                if (starts != null || ends != null
                                        || before.phase().equals("DEPLOY") || before.phase().equals("WATCH")) {
                                    event.put("builder_battle", BuilderRun.liveBanner(board));
                                    event.put("hero", board.heroReading());
                                    event.put("ready_cards", board.ready());
                                    if (board.heroSlot() != null) event.put("hero_power", board.heroPower(board.heroSlot()));
                                }
                                if (inspect) {
                                    event.put("base", board.find(Mark.BASE));
                                    if (board.find(Mark.RETURN) != null) event.put("result", board.result());
                                } else {
                                    status = controller.step(board, now);
                                    blockedSince = -1;
                                    event.put("after", controller.diagnostic());
                                    event.put("status", status);
                                }
                            }
                        }
                    } catch (BuilderRun.ScreenUnavailable | AdbPhone.ScreenInterrupted e) {
                        event.put("error", e.getMessage());
                        // The ADB guard certifies that focus/lock rejection happened before input.
                        if (!live || (input.action != null && !(e instanceof AdbPhone.ScreenInterrupted))) throw e;
                        if (blockedSince < 0) blockedSince = now;
                        if (now - blockedSince >= 60_000) throw e;
                        controller.resume();
                        status = new BuilderRun.Status("Waiting for a clear game screen: " + e.getMessage(), false, 1_000);
                        event.put("status", status);
                    } catch (IOException | RuntimeException e) {
                        event.put("error", e.getMessage());
                        throw e;
                    } finally {
                        event.put("action", input.action);
                        event.put("step_ms", elapsed(started) - captured);
                        write(trace, event);
                    }
                    if (status != null && !status.text().equals(previous)) {
                        previous = status.text();
                        System.out.println(previous);
                    }
                    if (status != null && status.finished()) {
                        write(trace, map("type", "end", "status", "completed"));
                        return;
                    }
                    if (!replay) Thread.sleep(observe ? 300 : status.delayMs());
                }
                write(trace, map("type", "end", "status", observe || inspect ? "captured" : "budget_or_end_of_frames"));
                if (live) throw new IOException("Recording budget reached. No further input sent.");
            } catch (IOException | RuntimeException e) {
                write(trace, map("type", "error", "message", e.getMessage()));
                throw e;
            }
        }
    }

    private static final class Journal implements BattleScore.Journal {
        private final BufferedWriter output;
        private final BattleScore.Profile previousProfile;
        private final long previousSamples;
        private long samples;
        private String frame;

        private Journal(BufferedWriter output, BattleScore.Profile previousProfile, long previousSamples) {
            this.output = output;
            this.previousProfile = previousProfile;
            this.previousSamples = previousSamples;
        }
        @Override public long comparisonCount(BattleScore.Profile profile) {
            if (!previousProfile.equals(profile)) throw new IllegalStateException("Host comparison profile changed mid-run.");
            return previousSamples + samples;
        }
        @Override public void record(BattleScore.Entry entry) throws IOException {
            write(output, map("frame", frame, "at", Instant.now().toString(), "battle", entry));
            if (entry.comparison()) samples++;
        }
    }

    private static final class Input implements BuilderVision.Input {
        private final boolean live;
        private AdbPhone phone;
        private Map<String, Object> action;
        Input(boolean live) { this.live = live; }
        @Override public void tap(Point point) throws IOException {
            issue("tap", point, point, "/system/bin/input -d 0 tap " + xy(point));
        }
        @Override public void swipe(Point from, Point to) throws IOException {
            issue("swipe", from, to, "/system/bin/input -d 0 swipe " + xy(from) + " " + xy(to) + " 520");
        }
        @Override public boolean clear(Point from, Point to) { return true; }

        private void issue(String kind, Point from, Point to, String command) throws IOException {
            if (action != null) throw new IOException("More than one input requested on a frame; refused.");
            action = map("type", kind, "from", from, "to", to, "sent", false);
            if (live) {
                phone.guarded(command, 4096);
                action.put("sent", true);
            }
        }
        private static String xy(Point point) { return point.x() + " " + point.y(); }
    }

    private static Mat template(Path templates, String name) throws IOException {
        Mat image = Imgcodecs.imread(templates.resolve(name + ".png").toString(), Imgcodecs.IMREAD_COLOR);
        if (image.empty()) {
            image.release();
            throw new IOException("Couldn't read builder template " + name);
        }
        Imgproc.cvtColor(image, image, Imgproc.COLOR_BGR2RGB);
        return image;
    }

    private static String ocr(Mat mat, String tessdata) {
        MatOfByte encoded = new MatOfByte();
        try {
            if (!Imgcodecs.imencode(".png", mat, encoded)) return "";
            List<String> command = new ArrayList<>(List.of("tesseract", "stdin", "stdout", "-l", "eng", "--psm", "7",
                    "-c", "tessedit_char_whitelist=0123456789%+-"));
            if (tessdata != null) command.addAll(List.of("--tessdata-dir", tessdata));
            return new String(process(4096, 3, encoded.toArray(), command.toArray(String[]::new)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Result OCR unavailable: " + e.getMessage());
            return "";
        } finally {
            encoded.release();
        }
    }

    private static void saveFrame(Path path, byte[] raw) throws IOException {
        var size = ScreenPixels.readHeader(raw);
        Mat bgra = new Mat(size.height(), size.width(), CvType.CV_8UC4);
        MatOfInt parameters = new MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, 1);
        try {
            int count = raw.length - ScreenPixels.HEADER_BYTES;
            if (bgra.put(0, 0, raw, ScreenPixels.HEADER_BYTES, count) != count) throw new IOException("Incomplete screenshot.");
            Imgproc.cvtColor(bgra, bgra, Imgproc.COLOR_RGBA2BGRA);
            if (!Imgcodecs.imwrite(path.toString(), bgra, parameters)) throw new IOException("Couldn't save " + path);
        } finally {
            parameters.release();
            bgra.release();
        }
    }

    private static byte[] loadFrame(Path path) throws IOException {
        if (Files.size(path) > MAX_CAPTURE) throw new IOException("Screenshot is too large: " + path);
        return decodePng(Files.readAllBytes(path));
    }

    private static byte[] decodePng(byte[] png) throws IOException {
        byte[] magic = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (png.length < 24 || !Arrays.equals(png, 0, magic.length, magic, 0, magic.length)) {
            throw new IOException("Expected a PNG screenshot.");
        }
        ByteBuffer header = ByteBuffer.wrap(png);
        int width = header.getInt(16);
        int height = header.getInt(20);
        if (width < 1 || height < 1 || width > 4096 || height > 4096
                || width * height * 4L > MAX_CAPTURE - ScreenPixels.HEADER_BYTES) {
            throw new IOException("PNG screenshot is too large or has invalid dimensions.");
        }
        MatOfByte encoded = new MatOfByte(png);
        Mat image = null;
        try {
            image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_UNCHANGED);
            if (image.empty() || image.cols() != width || image.rows() != height || image.depth() != CvType.CV_8U) {
                throw new IOException("Couldn't decode an 8-bit PNG screenshot.");
            }
            Imgproc.cvtColor(image, image, switch (image.channels()) {
                case 3 -> Imgproc.COLOR_BGR2RGBA;
                case 4 -> Imgproc.COLOR_BGRA2RGBA;
                default -> throw new IOException("Screenshot must be RGB or RGBA.");
            });
            byte[] pixels = new byte[(int) image.total() * 4];
            if (image.get(0, 0, pixels) != pixels.length) throw new IOException("Couldn't read all screenshot pixels");
            return ByteBuffer.allocate(ScreenPixels.HEADER_BYTES + pixels.length).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(image.cols()).putInt(image.rows()).putInt(1).putInt(1).put(pixels).array();
        } finally {
            if (image != null) image.release();
            encoded.release();
        }
    }

    static final class CommandFailed extends IOException {
        final int code;
        CommandFailed(String command, int code) { super(command + " exited with code " + code); this.code = code; }
    }

    static byte[] process(int limit, int seconds, byte[] stdin, String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var reader = new FutureTask<>(() -> process.getInputStream().readNBytes(limit + 1));
        Thread thread = new Thread(reader, "command-output");
        thread.setDaemon(true);
        thread.start();
        try {
            try (var sink = process.getOutputStream()) { if (stdin != null) sink.write(stdin); }
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) throw new IOException(command[0] + " timed out");
            if (process.exitValue() != 0) throw new CommandFailed(command[0], process.exitValue());
            byte[] result = reader.get(1, TimeUnit.SECONDS);
            if (result.length > limit) throw new IOException(command[0] + " returned excess data");
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("Couldn't read " + command[0] + " output", e);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }

    private static Map<String, Object> map(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static void write(BufferedWriter writer, Object value) throws IOException {
        writer.write(json(value));
        writer.newLine();
        writer.flush();
    }

    private static String json(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Enum<?> e) return json(e.name());
        if (value instanceof String s) {
            StringBuilder escaped = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                if (c == '"' || c == '\\') escaped.append('\\').append(c);
                else if (c < 32) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                else escaped.append(c);
            }
            return escaped.append('"').toString();
        }
        if (value instanceof Map<?, ?> m) {
            var fields = new ArrayList<String>();
            m.forEach((key, item) -> fields.add(json(key) + ":" + json(item)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Iterable<?> iter) {
            var items = new ArrayList<String>();
            iter.forEach(item -> items.add(json(item)));
            return "[" + String.join(",", items) + "]";
        }
        if (value.getClass().isRecord()) {
            var fields = new LinkedHashMap<String, Object>();
            try {
                for (var field : value.getClass().getRecordComponents()) {
                    fields.put(field.getName(), field.getAccessor().invoke(value));
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Couldn't serialize the trace record", e);
            }
            return json(fields);
        }
        throw new IllegalArgumentException("Unsupported trace value: " + value.getClass());
    }
}
