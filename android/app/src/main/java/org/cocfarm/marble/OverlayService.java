package org.cocfarm.marble;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
    private static final String ALERT_CHANNEL = "cocfarm_run_alerts";
    private static final int NOTIFICATION = 41;
    private static final int RUN_ALERT = 42;
    private static final int COMPACT_WIDTH = 306;
    private static final int EXPANDED_WIDTH = 342;
    private static final int WINDOW_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RunControl control = new RunControl();
    private final Runnable tick = this::tick;
    private RootDevice device;
    private BattleHistory journal;
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
    private LinearLayout settingsContent;
    private HistoryView history;
    private LinearLayout battleSettings;
    private TextView phaseLabel;
    private TextView detailLabel;
    private Button action;
    private Button second;
    private Button pauseButton;
    private Button historyButton;
    private TextView settingsHint;
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
    private boolean recheckScreen;
    private boolean showHistory;
    private boolean expanded;
    private boolean expandAfterTick;
    private boolean panelLayoutPending;
    private boolean dockedLeft;
    private boolean dockAfterEdit;
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
        final Mode mode;
        final boolean verify;
        AndroidVision vision;

        Session(Options options, BattleHistory journal) {
            mode = options.mode();
            verify = options.verify();
            run = new BuilderRun(options, journal);
        }

        @Override public void close() {
            if (vision != null) vision.close();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        device = new RootDevice(this);
        journal = new BattleHistory(this);
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
        overcharge = Overcharge.fromPreference(prefs.getString("overchargeMode", ""), prefs.getBoolean("overcharge", false));
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
        notifications.createNotificationChannel(new NotificationChannel(ALERT_CHANNEL, "Run alerts",
                NotificationManager.IMPORTANCE_DEFAULT));
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
                    // The game still receives the touch; hold releases after compact bounds are ready.
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
        historyButton = Ui.button(this, "History", false, v -> {
            showHistory = !showHistory;
            if (showHistory) history.refresh(settings(false).profile());
            settings.scrollTo(0, 0);
            render();
        });
        Ui.cell(top, historyButton, 68, 38, 4);
        Button close = Ui.button(this, "×", false, v -> closePanel());
        close.setContentDescription("Stop and close field controls");
        Ui.cell(top, close, 38, 38, 4);
        Ui.line(full, top, 0);

        phaseLabel = Ui.text(this, "", 11, Ui.LIME, true);
        Ui.line(full, phaseLabel, 8);
        detailLabel = Ui.text(this, "", 15, Ui.TEXT, false);
        detailLabel.setMaxLines(2);
        detailLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Ui.line(full, detailLabel, 2);

        LinearLayout controls = Ui.row(this);
        action = Ui.button(this, "Start", true, v -> startStop());
        second = Ui.button(this, "Check", false, v -> begin(true));
        pauseButton = Ui.button(this, "Pause", false, v -> togglePause());
        Ui.cell(controls, action, 0, 44, 0);
        Ui.cell(controls, second, 76, 44, 6);
        Ui.cell(controls, pauseButton, 106, 44, 6);
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
        compact.setPadding(Ui.dp(this, 3), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2));
        quickAction = Ui.button(this, "Start", true, v -> {
            if (paused) togglePause();
            else startStop();
        });
        quickAction.setSingleLine();
        Ui.cell(compact, quickAction, 76, 44, 0);
        statusHandle = Ui.row(this);
        statusHandle.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
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
        boolean wasHeld = held();
        expanded = true;
        updateHold(wasHeld);
        render();
    }

    private void collapse() {
        expandAfterTick = false;
        if (!expanded) return;
        boolean wasHeld = held();
        expanded = showHistory = false;
        render();
        if (dockAfterEdit) {
            dockAfterEdit = false;
            if ((session != null ? session.mode : mode) == Mode.BATTLES && cartEvery > 0 && !dockedLeft) {
                dockUpperLeft();
            }
        }
        updateHold(wasHeld);
    }

    private void addSettings() {
        settings = new ScrollView(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int available = windows.getCurrentWindowMetrics().getBounds().height()
                        - layout.y - Ui.dp(OverlayService.this, 180);
                int maxHeight = Math.max(Ui.dp(OverlayService.this, 64), available);
                if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                    maxHeight = Math.min(maxHeight, MeasureSpec.getSize(heightSpec));
                }
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        settings.setVerticalScrollBarEnabled(true);
        LinearLayout scrollContent = Ui.stack(this);
        settings.addView(scrollContent);
        settingsContent = Ui.stack(this);
        Ui.line(scrollContent, settingsContent, 0);
        history = new HistoryView(this);
        Ui.line(scrollContent, history, 0);
        Ui.line(full, settings, 4);
        settingsHint = Ui.text(this, "", 12, Ui.MUTED, false);
        Ui.line(settingsContent, settingsHint, 2);
        modeButton = Ui.button(this, "", false, v -> {
            mode = mode == Mode.BATTLES ? Mode.CART : Mode.BATTLES;
            if (session == null) {
                detail = null;
                warning = false;
            }
            saveSettings();
        });
        LinearLayout modeRow = Ui.row(this);
        Ui.cell(modeRow, modeButton, 0, 40, 0);
        Ui.line(settingsContent, modeRow, 4);

        battleSettings = Ui.stack(this);
        Ui.line(settingsContent, battleSettings, 0);
        LinearLayout farm = Ui.row(this);
        cartEveryButton = Ui.button(this, "", false, v -> {
            if (cartEvery == 0) dockAfterEdit = true;
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
            if (storageGoal != Goal.BATTLE_LIMIT && cartEvery == 0) {
                dockAfterEdit = true;
                cartEvery = 3;
            }
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
            saveSettings();
        });
        overchargeButton = Ui.button(this, "", false, v -> {
            overcharge = overcharge == Overcharge.GAME ? Overcharge.HALF_HP : Overcharge.GAME;
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
        limit = Math.max(1, Math.min(20, limit + direction));
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
        String phase = active ? (paused ? "PAUSED" : expanded ? "EDITING" : "RUNNING")
                : (warning ? "STOPPED" : "READY");
        int tint = held() || warning ? Ui.WARN : Ui.LIME;
        compact.setVisibility(expanded ? View.GONE : View.VISIBLE);
        full.setVisibility(expanded ? View.VISIBLE : View.GONE);
        String summary = paused ? "Paused · " + statusDetail()
                : warning ? "Stopped · " + detail
                : detail != null ? detail.replace('\n', ' ')
                : mode == Mode.CART ? "Ready · Elixir Cart" : "Ready · Builder Battles";
        compactStatus.setText(summary);
        statusHandle.setContentDescription(summary + ". Expand field controls. Drag to move.");
        signal.setBackground(Ui.surface(this, tint, 0, 3));
        String startStopLabel = active ? "Stop" : "Start";
        Mode runningMode = active ? session.mode : mode;
        phaseLabel.setText((runningMode == Mode.CART ? "ELIXIR CART" : "BUILDER BATTLES") + "   /   " + phase);
        phaseLabel.setTextColor(tint);
        String message = statusDetail();
        detailLabel.setText(message);
        renderPrimary(action, startStopLabel, active);
        renderPrimary(quickAction, paused ? "Resume" : startStopLabel, active && !paused);
        second.setVisibility(active ? View.GONE : View.VISIBLE);
        pauseButton.setVisibility(active ? View.VISIBLE : View.GONE);
        pauseButton.setText(paused ? "Resume & close" : "Keep paused");
        historyButton.setText(showHistory ? "Controls" : "History");
        history.setVisibility(showHistory ? View.VISIBLE : View.GONE);
        settingsContent.setVisibility(showHistory ? View.GONE : View.VISIBLE);
        settingsHint.setText(active ? "Automation paused here. Battle settings apply to the next battle; mode on the next Start."
                : "Changes save automatically.");
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
        boolean comparing = strategy == Strategy.COMPARE;
        overchargeButton.setText(comparing ? "P.E.K.K.A.: both" : "P.E.K.K.A.: " + overcharge.label);
        overchargeButton.setEnabled(!comparing);
        overchargeButton.setContentDescription(comparing ? "Compare rotates Game and 50 percent HP Overcharge"
                : overcharge == Overcharge.HALF_HP ? "P.E.K.K.A.: trigger Overcharge at about 50 percent health" : null);
        limitLabel.setText("Battle limit: " + limit);
        ((View) limitLabel.getParent()).setVisibility(storageGoal == Goal.BATTLE_LIMIT ? View.VISIBLE : View.GONE);
        offsetLabel.setText("Troop bar: " + offset);
        int flags = WINDOW_FLAGS
                | (expanded ? WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH : 0)
                | (active && !held() ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0);
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

    private void renderPrimary(Button button, String label, boolean stop) {
        if (!label.contentEquals(button.getText())) {
            button.setText(label);
            Ui.primaryFill(button, stop ? Ui.WARN : Ui.LIME);
        }
    }

    private String statusDetail() {
        if (detail != null) return detail;
        return mode == Mode.CART ? "Start finds the shoreline cart." : "Start from Builder Base or a live battle.";
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
        if (!verify) notifications.cancel(RUN_ALERT);
        control.invalidate();
        session = new Session(settings(verify), journal);
        inFlight = paused = recheckScreen = warning = showHistory = dockAfterEdit = false;
        expanded = expandAfterTick = false;
        detail = "Checking the game screen…";
        render();
        if (mode == Mode.BATTLES && cartEvery > 0) dockUpperLeft();
        schedule(250);
    }

    private Options settings(boolean verify) {
        return new Options(mode, limit, stageTwo, hero, offset, verify, strategy, overcharge,
                cartEvery, storageGoal);
    }

    private void startStop() {
        if (session == null) begin(false);
        else endSession("Stopped. Take over in the game.", false);
    }

    private boolean held() {
        return session != null && (paused || expanded);
    }

    private void togglePause() {
        if (session == null) return;
        boolean wasHeld = held();
        paused = !paused;
        if (!paused && expanded) collapse();
        else updateHold(wasHeld);
        if (!session.verify) {
            if (paused) postRunAlert("Paused", "Use Resume over Clash to continue.");
            else notifications.cancel(RUN_ALERT);
        }
        render();
    }

    private void updateHold(boolean wasHeld) {
        if (session == null || wasHeld == held()) return;
        control.invalidate();
        if (held()) main.removeCallbacks(tick);
        else {
            recheckScreen = true;
            detail = "Checking the current game screen…";
            schedule(250);
        }
    }

    private void schedule(long delay) {
        if (session == null || held() || inFlight) return;
        main.removeCallbacks(tick);
        main.postDelayed(tick, delay);
    }

    private void tick() {
        if (session == null || held() || inFlight) return;
        if (panelLayoutPending) {
            schedule(50);
            return;
        }
        Session current = session;
        Options edited = settings(false);
        int epoch = control.epoch();
        boolean reset = recheckScreen;
        recheckScreen = false;
        inFlight = true;
        worker.execute(() -> {
            Status status = null;
            Exception failure = null;
            try {
                control.require(epoch);
                if (current.vision == null) {
                    RootDevice.checkRoot();
                    current.vision = new AndroidVision(getAssets());
                }
                if (reset) current.run.resume();
                control.require(epoch);
                Options frameOptions = current.run.applySettings(edited);
                try (BuilderVision.Frame board = current.vision.frame(device.capture(), frameOptions,
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
                    status = current.run.step(board, SystemClock.elapsedRealtime());
                }
            } catch (IOException | RuntimeException e) {
                failure = e;
            } catch (LinkageError e) {
                failure = new IOException("Couldn't start the bundled vision library.", e);
            }
            Status result = status;
            Exception error = failure;
            main.post(() -> afterTick(current, epoch, result, error));
        });
    }

    private void requireClearPath(Point start, Point end) throws IOException {
        Rect overlay = panelBounds;
        if (overlay == null) throw new IOException("Couldn't locate the field controls. Show them and try again.");
        if (!overlayClear(overlay, start, end)) {
            throw new BuilderRun.ScreenUnavailable("Field controls cover a target. Move them, then Resume.");
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

    private void afterTick(Session current, int epoch, Status result, Exception error) {
        if (session != current) return;
        inFlight = false;
        boolean screenBlocked = error instanceof RootDevice.InterruptedScreen
                || error instanceof BuilderRun.ScreenUnavailable;
        if (control.epoch() != epoch) {
            // Resume supersedes old observations, but a failed command may have sent input.
            if (screenBlocked) error = null;
            if (result != null && !result.finished()) result = null;
        }
        if (!current.verify && error != null && screenBlocked) {
            suspend(error.getMessage());
        } else if (error != null && !(error instanceof RunControl.Changed)) {
            Log.w("MarbleRunner", "Run stopped", error);
            endSession(error instanceof IOException ? error.getMessage() : "Unexpected runner error. Check logcat.", true);
            return;
        }
        if (result != null && result.finished()) {
            Log.i("MarbleRunner", result.text().replace('\n', ' '));
            if (!current.verify && result.goalReached()) postRunAlert("Goal reached", result.text());
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

    private void suspend(String message) {
        Log.i("MarbleRunner", "Paused: " + message);
        boolean wasHeld = held();
        boolean alert = !paused && session != null && !session.verify;
        paused = true;
        detail = message;
        updateHold(wasHeld);
        if (alert) postRunAlert("Paused", message);
        render();
    }

    private void endSession(String message, boolean failed) {
        if (session != null && !session.verify && failed) postRunAlert("Needs attention", message);
        clearSession();
        detail = message;
        warning = failed;
        expanded = showHistory = expandAfterTick = dockAfterEdit = false;
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
        if (session != null) suspend("Return to Clash in landscape.");
        if (panel != null) panel.post(() -> {
            if (dockedLeft) dockUpperLeft();
            else clampPanel();
        });
    }

    private Notification notification() {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setColor(Ui.LIME).setSmallIcon(R.drawable.ic_status).setContentTitle(getString(R.string.app_name)
                        + (session == null ? "" : paused ? " · Paused" : " · Active"))
                .setContentText(statusDetail()).setOnlyAlertOnce(true).setOngoing(true)
                .setContentIntent(openApp());
        if (session != null) {
            builder.addAction(new Notification.Action.Builder(null, paused ? "Resume" : "Pause",
                    notificationAction(TOGGLE)).build());
        }
        builder.addAction(new Notification.Action.Builder(null, "Stop & close", notificationAction(CLOSE)).build());
        return builder.build();
    }

    private void postRunAlert(String title, String message) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        notifications.notify(RUN_ALERT, new Notification.Builder(this, ALERT_CHANNEL)
                .setColor(Ui.LIME).setSmallIcon(R.drawable.ic_status)
                .setContentTitle(getString(R.string.app_name) + " · " + title)
                .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true).setContentIntent(openApp()).build());
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent notificationAction(String action) {
        return PendingIntent.getService(this, 0, new Intent(this, OverlayService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onDestroy() {
        clearSession();
        worker.execute(journal::close);
        worker.shutdown();
        panelBounds = null;
        if (panel != null && panel.isAttachedToWindow()) windows.removeViewImmediate(panel);
        panel = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
