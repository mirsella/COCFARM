package org.cocfarm.marble;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class RootDevice {
    static final String GAME = "com.supercell.clashofclans";
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    // Refuse ambiguous focus (including multiple focused displays), not mFocusedApp.
    static final String FOCUS_GUARD = """
            focus=$(/system/bin/dumpsys window displays |
                /system/bin/grep -E '^[[:space:]]*mCurrentFocus=')
            [ "$(printf '%s\\n' "$focus" | /system/bin/wc -l)" -eq 1 ] &&
                printf '%s\\n' "$focus" | /system/bin/grep -Eq \
            '^[[:space:]]*mCurrentFocus=Window\\{[^}]*[[:space:]]com\\.supercell\\.clashofclans/' ||
                exit 42
            """;
    private final PowerManager power;
    private final KeyguardManager keyguard;

    RootDevice(Context context) {
        power = context.getSystemService(PowerManager.class);
        keyguard = context.getSystemService(KeyguardManager.class);
    }

    static void checkRoot() throws IOException {
        requireMarble();
        byte[] output = execute(() -> su("exec /system/bin/id -u"), 15);
        if (!"0".equals(new String(output, StandardCharsets.UTF_8).trim())) {
            throw new IOException("KernelSU did not grant root to Marble Runner.");
        }
    }

    private static void requireMarble() throws IOException {
        if (!"marble".equals(Build.DEVICE) || Build.SUPPORTED_64_BIT_ABIS.length == 0
                || isEmulatorProp("ro.kernel.qemu") || isEmulatorProp("ro.boot.qemu")) {
            throw new IOException("Only the physical marble phone is supported.");
        }
    }

    private static boolean isEmulatorProp(String name) throws IOException {
        String value = new String(execute(
                () -> new ProcessBuilder("/system/bin/getprop", name).start(), 3),
                StandardCharsets.UTF_8).trim();
        return !value.isEmpty() && !"0".equals(value);
    }

    byte[] capture() throws IOException {
        requireUnlocked();
        return guarded(() -> su(FOCUS_GUARD + "exec /system/bin/screencap"));
    }

    void tap(int x, int y, RunControl control, int epoch) throws IOException {
        input("tap " + x + " " + y, control, epoch);
    }

    void swipe(int x1, int y1, int x2, int y2, RunControl control, int epoch) throws IOException {
        input("swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " 520", control, epoch);
    }

    private void input(String command, RunControl control, int epoch) throws IOException {
        requireUnlocked();
        guarded(() -> control.issue(epoch,
                () -> su(FOCUS_GUARD + "exec /system/bin/input -d 0 " + command)));
    }

    private void requireUnlocked() throws IOException {
        if (!power.isInteractive() || keyguard.isKeyguardLocked()) {
            throw new IOException("The phone is locked or its screen is off.");
        }
    }

    private static byte[] guarded(RunControl.StartProcess start) throws IOException {
        return execute(start, 8);
    }

    private static Process su(String command) throws IOException {
        try {
            return new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("Couldn't open su. Check KernelSU and grant this app root.", e);
        }
    }

    private static byte[] execute(RunControl.StartProcess start, int timeout) throws IOException {
        Process process = start.start();
        FutureTask<byte[]> read = new FutureTask<>(() -> {
            try (InputStream input = process.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[32 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (output.size() + count > MAX_BYTES) throw new IOException("Root output too large.");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        });
        try {
            process.getOutputStream().close();
            new Thread(read, "marble-root-output").start();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) throw new IOException("Root command timed out.");
            int exit = process.exitValue();
            if (exit == 42) throw new IOException("Clash of Clans isn't the focused window.");
            if (exit != 0) throw new IOException("Root command failed (" + exit + "). Check KernelSU.");
            return read.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Root command interrupted.", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Couldn't read root command output.", e);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            read.cancel(true);
        }
    }
}
