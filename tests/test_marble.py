import shlex
import shutil
import struct
import subprocess
from argparse import Namespace
from itertools import chain, repeat
from pathlib import Path
from unittest.mock import Mock

import cv2
import numpy as np
import pytest

from farmbot import marble_pc as marble
from farmbot.device import DeviceError
from farmbot.game import Game, GameNotInFront, T
from farmbot.tasks import builder
from farmbot.vision import TemplateLibrary, Vision


@pytest.fixture
def phone():
    """A landscape display with the bundled buttons placed on synthetic frames."""
    adb = Mock(spec=marble.UsbDevice)
    library = TemplateLibrary(Path(__file__).resolve().parents[1] / "templates")
    elapsed = [0.0]

    def advance(seconds):
        elapsed[0] += seconds

    game = Game(
        marble.MarbleScreen(adb),
        Vision(library),
        clock=lambda: elapsed[0],
        sleep=advance,
    )

    def frame(*buttons):
        screen = np.full((920, 2050, 3), 80, dtype=np.uint8)
        for name, x, y, *muted in buttons:
            _, image = library.get(name)[0]
            if muted:
                image = cv2.cvtColor(
                    cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), cv2.COLOR_GRAY2BGR
                )
            h, w = image.shape[:2]
            screen[y : y + h, x : x + w] = image
        return screen

    return adb, game, frame


USB_LISTING = (
    b"List of devices attached\nmyphone device usb:1-2 device:marble transport_id:8\n"
)
GAME_FOCUS = (
    "  mCurrentFocus=Window{abcd u0 com.supercell.clashofclans/.GameActivity}\n"
)
SH = shutil.which("sh")


@pytest.fixture
def usb_shell():
    if SH is None:
        pytest.skip("Needs a shell to run the guarded ADB command against phone stubs")

    def phone(
        *,
        serial="myphone",
        codename="marble",
        kernel_qemu="",
        boot_qemu="",
        focus=GAME_FOCUS,
    ):
        def run(args):
            if args == ["adb", "devices", "-l"]:
                return USB_LISTING
            assert args[:5] == ["adb", "-t", "8", "shell", "-T"]
            preamble = f"""
getprop() {{
    case "$1" in
      ro.serialno) printf %s {shlex.quote(serial)} ;;
      ro.product.device) printf %s {shlex.quote(codename)} ;;
      ro.kernel.qemu) printf %s {shlex.quote(kernel_qemu)} ;;
      ro.boot.qemu) printf %s {shlex.quote(boot_qemu)} ;;
    esac
}}
dumpsys() {{ printf %s {shlex.quote(focus)}; }}
input() {{ echo "input $*" >&2; }}
screencap() {{ echo "screencap $*" >&2; }}
"""
            result = subprocess.run(
                [SH, "-c", preamble + args[5].replace("/system/bin/", "")],
                capture_output=True,
                timeout=5,
                check=False,
            )
            if result.returncode:
                assert not result.stderr, (
                    "A rejected command must not reach input or capture"
                )
                raise DeviceError(f"adb failed ({result.returncode}):")
            return result.stderr or result.stdout

        return run

    return phone


@pytest.mark.parametrize(
    "properties", [{"codename": "emu64a"}, {"kernel_qemu": "1"}, {"boot_qemu": "1"}]
)
def test_rejects_other_devices_and_emulators(usb_shell, properties):
    with pytest.raises(DeviceError, match="physical Android device"):
        marble.open_usb_marble(None, usb_shell(**properties))


@pytest.mark.parametrize(
    "focus",
    [
        "  mCurrentFocus=null\n" + GAME_FOCUS.replace("mCurrentFocus=", "mFocusedApp="),
        GAME_FOCUS + "  mCurrentFocus=null\n",
        "  mCurrentFocus=Window{abcd u0 com.supercell.clashofclans.other/.Activity}\n",
    ],
)
def test_wrong_or_ambiguous_foreground_stops_capture_and_input(usb_shell, focus):
    adb = marble.open_usb_marble(None, usb_shell(focus=focus))
    with pytest.raises(GameNotInFront):
        adb.screenshot()
    with pytest.raises(GameNotInFront):
        adb.tap(10, 10)
    with pytest.raises(GameNotInFront):
        adb.swipe(10, 10, 20, 20, 520)


def test_rejects_display_change_and_out_of_bounds_tap(phone):
    adb, game, frame = phone
    adb.screenshot.side_effect = [frame(), frame()[:, :1900]]
    game.refresh()
    with pytest.raises(DeviceError, match="outside"):
        game.tap(2060, 10)
    with pytest.raises(DeviceError, match="full-screen landscape"):
        game.refresh()
    adb.tap.assert_not_called()


def test_usb_transport_is_pinned_and_disconnect_does_not_retry_the_tap():
    runner = Mock(
        side_effect=[
            USB_LISTING,
            USB_LISTING,
            b"",
            USB_LISTING,
            DeviceError("device offline"),
        ]
    )
    adb = marble.open_usb_marble(None, runner)
    with pytest.raises(DeviceError, match="offline"):
        adb.tap(3, 4)
    assert runner.call_count == 5
    command = runner.call_args.args[0]
    assert command[:5] == ["adb", "-t", "8", "shell", "-T"]
    assert command[5].endswith("/system/bin/input -d 0 tap 3 4")


def test_changed_usb_transport_rejects_input_without_opening_a_device_shell(usb_shell):
    runner = Mock(side_effect=usb_shell())
    adb = marble.open_usb_marble(None, runner)
    runner.side_effect = None
    runner.return_value = USB_LISTING.replace(b"transport_id:8", b"transport_id:9")
    calls = runner.call_count
    with pytest.raises(DeviceError, match="transport changed"):
        adb.tap(3, 4)
    assert runner.call_count == calls + 1
    runner.assert_called_with(["adb", "devices", "-l"])


def test_recycled_transport_cannot_send_input_to_another_phone(usb_shell):
    runner = Mock(side_effect=usb_shell())
    adb = marble.open_usb_marble(None, runner)
    runner.side_effect = usb_shell(serial="different-phone")
    with pytest.raises(DeviceError, match="authorized USB device changed"):
        adb.tap(3, 4)


def test_guard_allows_input_to_the_authorized_phone_in_game(usb_shell):
    adb = marble.open_usb_marble(None, usb_shell())
    assert adb.shell("/system/bin/input -d 0 tap 3 4") == b"input -d 0 tap 3 4\n"


@pytest.mark.parametrize("raw", [True, False])
def test_guarded_binary_capture_preserves_pixel_values(raw):
    bgr = (7, 10, 255)
    image = np.full((2, 3, 3), bgr, dtype=np.uint8)
    capture = struct.pack("<4I", 3, 2, 1, 1) + bytes((*reversed(bgr), 255)) * 6
    outputs = [capture] if raw else [b"", cv2.imencode(".png", image)[1].tobytes()]
    runner = Mock(
        side_effect=[
            USB_LISTING,
            USB_LISTING,
            b"",
            *(data for output in outputs for data in (USB_LISTING, output)),
        ]
    )
    adb = marble.open_usb_marble(None, runner)
    np.testing.assert_array_equal(adb.screenshot(), image)
    last_command = runner.call_args.args[0]
    assert last_command[:5] == ["adb", "-t", "8", "shell", "-T"]
    assert last_command[5].endswith(
        "/system/bin/screencap" if raw else "/system/bin/screencap -p"
    )


@pytest.mark.parametrize(
    "listed",
    [
        b"emulator-5554 device usb:1-2 device:marble transport_id:9\n",
        b"192.0.2.5:5555 device device:marble transport_id:9\n",
        b"wireless._adb-tls-connect._tcp device device:marble transport_id:9\n",
        b"myphone unauthorized usb:1-2 transport_id:9\n",
        b"myphone device usb:1-2 device:marble\n",
    ],
)
def test_requires_an_authorized_usb_transport(listed):
    runner = Mock(return_value=b"List of devices attached\n" + listed)
    with pytest.raises(DeviceError, match="authorized USB phone"):
        marble.open_usb_marble(None, runner)
    runner.assert_called_once_with(["adb", "devices", "-l"])


def test_multiple_usb_devices_require_explicit_selection():
    listed = (
        b"List of devices attached\n"
        b"other device usb:1-1 transport_id:5\n"
        b"myphone device usb:1-2 transport_id:8\n"
    )
    runner = Mock(return_value=listed)
    with pytest.raises(DeviceError, match="Multiple USB devices"):
        marble.open_usb_marble(None, runner)
    runner.assert_called_once_with(["adb", "devices", "-l"])

    runner = Mock(side_effect=[listed, listed, b""])
    adb = marble.open_usb_marble("myphone", runner)
    assert adb.address == marble.UsbAddress("myphone", "1-2", "8")
    assert runner.call_args.args[0][:5] == ["adb", "-t", "8", "shell", "-T"]


def test_check_requires_active_builder_button_at_bottom_left_but_never_taps(phone):
    adb, game, frame = phone
    args = Namespace(cart_only=False, check=True, card_offset_x=0, battles=1)
    adb.screenshot.return_value = frame((T.BB_BATTLE, 995, 150))
    with pytest.raises(builder.BuilderError, match="isn't visible"):
        marble.run(game, args)
    adb.screenshot.return_value = frame((T.BB_BATTLE, 24, 784)) // 2
    with pytest.raises(builder.BuilderError, match="isn't visible"):
        marble.run(game, args)
    adb.screenshot.return_value = frame((T.BB_BATTLE, 24, 784))
    marble.run(game, args)
    adb.tap.assert_not_called()


@pytest.mark.parametrize(
    "check, muted, taps", [(True, False, 0), (False, True, 0), (False, False, 1)]
)
def test_cart_requires_active_collect_and_check_never_taps(phone, check, muted, taps):
    adb, game, frame = phone
    button = (T.BB_CART_COLLECT, 1250, 780)
    if muted:
        button += (True,)
    adb.screenshot.return_value = frame((T.BB_CART_TITLE, 925, 74), button)
    marble.run(game, Namespace(cart_only=True, check=check))
    assert adb.tap.call_count == taps
    if taps:
        x, y = adb.tap.call_args.args
        assert 1250 <= x < 1340 and 780 <= y < 806


def test_cart_does_not_click_collect_without_the_title(phone):
    adb, game, frame = phone
    dimmed = frame((T.BB_BATTLE, 24, 784)) // 2
    button = frame((T.BB_CART_COLLECT, 1250, 780))
    dimmed[780:806, 1250:1340] = button[780:806, 1250:1340]
    adb.screenshot.return_value = dimmed
    with pytest.raises(builder.BuilderError, match="Builder Base isn't visible"):
        marble.run(game, Namespace(cart_only=True, check=False))
    adb.tap.assert_not_called()
    adb.swipe.assert_not_called()


@pytest.mark.parametrize("cart_has_rewards", [True, False])
def test_cart_pans_then_opens_and_only_collects_active_rewards(phone, cart_has_rewards):
    adb, game, frame = phone
    base = (T.BB_BATTLE, 24, 784)
    title = (T.BB_CART_TITLE, 925, 74)
    adb.screenshot.return_value = frame(base)
    marble.run(game, Namespace(cart_only=True, check=True))
    adb.tap.assert_not_called()
    adb.swipe.assert_not_called()

    def pan(*_):
        name = T.BB_CART_WORLD if cart_has_rewards else f"{T.BB_CART_WORLD}/empty"
        adb.screenshot.return_value = frame(base, (name, 965, 225))

    def tap(x, y):
        if y < 300:
            assert 965 <= x < 1050 and 225 <= y < 302
            collect = (T.BB_CART_COLLECT, 1250, 780)
            if not cart_has_rewards:
                collect += (True,)
            adb.screenshot.return_value = frame(title, collect)
        else:
            assert 1250 <= x < 1340 and 780 <= y < 806
            adb.screenshot.return_value = frame(
                title, (T.BB_CART_COLLECT, 1250, 780, True)
            )

    adb.swipe.side_effect = pan
    adb.tap.side_effect = tap
    marble.run(game, Namespace(cart_only=True, check=False))
    adb.swipe.assert_called_once()
    assert adb.tap.call_count == (2 if cart_has_rewards else 1)


@pytest.mark.parametrize("button", [T.CLOSE, T.BB_FIND_NOW])
def test_battle_does_not_click_unrelated_popup_or_disabled_find_now(phone, button):
    adb, game, frame = phone
    base = frame((T.BB_BATTLE, 24, 784))
    popup = frame((button, 947, 572, True))
    adb.screenshot.return_value = base
    game.refresh()
    cfg = marble.battle_config(game, card_offset_x=0)
    adb.tap.side_effect = lambda *_: setattr(adb.screenshot, "return_value", popup)
    with pytest.raises(builder.BuilderError, match="Find Now wasn't visible"):
        marble.run_battle(game, cfg)
    assert adb.tap.call_count == 1
    x, y = adb.tap.call_args.args
    assert 24 <= x < 107 and 784 <= y < 861


def test_battle_does_not_tap_dimmed_builder_button_behind_a_dialog(phone):
    adb, game, frame = phone
    adb.screenshot.return_value = frame((T.BB_BATTLE, 24, 784)) // 2
    game.refresh()
    cfg = marble.battle_config(game, card_offset_x=0)
    with pytest.raises(builder.BuilderError, match="Builder Base isn't visible"):
        marble.run_battle(game, cfg)
    adb.tap.assert_not_called()


@pytest.mark.parametrize("combat_during_deployment", [False, True])
def test_rejected_first_drop_does_not_advance_until_combat_then_a_new_countdown(
    phone, combat_during_deployment
):
    adb, game, frame = phone
    starts = frame((T.BB_BATTLE_STARTS, 954, 70))
    ends = frame((T.BB_BATTLE_ENDS, 954, 70))
    result = frame((T.BB_RETURN_HOME, 984, 735))
    base = frame((T.BB_BATTLE, 24, 784))
    adb.screenshot.side_effect = chain(
        [starts, starts, ends, starts, result], repeat(base)
    )
    deployments = []
    stage = marble.StageTransition(T.BB_BATTLE_STARTS)

    def deploy(game, cfg, first):
        deployments.append((first, adb.screenshot.call_count))
        landed = game.clock()
        if first and combat_during_deployment:
            for _ in range(2):
                game.refresh()
                stage.observe(game)
        return landed, []

    battle = builder.fight(
        game,
        marble.battle_config(game, card_offset_x=0),
        deploy=deploy,
        stage_started=stage.started,
        return_button=marble.return_button,
    )
    assert battle.stages == 2
    assert deployments == [(True, 1), (False, 4)]
    adb.tap.assert_called_once()  # Return Home.


def test_plays_one_builder_battle_and_returns(phone):
    adb, game, frame = phone
    base = frame((T.BB_BATTLE, 24, 784))
    menu = frame((T.BB_FIND_NOW, 947, 572))
    battle = frame((T.BB_BATTLE_STARTS, 954, 70))
    battle[796:812, 591:651] = (255, 0, 180)  # ready troop's purple band
    battle[820:860:2, 591:651] = 255  # detail distinguishes the card from a blank slot
    result = frame()
    _, original = game.vision.library.get(T.BB_RETURN_HOME)[0]
    button = cv2.resize(original, None, fx=0.94, fy=0.94, interpolation=cv2.INTER_AREA)
    height, width = button.shape[:2]
    result[735 : 735 + height, 984 : 984 + width] = button

    def tap(x, y):
        screen = adb.screenshot.return_value
        if screen is base:
            assert 24 <= x < 107 and 784 <= y < 861
            adb.screenshot.return_value = menu
        elif screen is menu:
            assert 947 <= x < 1103 and 572 <= y < 601
            adb.screenshot.return_value = battle
        elif screen is battle:
            if y >= 800:
                assert (x, y) == (621, 830)
            else:
                assert abs(x - game.size[0] * 19 // 100) <= 3
                assert abs(y - game.size[1] * 49 // 100) <= 3
                adb.screenshot.return_value = result
        else:
            assert (
                screen is result and 984 <= x < 984 + width and 735 <= y < 735 + height
            )
            adb.screenshot.return_value = base

    adb.screenshot.return_value = base
    adb.tap.side_effect = tap
    marble.run(
        game, Namespace(cart_only=False, check=False, card_offset_x=0, battles=1)
    )
    assert adb.tap.call_count == 5
    assert adb.screenshot.return_value is base
