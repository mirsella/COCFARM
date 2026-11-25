package org.cocfarm.marble;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.cocfarm.marble.BuilderRun.Mode;
import org.cocfarm.marble.BuilderRun.Options;
import org.cocfarm.marble.BuilderRun.Overcharge;
import org.cocfarm.marble.BuilderRun.Point;
import org.cocfarm.marble.BuilderRun.Status;
import org.cocfarm.marble.BuilderRun.Strategy;
import org.cocfarm.marble.StorageMeter.Goal;

public final class OverlayService extends Service {
    static final String SHOW = "org.cocfarm.marble.SHOW";
    private static final String TOGGLE = "org.cocfarm.marble.TOGGLE";
    private static final String CLOSE = "org.cocfarm.marble.CLOSE";
    private static final String CHANNEL = "marble_panel";
    private static final int NOTIFICATION = 41;
    private static final int COMPACT_WIDTH = 208;
    private static final int EXPANDED_WIDTH = 276;
    private static final int WINDOW_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RunControl control = new RunControl();
    private final Runnable tick = this::tick;
    private RootDevice device;
    private WindowManager windows;
    private NotificationManager notifications;
    private SharedPreferences prefs;
    private WindowManager.LayoutParams layout;
    private LinearLayout panel;
    private LinearLayout compact;
    private LinearLayout full;
    private LinearLayout statusHandle;
    private View signal;
    private TextView compactStatus;
    private Button quickAction;
    private ScrollView settings;
    private LinearLayout battleSettings;
    private TextView phaseLabel;
    private TextView detailLabel;
    private Button action;
    private Button second;
    private Button gear;
    private Button modeButton;
    private Button stageButton;
    private Button heroButton;
    private Button strategyButton;
    private Button overchargeButton;
    private Button cartEveryButton;
    private Button storageGoalButton;
    private TextView limitLabel;
    private TextView offsetLabel;
    private volatile Rect panelBounds;

    private Session session;
    private boolean inFlight;
    private boolean paused;
    private boolean resetSelection;
    private long pauseStarted;
    private long pauseTotal;
    private boolean showSettings;
    private boolean expanded;
    private boolean expandAfterTick;
    private boolean panelLayoutPending;
    private boolean dockedLeft;
    private boolean warning;
    private String detail;
    private Mode mode;
    private int limit;
    private boolean stageTwo;
    private boolean hero;
    private Strategy strategy;
    private Overcharge overcharge;
    private int offset;
    private int cartEvery;
    private Goal storageGoal;

    private static final class Session implements AutoCloseable {
        final BuilderRun run;
        BuilderVision vision;

        Session(Options options) { run = new BuilderRun(options); }

        @Override public void close() {
            if (vision != null) vision.close();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        device = new RootDevice(this);
        windows = getSystemService(WindowManager.class);
        notifications = getSystemService(NotificationManager.class);
        prefs = getSharedPreferences("runner", MODE_PRIVATE);
        mode = prefs.getBoolean("cart", false) ? Mode.CART : Mode.BATTLES;
        limit = Math.max(1, Math.min(20, prefs.getInt("limit", 1)));
        stageTwo = prefs.getBoolean("second", true);
        hero = prefs.getBoolean("hero", true);
        strategy = switch (prefs.getString("strategy", "")) {
            case "SPLIT" -> Strategy.SPLIT;
            case "COMPARE" -> Strategy.COMPARE;
            default -> Strategy.FOCUS;
        };
        if (strategy == Strategy.COMPARE && limit % 2 != 0) limit++;
        overcharge = switch (prefs.getString("overchargeMode", "")) {
            case "NEAR_TARGETS" -> Overcharge.NEAR_TARGETS;
            case "LOW_HP" -> Overcharge.LOW_HP;
            case "GAME" -> Overcharge.GAME;
            default -> prefs.getBoolean("overcharge", false) ? Overcharge.LOW_HP : Overcharge.GAME;
        };
        offset = Math.max(-80, Math.min(360, prefs.getInt("offset", 0)));
        cartEvery = switch (prefs.getInt("cartEvery", 0)) {
            case 1 -> 1;
            case 3 -> 3;
            case 5 -> 5;
            case 10 -> 10;
            default -> 0;
        };
        // Older builds had no choice between either resource and both; start with the new default.
        storageGoal = switch (prefs.getString("storageStop", Goal.DEFAULT.name())) {
            case "EITHER_95" -> Goal.EITHER_95;
            case "BOTH_95" -> Goal.BOTH_95;
            case "BOTH_90" -> Goal.BOTH_90;
            case "BATTLE_LIMIT" -> Goal.BATTLE_LIMIT;
            default -> {
                Log.w("MarbleRunner", "Unrecognized storage stop setting; using the default");
                yield Goal.DEFAULT;
            }
        };
        if (storageGoal != Goal.BATTLE_LIMIT && cartEvery == 0) cartEvery = 3;
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Field controls",
                NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent command, int flags, int startId) {
        String requested = command == null ? CLOSE : command.getAction();
        if (CLOSE.equals(requested)) {
            closePanel();
            return START_NOT_STICKY;
        }
        if (TOGGLE.equals(requested)) {
            togglePause();
            return START_NOT_STICKY;
        }
        if (!SHOW.equals(requested) || !Settings.canDrawOverlays(this)) {
            closePanel();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION, notification());
        }
        if (panel == null) {
            try {
                showPanel();
            } catch (RuntimeException e) {
                Log.e("MarbleRunner", "Couldn't attach overlay", e);
                Toast.makeText(this, "Couldn't show field controls. Check the overlay permission.",
                        Toast.LENGTH_LONG).show();
                closePanel();
            }
        }
        return START_NOT_STICKY;
    }

    private void showPanel() {
        panel = new LinearLayout(this) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    collapse();
                    // Android also delivers the same touch to the game. This is only a notification.
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.surface(this, Ui.BACK, Ui.EDGE, 8));
        panel.setElevation(Ui.dp(this, 7));
        addCompact();

        full = Ui.stack(this);
        int pad = Ui.dp(this, 9);
        full.setPadding(pad, pad, pad, pad);
        Ui.line(panel, full, 0);
        LinearLayout top = Ui.row(this);
        TextView handle = Ui.text(this, "≡  MARBLE / FIELD", 12, Ui.LIME, true);
        handle.setGravity(Gravity.CENTER_VERTICAL);
        handle.setContentDescription("Collapse field controls. Drag to move.");
        dragHandle(handle, this::collapse);
        Ui.cell(top, handle, 0, 38, 0);
        gear = Ui.button(this, "Setup", false, v -> {
            showSettings = !showSettings;
            render();
        });
        Ui.cell(top, gear, 64, 38, 4);
        Button close = Ui.button(this, "×", false, v -> closePanel());
        close.setContentDescription("Stop and close field controls");
        Ui.cell(top, close, 38, 38, 4);
        Ui.line(full, top, 0);

        phaseLabel = Ui.text(this, "", 11, Ui.LIME, true);
        Ui.line(full, phaseLabel, 8);
        detailLabel = Ui.text(this, "", 15, Ui.TEXT, false);
        detailLabel.setMaxLines(3);
        detailLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Ui.line(full, detailLabel, 2);

        LinearLayout controls = Ui.row(this);
        action = Ui.button(this, "Start", true, v -> {
            if (session == null) begin(false);
            else togglePause();
        });
        second = Ui.button(this, "Check", false, v -> {
            if (session == null) begin(true);
            else endSession("Stopped. Take over in the game.", false);
        });
        Ui.cell(controls, action, 0, 44, 0);
        Ui.cell(controls, second, 76, 44, 6);
        Ui.line(full, controls, 8);
        addSettings();

        layout = new WindowManager.LayoutParams(Ui.dp(this, COMPACT_WIDTH), -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WINDOW_FLAGS, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.END;
        layout.x = layout.y = Ui.dp(this, 10);
        if (mode == Mode.BATTLES && cartEvery > 0) dockUpperLeft();
        render();
        panelLayoutPending = true;
        panel.getViewTreeObserver().addOnPreDrawListener(() -> {
            updateBounds();
            panelLayoutPending = panelBounds == null;
            return true;
        });
        windows.addView(panel, layout);
    }

    private void addCompact() {
        compact = Ui.row(this);
        compact.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2));
        statusHandle = Ui.row(this);
        statusHandle.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 4), 0);
        dragHandle(statusHandle, this::expand);
        signal = new View(this);
        Ui.cell(statusHandle, signal, 6, 6, 0);
        compactStatus = Ui.text(this, "", 13, Ui.TEXT, false);
        compactStatus.setGravity(Gravity.CENTER_VERTICAL);
        compactStatus.setMaxLines(1);
        compactStatus.setEllipsize(TextUtils.TruncateAt.END);
        compactStatus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Ui.cell(statusHandle, compactStatus, 0, 44, 7);
        TextView caret = Ui.text(this, "▾", 13, Ui.MUTED, false);
        caret.setGravity(Gravity.CENTER);
        caret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Ui.cell(statusHandle, caret, 12, 44, 3);
        Ui.cell(compact, statusHandle, 0, 44, 0);
        quickAction = Ui.button(this, "Pause", false, v -> togglePause());
        quickAction.setSingleLine();
        Ui.cell(compact, quickAction, 68, 44, 3);
        Ui.line(panel, compact, 0);
    }

    private void dragHandle(View handle, Runnable click) {
        handle.setOnClickListener(v -> click.run());
        handle.setOnTouchListener(new View.OnTouchListener() {
            float x;
            float y;
            int left;
            int top;
            boolean moving;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        x = event.getRawX();
                        y = event.getRawY();
                        left = layout.x;
                        top = layout.y;
                        moving = false;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        int dx = Math.round(event.getRawX() - x);
                        int dy = Math.round(event.getRawY() - y);
                        if (!moving && Math.abs(dx) + Math.abs(dy) > Ui.dp(OverlayService.this, 6)) {
                            moving = true;
                            if (session != null && !paused) togglePause();
                        }
                        if (moving) {
                            dockedLeft = false;
                            layout.x = left - dx; // END gravity anchors x to the right edge.
                            layout.y = top + dy;
                            clampPanel();
                        }
                    }
                    case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!moving && event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
                    }
                }
                return true;
            }
        });
    }

    private void expand() {
        // Don't grow the overlay over a game input the worker may already be sending.
        if (session != null && inFlight) {
            expandAfterTick = true;
            return;
        }
        expanded = true;
        render();
    }

    private void collapse() {
        expandAfterTick = false;
        if (!expanded) return;
        expanded = showSettings = false;
        render();
    }

    private void addSettings() {
        settings = new ScrollView(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int available = windows.getCurrentWindowMetrics().getBounds().height()
                        - layout.y - Ui.dp(OverlayService.this, 175);
                int maxHeight = Math.max(Ui.dp(OverlayService.this, 96), available);
                if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                    maxHeight = Math.min(maxHeight, MeasureSpec.getSize(heightSpec));
                }
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        settings.setVerticalScrollBarEnabled(true);
        LinearLayout settingsContent = Ui.stack(this);
        settings.addView(settingsContent);
        Ui.line(full, settings, 4);
        modeButton = Ui.button(this, "", false, v -> {
            mode = mode == Mode.BATTLES ? Mode.CART : Mode.BATTLES;
            detail = null;
            warning = false;
            saveSettings();
        });
        LinearLayout modeRow = Ui.row(this);
        Ui.cell(modeRow, modeButton, 0, 40, 0);
        Ui.line(settingsContent, modeRow, 4);

        battleSettings = Ui.stack(this);
        Ui.line(settingsContent, battleSettings, 0);
        LinearLayout farm = Ui.row(this);
        cartEveryButton = Ui.button(this, "", false, v -> {
            cartEvery = switch (cartEvery) {
                case 3 -> 5;
                case 5 -> 10;
                case 10 -> 1;
                case 1 -> storageGoal != Goal.BATTLE_LIMIT ? 3 : 0;
                default -> 3;
            };
            saveSettings();
        });
        storageGoalButton = Ui.button(this, "", false, v -> {
            storageGoal = storageGoal.next();
            if (storageGoal != Goal.BATTLE_LIMIT && cartEvery == 0) cartEvery = 3;
            saveSettings();
        });
        Ui.cell(farm, cartEveryButton, 0, 42, 0);
        Ui.cell(farm, storageGoalButton, 0, 42, 6);
        Ui.line(battleSettings, farm, 4);
        LinearLayout toggles = Ui.row(this);
        stageButton = Ui.button(this, "", false, v -> { stageTwo = !stageTwo; saveSettings(); });
        heroButton = Ui.button(this, "", false, v -> { hero = !hero; saveSettings(); });
        Ui.cell(toggles, stageButton, 0, 40, 0);
        Ui.cell(toggles, heroButton, 0, 40, 6);
        Ui.line(battleSettings, toggles, 4);
        LinearLayout tactics = Ui.row(this);
        strategyButton = Ui.button(this, "", false, v -> {
            strategy = switch (strategy) {
                case FOCUS -> Strategy.SPLIT;
                case SPLIT -> Strategy.COMPARE;
                case COMPARE -> Strategy.FOCUS;
            };
            if (strategy == Strategy.COMPARE && limit % 2 != 0) limit++;
            saveSettings();
        });
        overchargeButton = Ui.button(this, "", false, v -> {
            overcharge = switch (overcharge) {
                case GAME -> Overcharge.LOW_HP;
                case LOW_HP -> Overcharge.NEAR_TARGETS;
                case NEAR_TARGETS -> Overcharge.GAME;
            };
            saveSettings();
        });
        Ui.cell(tactics, strategyButton, 0, 40, 0);
        Ui.cell(tactics, overchargeButton, 0, 40, 6);
        Ui.line(battleSettings, tactics, 4);
        limitLabel = stepper("Battle limit", () -> adjustBattleLimit(-1), () -> adjustBattleLimit(1));
        offsetLabel = stepper("Troop bar", () -> { offset = Math.max(-80, offset - 10); saveSettings(); },
                () -> { offset = Math.min(360, offset + 10); saveSettings(); });
    }

    private void adjustBattleLimit(int direction) {
        int step = strategy == Strategy.COMPARE ? 2 : 1;
        limit = Math.max(step, Math.min(20, limit + step * direction));
        saveSettings();
    }

    private TextView stepper(String label, Runnable decrement, Runnable increment) {
        LinearLayout row = Ui.row(this);
        TextView value = Ui.text(this, label, 13, Ui.MUTED, true);
        value.setGravity(Gravity.CENTER_VERTICAL);
        Ui.cell(row, value, 0, 38, 0);
        Button minus = Ui.button(this, "−", false, v -> decrement.run());
        minus.setContentDescription("Decrease " + label);
        Ui.cell(row, minus, 39, 38, 4);
        Button plus = Ui.button(this, "+", false, v -> increment.run());
        plus.setContentDescription("Increase " + label);
        Ui.cell(row, plus, 39, 38, 4);
        Ui.line(battleSettings, row, 4);
        return value;
    }

    private void saveSettings() {
        prefs.edit().putBoolean("cart", mode == Mode.CART).putInt("limit", limit)
                .putBoolean("second", stageTwo).putBoolean("hero", hero).putInt("offset", offset)
                .putString("strategy", strategy.name()).putString("overchargeMode", overcharge.name())
                .putInt("cartEvery", cartEvery).putString("storageStop", storageGoal.name()).apply();
        render();
    }

    private void render() {
        if (panel == null) return;
        boolean active = session != null;
        String phase = active ? (paused ? "PAUSED" : "RUNNING") : (warning ? "STOPPED" : "READY");
        int tint = paused || warning ? Ui.WARN : Ui.LIME;
        compact.setVisibility(expanded ? View.GONE : View.VISIBLE);
        full.setVisibility(expanded ? View.VISIBLE : View.GONE);
        String summary = paused ? "Paused · " + detail
                : warning ? "Stopped · " + detail
                : detail != null ? detail.replace('\n', ' ')
                : mode == Mode.CART ? "Ready · Elixir Cart" : "Ready · Builder Battles";
        compactStatus.setText(summary);
        statusHandle.setContentDescription(summary + ". Expand field controls. Drag to move.");
        signal.setBackground(Ui.surface(this, tint, 0, 3));
        String pauseLabel = paused ? "Resume" : "Pause";
        quickAction.setText(pauseLabel);
        quickAction.setVisibility(active ? View.VISIBLE : View.GONE);
        phaseLabel.setText((mode == Mode.CART ? "ELIXIR" : cartEvery > 0 ? "FARM" : "GOLD") + "   /   " + phase);
        phaseLabel.setTextColor(tint);
        String message = statusDetail();
        detailLabel.setMaxLines(message.startsWith("A/B averages") ? 6 : 3);
        detailLabel.setText(message);
        action.setText(active ? pauseLabel : (mode == Mode.BATTLES ? "Start" : "Collect"));
        second.setText(active ? "Stop" : "Check");
        gear.setVisibility(active ? View.GONE : View.VISIBLE);
        gear.setText(showSettings ? "Done" : "Setup");
        settings.setVisibility(!active && showSettings ? View.VISIBLE : View.GONE);
        battleSettings.setVisibility(mode == Mode.BATTLES ? View.VISIBLE : View.GONE);
        modeButton.setText(mode == Mode.BATTLES ? "Mode: Builder Battles" : "Mode: Elixir Cart");
        cartEveryButton.setText(cartEvery == 0 ? "Auto cart: off" : "Cart: every " + cartEvery);
        cartEveryButton.setContentDescription(cartEvery == 0 ? "Automatic cart collection off"
                : "Collect cart every " + cartEvery + (cartEvery == 1 ? " battle" : " battles"));
        storageGoalButton.setText(storageGoal.label());
        storageGoalButton.setContentDescription(storageGoal == Goal.EITHER_95
                ? "Stop when either Builder Gold or Builder Elixir reaches approximately 95 percent" : null);
        stageButton.setText(stageTwo ? "Stage 2: on" : "Stage 2: off");
        heroButton.setText(hero ? "Hero: on" : "Hero: off");
        strategyButton.setText("Plan: " + strategy.label);
        overchargeButton.setText(switch (overcharge) {
            case GAME -> "P.E.K.K.A: game";
            case LOW_HP -> "P.E.K.K.A: low HP";
            case NEAR_TARGETS -> "P.E.K.K.A: nearby";
        });
        if (overcharge == Overcharge.NEAR_TARGETS) {
            overchargeButton.setContentDescription("P.E.K.K.A.: nearby targets, experimental");
        } else {
            overchargeButton.setContentDescription(null);
        }
        limitLabel.setText("Battle limit: " + limit);
        ((View) limitLabel.getParent()).setVisibility(storageGoal == Goal.BATTLE_LIMIT ? View.VISIBLE : View.GONE);
        offsetLabel.setText("Troop bar: " + offset);
        int flags = WINDOW_FLAGS
                | (expanded ? WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH : 0)
                | (active && !paused ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0);
        int width = Ui.dp(this, expanded ? EXPANDED_WIDTH : COMPACT_WIDTH);
        if (layout.flags != flags || layout.width != width) {
            int available = windows.getCurrentWindowMetrics().getBounds().width();
            if (layout.x + layout.width / 2 > available / 2) layout.x += layout.width - width;
            layout.width = width;
            layout.flags = flags;
            clampPanel();
        }
        notifications.notify(NOTIFICATION, notification());
    }

    private String statusDetail() {
        if (detail != null) return detail;
        return mode == Mode.CART ? "Collect finds the shoreline cart." : "Open Builder Base in landscape.";
    }

    private void dockUpperLeft() {
        // Leave the resource HUD and cart close button clear.
        dockedLeft = true;
        int margin = Ui.dp(this, 10);
        layout.x = Math.max(0, windows.getCurrentWindowMetrics().getBounds().width() - layout.width - margin);
        layout.y = margin;
        clampPanel();
    }

    private void begin(boolean verify) {
        if (session != null) return;
        control.invalidate();
        session = new Session(new Options(mode, limit, stageTwo, hero, offset, verify, strategy, overcharge,
                cartEvery, storageGoal));
        inFlight = paused = resetSelection = warning = showSettings = false;
        expanded = expandAfterTick = false;
        pauseTotal = 0;
        detail = "Checking the game screen…";
        render();
        if (mode == Mode.BATTLES && cartEvery > 0) dockUpperLeft();
        schedule(250);
    }

    private void togglePause() {
        if (session == null) return;
        control.invalidate();
        paused = !paused;
        if (paused) {
            pauseStarted = SystemClock.elapsedRealtime();
            main.removeCallbacks(tick);
        } else {
            pauseTotal += SystemClock.elapsedRealtime() - pauseStarted;
            resetSelection = true;
            schedule(250);
        }
        render();
    }

    private void schedule(long delay) {
        if (session == null || paused || inFlight) return;
        main.removeCallbacks(tick);
        main.postDelayed(tick, delay);
    }

    private void tick() {
        if (session == null || paused || inFlight) return;
        if (panelLayoutPending) {
            schedule(50);
            return;
        }
        Session current = session;
        int epoch = control.epoch();
        long pausedFor = pauseTotal;
        boolean reset = resetSelection;
        resetSelection = false;
        inFlight = true;
        worker.execute(() -> {
            Status status = null;
            Exception failure = null;
            try {
                control.require(epoch);
                if (current.vision == null) {
                    RootDevice.checkRoot();
                    current.vision = new BuilderVision(getAssets());
                }
                if (reset) current.run.resume();
                control.require(epoch);
                try (BuilderVision.Frame board = current.vision.frame(device.capture(), current.run.options,
                        new BuilderVision.Input() {
                            @Override public void tap(Point point) throws IOException {
                                requireClearPath(point, point);
                                device.tap(point.x(), point.y(), control, epoch);
                            }

                            @Override public void swipe(Point start, Point end) throws IOException {
                                requireClearPath(start, end);
                                device.swipe(start.x(), start.y(), end.x(), end.y(), control, epoch);
                            }

                            @Override public boolean clear(Point start, Point end) {
                                return overlayClear(panelBounds, start, end);
                            }
                        })) {
                    control.require(epoch);
                    status = current.run.step(board, SystemClock.elapsedRealtime() - pausedFor);
                }
            } catch (IOException | RuntimeException e) {
                failure = e;
            } catch (LinkageError e) {
                failure = new IOException("Couldn't start the bundled vision library.", e);
            }
            Status result = status;
            Exception error = failure;
            main.post(() -> afterTick(current, result, error));
        });
    }

    private void requireClearPath(Point start, Point end) throws IOException {
        Rect overlay = panelBounds;
        if (overlay == null) throw new IOException("Couldn't locate the field controls. Show them and try again.");
        if (!overlayClear(overlay, start, end)) {
            throw new IOException("Field controls cover a target. Move the panel and restart.");
        }
    }

    private static boolean overlayClear(Rect overlay, Point start, Point end) {
        if (overlay == null) return false;
        int left = Math.min(start.x(), end.x());
        int top = Math.min(start.y(), end.y());
        int right = Math.max(start.x(), end.x()) + 1;
        int bottom = Math.max(start.y(), end.y()) + 1;
        return !overlay.intersects(left, top, right, bottom);
    }

    private void afterTick(Session current, Status result, Exception error) {
        if (session != current) return;
        inFlight = false;
        if (error != null && !(error instanceof RunControl.Changed)) {
            Log.w("MarbleRunner", "Run stopped", error);
            endSession(error instanceof IOException ? error.getMessage() : "Unexpected runner error. Check logcat.", true);
            return;
        }
        if (result != null && result.finished()) {
            Log.i("MarbleRunner", result.text());
            endSession(result.text(), false);
            return;
        }
        boolean changed = result != null && !result.text().equals(detail);
        if (changed) {
            detail = result.text();
            Log.i("MarbleRunner", detail);
        }
        if (expandAfterTick) {
            expandAfterTick = false;
            expand();
        } else if (changed) render();
        schedule(result != null ? result.delayMs() : 340);
    }

    private void endSession(String message, boolean failed) {
        clearSession();
        detail = message;
        warning = failed;
        expanded = showSettings = expandAfterTick = false;
        render();
    }

    private void clearSession() {
        control.invalidate();
        main.removeCallbacks(tick);
        Session old = session;
        session = null;
        paused = false;
        if (old != null) worker.execute(old::close);
    }

    private void closePanel() {
        clearSession();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void clampPanel() {
        if (panel == null || !panel.isAttachedToWindow()) return;
        Rect bounds = windows.getCurrentWindowMetrics().getBounds();
        layout.x = Math.max(0, Math.min(layout.x, bounds.width() - layout.width));
        layout.y = Math.max(0, Math.min(layout.y, bounds.height() - panel.getHeight()));
        panelLayoutPending = true;
        windows.updateViewLayout(panel, layout);
        panel.invalidate();
    }

    private void updateBounds() {
        if (panel == null || panel.getWidth() <= 0 || panel.getHeight() <= 0) return;
        int[] origin = new int[2];
        panel.getLocationOnScreen(origin);
        int margin = Ui.dp(this, 5);
        panelBounds = new Rect(origin[0] - margin, origin[1] - margin,
                origin[0] + panel.getWidth() + margin, origin[1] + panel.getHeight() + margin);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (session != null) endSession("Device configuration changed. Verify the game again.", true);
        if (panel != null) panel.post(() -> {
            if (dockedLeft) dockUpperLeft();
            else clampPanel();
        });
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setColor(Ui.LIME).setSmallIcon(R.drawable.ic_status).setContentTitle(
                        session == null ? "Marble Runner" : (paused ? "Marble Runner · Paused" : "Marble Runner · Active"))
                .setContentText(statusDetail()).setOnlyAlertOnce(true).setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
        if (session != null) {
            builder.addAction(new Notification.Action.Builder(null, paused ? "Resume" : "Pause",
                    notificationAction(TOGGLE)).build());
        }
        builder.addAction(new Notification.Action.Builder(null, "Stop & close", notificationAction(CLOSE)).build());
        return builder.build();
    }

    private PendingIntent notificationAction(String action) {
        return PendingIntent.getService(this, 0, new Intent(this, OverlayService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onDestroy() {
        clearSession();
        worker.shutdown();
        panelBounds = null;
        if (panel != null && panel.isAttachedToWindow()) windows.removeViewImmediate(panel);
        panel = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
