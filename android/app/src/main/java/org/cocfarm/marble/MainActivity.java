package org.cocfarm.marble;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

public final class MainActivity extends Activity {
    private Button overlay;
    private TextView permission;
    private TextView rootStatus;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(Ui.BACK);
        LinearLayout content = Ui.stack(this);
        int inset = Ui.dp(this, 22);
        getWindow().setDecorFitsSystemWindows(false);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            var system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            content.setPadding(inset + system.left, inset + system.top,
                    inset + system.right, inset + system.bottom);
            return insets;
        });
        page.addView(content);
        setContentView(page);

        Ui.line(content, Ui.text(this, "MARBLE    /    DEVICE 01", 12, Ui.LIME, true), 8);
        Ui.line(content, Ui.text(this, getString(R.string.app_name), 46, Ui.TEXT, false), 4);
        Ui.line(content, Ui.text(this, "Builder Base field controls", 20, Ui.MUTED, false), 1);
        Ui.line(content, Ui.text(this,
                "Give COCFARM root in KernelSU Manager. Nothing starts until you tap Start over the game.",
                16, Ui.TEXT, false), 20);

        LinearLayout setup = Ui.stack(this);
        int pad = Ui.dp(this, 14);
        setup.setPadding(pad, pad, pad, pad);
        setup.setBackground(Ui.surface(this, Ui.PANEL, Ui.EDGE, 8));
        Ui.line(content, setup, 22);
        rootStatus = Ui.text(this, "01  /  ROOT NOT CHECKED", 12, Ui.MUTED, true);
        Ui.line(setup, rootStatus, 0);
        row(setup, Ui.button(this, "Check KernelSU", false, v -> checkRoot((Button) v)), 8);
        permission = Ui.text(this, "", 12, Ui.MUTED, true);
        Ui.line(setup, permission, 16);
        row(setup, Ui.button(this, "Manage overlay permission", false, v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))), 8);

        overlay = Ui.button(this, "Show field controls", true, v -> openOverlay());
        row(content, overlay, 18);
        row(content, Ui.button(this, "Open Clash of Clans", false, v -> {
            Intent game = getPackageManager().getLaunchIntentForPackage(RootDevice.GAME);
            if (game == null) Toast.makeText(this, "Clash of Clans isn't installed.", Toast.LENGTH_LONG).show();
            else startActivity(game);
        }), 8);
        row(content, Ui.button(this, "Manage run alerts", false, v -> startActivity(new Intent(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))), 8);
        Ui.line(content, Ui.text(this, "Use English and full-screen landscape. Run Check from the overlay before your first battle.",
                15, Ui.MUTED, false), 16);
        Ui.line(content, Ui.text(this, "Game automation can get your Supercell account banned.",
                15, Ui.WARN, false), 10);
    }

    @Override public void onResume() {
        super.onResume();
        boolean allowed = Settings.canDrawOverlays(this);
        permission.setText(allowed ? "02  /  OVERLAY ALLOWED" : "02  /  OVERLAY NOT ALLOWED");
        permission.setTextColor(allowed ? Ui.LIME : Ui.MUTED);
        overlay.setEnabled(allowed);
        overlay.setAlpha(allowed ? 1f : .45f);
    }

    private void openOverlay() {
        if (!Settings.canDrawOverlays(this)) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        showOverlay();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 1 && Settings.canDrawOverlays(this)) showOverlay();
    }

    private void showOverlay() {
        try {
            startForegroundService(new Intent(this, OverlayService.class).setAction(OverlayService.SHOW));
        } catch (RuntimeException e) {
            android.util.Log.e("MarbleRunner", "Couldn't start overlay", e);
            Toast.makeText(this, "Couldn't start the overlay. Reopen COCFARM and try again.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void checkRoot(Button button) {
        button.setEnabled(false);
        rootStatus.setText("01  /  CHECKING ROOT…");
        rootStatus.setTextColor(Ui.MUTED);
        new Thread(() -> {
            String error = null;
            try {
                RootDevice.checkRoot();
            } catch (IOException e) {
                error = e.getMessage();
            }
            String result = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                rootStatus.setText(result == null ? "01  /  KERNELSU ROOT READY" : result);
                rootStatus.setTextColor(result == null ? Ui.LIME : Ui.WARN);
                button.setEnabled(true);
            });
        }, "marble-root-check").start();
    }

    private void row(LinearLayout parent, Button button, int margin) {
        LinearLayout row = Ui.row(this);
        Ui.cell(row, button, 0, 48, 0);
        Ui.line(parent, row, margin);
    }
}
