package org.cocfarm.marble;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A fixed, explicitly selected physical Android transport. Never retries input. */
final class AdbPhone {
    static final class ScreenInterrupted extends IOException {
        ScreenInterrupted(String message, Throwable cause) { super(message, cause); }
    }

    private static final String FOCUS = """
            focus=$(/system/bin/dumpsys window displays | /system/bin/grep -E '^[[:space:]]*mCurrentFocus=')
            [ "$(printf '%s\\n' "$focus" | /system/bin/wc -l)" -eq 1 ] &&
                printf '%s\\n' "$focus" | /system/bin/grep -Eq \
                '^[[:space:]]*mCurrentFocus=Window\\{[^}]*[[:space:]]com\\.supercell\\.clashofclans/' || exit 42
            """;
    private final String device;
    private final String transport;
    private final String hardware;
    private final boolean exclusive;
    final boolean usb;

    AdbPhone(String device, String expectedHardware, boolean wireless, boolean exclusive) throws IOException {
        this.device = device;
        this.exclusive = exclusive;
        String[] listing = listing();
        transport = Arrays.stream(listing).filter(field -> field.matches("transport_id:[0-9]+"))
                .findFirst().orElseThrow(() -> new IOException("ADB did not report a transport ID.")).substring(13);
        usb = Arrays.stream(listing).anyMatch(field -> field.startsWith("usb:") && field.length() > 4);
        if (!usb && !wireless) throw new IOException("Not USB. Explicitly pass --wireless to use this device.");
        hardware = expectedHardware == null && usb ? device : expectedHardware;
        if (hardware == null || !hardware.matches("[A-Za-z0-9_-]{4,80}")) {
            throw new IOException("Wireless debugging requires --hardware with the expected phone serial.");
        }
    }

    byte[] capture(int limit) throws IOException {
        // Refuse to record the image if Clash lost focus while it was being captured.
        return guarded("/system/bin/screencap" + (usb ? "" : " -p") + " || exit 46\n" + FOCUS, limit);
    }

    private String[] listing() throws IOException {
        String devices = new String(HostRun.process(262_144, 10, null, "adb", "devices", "-l"), StandardCharsets.UTF_8);
        return devices.lines().map(line -> line.strip().split("\\s+"))
                .filter(fields -> fields.length > 2 && fields[0].equals(device) && fields[1].equals("device"))
                .findFirst().orElseThrow(() -> new IOException("Selected phone is not authorized or connected."));
    }

    byte[] guarded(String operation, int limit) throws IOException {
        if (!Arrays.asList(listing()).contains("transport_id:" + transport)) {
            throw new IOException("Phone transport changed; refusing to continue.");
        }
        String identity = """
                [ "$(/system/bin/getprop ro.serialno)" = '%s' ] &&
                    [ "$(/system/bin/getprop ro.product.device)" = marble ] || exit 43
                for prop in ro.kernel.qemu ro.boot.qemu; do
                    case "$(/system/bin/getprop "$prop")" in ''|0) ;; *) exit 43 ;; esac
                done
                """.formatted(hardware);
        String owner = exclusive ? """
                /system/bin/pidof org.cocfarm.marble >/dev/null && exit 44
                policy=$(/system/bin/dumpsys window policy)
                printf '%s\\n' "$policy" | /system/bin/grep -Eq '^[[:space:]]*screenState=SCREEN_STATE_ON$' &&
                    printf '%s\\n' "$policy" | /system/bin/grep -Eq '^[[:space:]]*interactiveState=INTERACTIVE_STATE_AWAKE$' &&
                    printf '%s\\n' "$policy" | /system/bin/grep -Eq '^[[:space:]]*showing=false$' || exit 45
                """ : "";
        try {
            return HostRun.process(limit, 12, null,
                    "adb", "-t", transport, "shell", "-T", identity + owner + FOCUS + operation);
        } catch (HostRun.CommandFailed e) {
            if (e.code == 42 || e.code == 45) {
                throw new ScreenInterrupted(e.code == 42 ? "Clash of Clans lost focus."
                        : "The phone is locked, the screen is off, or unlock state isn't verifiable.", e);
            }
            throw new IOException(switch (e.code) {
                case 43 -> "The selected transport isn't the same physical marble phone.";
                case 44 -> "COCFARM is already open on the phone. Close it before starting a host-controlled run.";
                case 46 -> "The phone couldn't capture the screen.";
                default -> e.getMessage();
            }, e);
        }
    }

}
