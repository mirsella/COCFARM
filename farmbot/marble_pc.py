"""Run Builder Base on a physical marble phone over USB from a computer."""

from __future__ import annotations

import argparse
import logging
import shlex
import sys
from collections.abc import Callable
from dataclasses import dataclass, replace
from functools import partial
from pathlib import Path

import cv2
import numpy as np

from farmbot import standard
from farmbot.config import BuilderConfig
from farmbot.device import DeviceError, Runner, _run, decode_raw
from farmbot.game import REFERENCE_HEIGHT, Game, GameNotInFront, T
from farmbot.tasks import builder
from farmbot.vision import Match, TemplateLibrary, Vision, match_template

FOCUS_GUARD = r"""
focus=$(/system/bin/dumpsys window displays |
    /system/bin/grep -E '^[[:space:]]*mCurrentFocus=')
[ "$(printf '%s\n' "$focus" | /system/bin/wc -l)" -eq 1 ] &&
    printf '%s\n' "$focus" | /system/bin/grep -Eq \
    '^[[:space:]]*mCurrentFocus=Window\{[^}]*[[:space:]]com\.supercell\.clashofclans/' ||
    exit 42
"""
REFERENCE_WIDTH = round(standard.WIDTH * REFERENCE_HEIGHT / standard.HEIGHT)
PHONE_TEMPLATE_SIZES = (0.94, 0.97, 1.0, 1.035, 1.07)
BANNER_SIZES = (*PHONE_TEMPLATE_SIZES, 1.055, 1.09)
CART_SIZES = (1.0, 0.70, 0.82, 0.94, 1.07, 1.20, 1.35)
CART_PANS = ((57, 22, 45, 72), *((61, 62, 32, 28),) * 3, (57, 22, 45, 72))
log = logging.getLogger(__name__)


@dataclass(frozen=True)
class UsbAddress:
    serial: str
    path: str
    transport: str


def usb_addresses(runner: Runner) -> list[UsbAddress]:
    devices = []
    for line in runner(["adb", "devices", "-l"]).decode(errors="replace").splitlines():
        fields = line.split()
        if len(fields) < 3 or fields[1] != "device":
            continue
        name = fields[0]
        if name.startswith("emulator-") or ":" in name:
            continue
        properties = dict(field.split(":", 1) for field in fields[2:] if ":" in field)
        path = properties.get("usb", "")
        transport = properties.get("transport_id", "")
        if path and transport.isdecimal():
            devices.append(UsbAddress(name, path, transport))
    return devices


def open_usb_marble(serial: str | None, runner: Runner = _run) -> UsbDevice:
    devices = [
        device for device in usb_addresses(runner) if serial in (None, device.serial)
    ]
    if not devices:
        raise DeviceError(
            "No matching authorized USB phone. Attach it and approve USB debugging."
        )
    if len(devices) != 1:
        raise DeviceError(
            "Multiple USB devices are connected. Pass --serial to choose your phone."
        )
    device = UsbDevice(devices[0], runner)
    device.shell(":", require_focus=False)
    return device


class UsbDevice:
    """Guard captures and stock input inside the shell selected for the command."""

    def __init__(self, address: UsbAddress, runner: Runner):
        self.address = address
        self._run = runner
        self._raw = True

    def shell(self, command: str, *, require_focus: bool = True) -> bytes:
        if self.address not in usb_addresses(self._run):
            raise DeviceError("The authorized USB transport changed. Start again.")
        # Transport IDs can be recycled by a new server between the listing and
        # the command. Check the physical identity in the selected shell too.
        script = f"""
[ "$(/system/bin/getprop ro.serialno)" = {shlex.quote(self.address.serial)} ] ||
    exit 43
[ "$(/system/bin/getprop ro.product.device)" = marble ] || exit 44
for prop in ro.kernel.qemu ro.boot.qemu; do
    case "$(/system/bin/getprop "$prop")" in ''|0) ;; *) exit 44 ;; esac
done
"""
        if require_focus:
            script += FOCUS_GUARD
        try:
            return self._run(
                ["adb", "-t", self.address.transport, "shell", "-T", script + command]
            )
        except DeviceError as exc:
            if str(exc).startswith("adb failed (42):"):
                raise GameNotInFront(
                    "Clash of Clans isn't the focused window."
                ) from exc
            if str(exc).startswith("adb failed (43):"):
                raise DeviceError(
                    "The authorized USB device changed or has no matching hardware serial."
                ) from exc
            if str(exc).startswith("adb failed (44):"):
                raise DeviceError(
                    "Only the physical Android device codenamed marble is supported."
                ) from exc
            raise

    def screenshot(self) -> np.ndarray:
        if self._raw:
            image = decode_raw(self.shell("/system/bin/screencap"))
            if image is not None:
                return image
            log.info("Raw screenshots not understood; using PNG")
            self._raw = False
        png = self.shell("/system/bin/screencap -p")
        image = (
            cv2.imdecode(np.frombuffer(png, np.uint8), cv2.IMREAD_COLOR)
            if png
            else None
        )
        if image is None:
            raise DeviceError("screencap returned no decodable image")
        return image

    def tap(self, x: int, y: int) -> None:
        self.shell(f"/system/bin/input -d 0 tap {x:d} {y:d}")

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int) -> None:
        self.shell(
            f"/system/bin/input -d 0 swipe {x1:d} {y1:d} {x2:d} {y2:d} {duration_ms:d}"
        )


class MarbleScreen:
    """Only expose screen reads and ordinary input; no raw input or app management."""

    def __init__(self, adb: UsbDevice):
        self.adb = adb
        self.size: tuple[int, int] | None = None

    def screenshot(self) -> np.ndarray:
        screen = self.adb.screenshot()
        h, w = screen.shape[:2]
        if w <= h or (self.size is not None and self.size != (w, h)):
            raise DeviceError(
                "Keep Clash of Clans in full-screen landscape without changing its display size."
            )
        self.size = (w, h)
        return screen

    def tap(self, x: int, y: int) -> None:
        self._require_point(x, y)
        self.adb.tap(x, y)

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int) -> None:
        self._require_point(x1, y1)
        self._require_point(x2, y2)
        self.adb.swipe(x1, y1, x2, y2, duration_ms)

    def _require_point(self, x: int, y: int) -> None:
        if self.size is None or not (0 <= x < self.size[0] and 0 <= y < self.size[1]):
            raise DeviceError(
                f"Refusing input outside the last landscape screenshot: ({x}, {y})."
            )


def require_base(game: Game) -> Match:
    base = active_base_button(game)
    if base is None:
        raise builder.BuilderError(
            "Builder Base isn't visible. Open the second village and dismiss any popups manually."
        )
    return base


def battle_config(game: Game, card_offset_x: int) -> BuilderConfig:
    """Center the card bar horizontally and keep it anchored to the bottom."""
    w, h = game.size
    cfg = BuilderConfig(
        switch_hero=False,
        hero_ability=False,
        troop_abilities=False,
        speed_up=False,
        collect_resources=False,
        clock_tower=False,
    )
    cfg.card_y += h - REFERENCE_HEIGHT
    bar_left = max(0, (w - REFERENCE_WIDTH) // 2) + card_offset_x
    cfg.hero_x += bar_left
    cfg.first_troop_x += bar_left
    if not (
        43 <= cfg.hero_x < cfg.first_troop_x < w - 30 and 66 <= cfg.card_y < h - 50
    ):
        raise builder.BuilderError(
            "Builder troop bar coordinates are outside the screenshot."
        )
    while cfg.max_troop_cards and builder.slot_x(cfg.max_troop_cards, cfg) + 30 > w:
        cfg.max_troop_cards -= 1
    if h * 49 // 100 >= cfg.card_y - 85:
        raise builder.BuilderError(
            "The Builder Battle map doesn't fit this screen calibration."
        )
    return cfg


def phone_match(
    game: Game, name: str, threshold: float, region: list[int]
) -> Match | None:
    matches = game.find_any_size(
        name, PHONE_TEMPLATE_SIZES, threshold, game.region(region)
    )
    return matches[0] if matches else None


def battle_banner(game: Game) -> str | None:
    w, h = game.size

    def highlights(image: np.ndarray) -> np.ndarray:
        # Match the bright lettering rather than map details behind the text.
        return cv2.subtract(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), 140)

    region = highlights(game.screen[: h // 10, w // 3 : w - w // 3])
    best = None
    for name in (T.BB_BATTLE_STARTS, T.BB_BATTLE_ENDS):
        _, template = game.vision.library.get(name)[0]
        for size in BANNER_SIZES:
            scaled = cv2.resize(
                template,
                None,
                fx=size,
                fy=size,
                interpolation=cv2.INTER_AREA if size < 1 else cv2.INTER_LINEAR,
            )
            hits = match_template(region, highlights(scaled), 0.65, name=name)
            if hits and (best is None or hits[0].score > best.score):
                best = hits[0]
    return best.name if best else None


class StageTransition:
    def __init__(self, initial_banner: str):
        self.saw_combat = initial_banner == T.BB_BATTLE_ENDS
        self.pending = False

    def observe(self, game: Game) -> None:
        self.note(battle_banner(game))

    def note(self, banner: str | None) -> None:
        if banner == T.BB_BATTLE_ENDS:
            self.saw_combat = True
        elif banner == T.BB_BATTLE_STARTS and self.saw_combat:
            self.pending = True

    def started(self, game: Game) -> bool:
        self.observe(game)
        if not self.pending:
            return False
        self.saw_combat = self.pending = False
        return True


def find_now(game: Game) -> bool:
    w, h = game.size
    match = phone_match(game, T.BB_FIND_NOW, 0.80, [w // 5, h // 3, w * 3 // 5, 0])
    if not match:
        return False
    x, y = match.x - match.w // 2, match.y - match.h // 2
    crop = game.screen[y : y + match.h, x : x + match.w]
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    green = cv2.inRange(hsv, (30, 65, 100), (70, 255, 255))
    if cv2.countNonZero(green) < 0.18 * green.size:
        return False
    game.tap_match(match)
    return True


def return_button(game: Game) -> Match | None:
    w, h = game.size
    return phone_match(
        game, T.BB_RETURN_HOME, 0.80, [w // 3, h * 2 // 3, w - 2 * (w // 3), 0]
    )


def deploy_stage(
    game: Game,
    cfg: BuilderConfig,
    first: bool = False,
    *,
    stage: StageTransition,
) -> tuple[float, list[builder.Slot]]:
    screen = game.refresh()
    if (banner := battle_banner(game)) is None:
        raise builder.BuilderError("Builder Battle wasn't recognized; not deploying.")
    stage.note(banner)
    troops = builder.ready_troops(screen, cfg)
    if not troops:
        raise builder.BuilderError(
            "No ready Builder troop cards matched. Check the card bar alignment."
        )
    cfg = replace(cfg, use_hero=builder.current_hero(game, screen, cfg) is not None)
    w, h = game.size
    # Start on the left strip, outside the zoomed-in base. Nudge right on retries.
    drop = ((w * 19 // 100, h * 49 // 100), (w * 29 // 100, h * 49 // 100))
    return builder.deploy_stage(game, cfg, first, drop=drop, observe=stage.observe)


def run_battle(game: Game, cfg: BuilderConfig) -> None:
    game.refresh()
    game.tap_match(require_base(game))
    deadline = game.clock() + 8
    while True:
        game.refresh()
        if find_now(game):
            break
        if game.clock() >= deadline:
            raise builder.BuilderError(
                "Builder Find Now wasn't visible. No popup was dismissed."
            )
        game.wait(0.5)
    deadline = game.clock() + builder.SEARCH_FOR
    while game.clock() < deadline:
        game.refresh()
        if (banner := battle_banner(game)) is not None:
            stage = StageTransition(banner)
            game.wait(1)  # the battle countdown can appear before the troop bar
            builder.fight(
                game,
                cfg,
                deploy=partial(deploy_stage, stage=stage),
                stage_started=stage.started,
                return_button=return_button,
            )
            game.refresh()
            require_base(game)
            return
        game.wait(1)
    raise builder.BuilderError(
        "No Builder Battle appeared in time. Take over in the game."
    )


def cart_title(game: Game) -> Match | None:
    w, h = game.size
    return game.find(
        T.BB_CART_TITLE, region=game.region([w // 4, h // 20, w // 2, h * 3 // 20])
    )


def active_base_button(game: Game) -> Match | None:
    # A dimmed Battle button still correlates well with its template behind dialogs.
    base = game.find(T.BB_BATTLE, region=game.region(builder.BATTLE_AREA))
    if base is None:
        return None
    x, y = base.x - base.w // 2, base.y - base.h // 2
    hsv = cv2.cvtColor(game.screen[y : y + base.h, x : x + base.w], cv2.COLOR_BGR2HSV)
    yellow = cv2.inRange(hsv, (18, 125, 215), (40, 255, 255))
    return base if cv2.countNonZero(yellow) >= 0.18 * yellow.size else None


def cart_world(game: Game) -> Match | None:
    w, h = game.size
    region = game.region([w // 6, 0, w - 2 * (w // 6), h * 2 // 3])
    matches = game.find_any_size(T.BB_CART_WORLD, CART_SIZES, 0.85, region)
    return matches[0] if matches else None


def cart_collect_button(game: Game) -> Match | None:
    w, h = game.size
    button = phone_match(
        game, T.BB_CART_COLLECT, 0.80, [w // 2, h * 3 // 4, w * 3 // 10, 0]
    )
    return button if button and builder._is_green(game.screen, button) else None


def collect_cart(game: Game, check: bool) -> None:
    for i in range(len(CART_PANS) + 1):
        if cart_title(game):
            if check:
                log.info("Elixir Cart recognized. No game input sent.")
                return
            break
        require_base(game)
        cart = cart_world(game)
        if check:
            log.info(
                "%s No game input sent.",
                "Shoreline cart recognized."
                if cart
                else "Builder Base recognized. Cart off-screen.",
            )
            return
        if cart:
            game.tap(cart.x, cart.y)
            break
        if i == len(CART_PANS):
            raise builder.BuilderError(
                "Cart not found. Center the first Builder Base area."
            )
        w, h = game.size
        x1, y1, x2, y2 = CART_PANS[i]
        game.swipe(w * x1 // 100, h * y1 // 100, w * x2 // 100, h * y2 // 100, 520)
        game.wait(0.45)
        game.refresh()

    deadline = game.clock() + 3.5
    while True:
        game.refresh()
        title = cart_title(game)
        button = cart_collect_button(game) if title else None
        if button:
            game.tap_match(button)
            break
        if game.clock() >= deadline:
            if title:
                log.info("No active Collect button. Check cart and storage.")
                return
            raise builder.BuilderError(
                "Elixir Cart didn't open. Nothing was collected."
            )
        game.wait(0.35)
    game.wait(2.7)
    game.refresh()
    if not cart_title(game):
        log.info("Collect tapped. Cart could not be rechecked.")
    elif cart_collect_button(game):
        log.info("Collect tapped. Rewards remain; check storage.")
    else:
        log.info("Collect tapped. The cart button is now inactive.")


def run(game: Game, args: argparse.Namespace) -> None:
    game.refresh()
    if not game.ui_scale:
        if args.cart_only and not cart_title(game):
            game.recalibrate(T.BB_CART_TITLE)
        if not args.cart_only or not cart_title(game):
            game.recalibrate(T.BB_BATTLE)
    if args.cart_only:
        collect_cart(game, args.check)
        return
    require_base(game)
    cfg = battle_config(game, args.card_offset_x)
    log.info(
        "Recognized Builder Base (%d×%d, UI scale %.2f).",
        *game.size,
        game.ui_scale or 1.0,
    )
    if args.check:
        return
    for battle in range(1, args.battles + 1):
        log.info("Starting Builder Battle %d/%d.", battle, args.battles)
        run_battle(game, cfg)


def bounded_int(minimum: int, maximum: int) -> Callable[[str], int]:
    def parse(value: str) -> int:
        number = int(value)
        if not minimum <= number <= maximum:
            raise argparse.ArgumentTypeError(f"must be between {minimum} and {maximum}")
        return number

    return parse


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--serial",
        help="ADB serial if more than one USB device is connected",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--battles",
        type=bounded_int(1, 20),
        default=1,
        help="Builder Battles to play (default: 1)",
    )
    mode.add_argument(
        "--cart-only",
        action="store_true",
        help="find and collect from the Builder Base Elixir Cart",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify the connection and starting screen without sending input",
    )
    parser.add_argument(
        "--card-offset-x",
        type=int,
        default=0,
        help="horizontal troop bar adjustment in normalized pixels",
    )
    args = parser.parse_args(argv)

    logging.basicConfig(format="%(message)s", level=logging.INFO)
    try:
        if sys.platform == "android" or Path("/system/bin/getprop").is_file():
            raise DeviceError("Run this USB runner on the computer, not on the phone.")
        adb = open_usb_marble(args.serial)
        library = TemplateLibrary(Path(__file__).resolve().parents[1] / "templates")
        game = Game(MarbleScreen(adb), Vision(library))
        run(game, args)
    except (
        DeviceError,
        GameNotInFront,
        builder.BuilderError,
        OSError,
    ) as exc:
        log.error("Stopped: %s", exc)
        return 1
    except KeyboardInterrupt:
        log.info("Stopped by user.")
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
