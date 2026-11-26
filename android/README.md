# COCFARM for Android

A standalone Builder Base runner for a physical `marble` phone running
Android 13 or newer with KernelSU. The APK bundles its screen recognition and
templates. It uses on-device `su` for screenshots, taps and map swipes. It
does not need an ADB connection, an emulator, or a computer after installation.

**Supercell can ban accounts for automation.** This is an experimental build.
The app completed both stages and returned to Builder Base on a physical `marble`
in a live test. Still watch the first battle on your setup: screen recognition
and deployment can vary with the layout.

## Install and check

1. Copy the signed COCFARM APK to the phone and install it. If Android asks, allow
   installs from the app you use to open the APK.
2. In KernelSU Manager, grant **COCFARM** (`org.cocfarm.marble`)
   superuser access. Open COCFARM and tap **Check KernelSU**.
3. Use **Manage overlay permission** to allow COCFARM to display over
   other apps. Return to the app and tap **Show field controls**. You can also
   allow notifications for a Pause and Stop shortcut.
4. Open Clash of Clans in English and full-screen landscape. Sail to Builder
   Base and dismiss any dialogs yourself. Drag the overlay by its status if
   it covers a button or the battle countdown.
5. Tap the status to expand the overlay, then tap **Check**. This reads the
   screen without sending game input. If it recognizes Builder Base, tap
   **Start** on the compact bar and watch the first battle. Check does not
   validate troop placement.

## Field controls

Tap the status to open all settings directly. Changes save automatically,
including across app restarts. Opening the panel during a run pauses the
runner's inputs, not the in-game timer. Tap outside to collapse and continue.
Battle settings take effect at the next battle boundary; switching between
Builder Battles and Elixir Cart takes effect on the next **Start**.

- **Builder Battles**: start matchmaking from Builder Base, or tap **Start**
  during an active Builder Battle to take over. The runner reads the battle
  again and deploys only cards it recognizes as ready. If Stage 2 is off and
  the current stage can't be identified, it watches instead of deploying.
  It returns to Builder Base after each result. Set a battle limit of 1–20.
  Stage 2 is optional and only occurs if offered by the game. If the attack
  menu shows a cooldown, the app waits for **Find Now!** for up to two
  minutes without tapping the timer. Once deployment is done, it taps the
  game's green **1×** button to enable **4×** when the game offers it.
- **Hero**: deploys behind the first troop at the same entry point, or alone
  if no other troop is available. If the hero card is missed during deployment,
  the app can still try it during combat. It taps the ability when all three
  charge bars are visibly full, then waits for a new charge cycle.
- **Auto cart**: check the cart every 1, 3, 5 or 10 completed battles. The app
  collects available defense elixir, closes the cart even if it was empty, and
  confirms Builder Base before starting the next battle.
- **Stop: either ≈95%** (default): stop when either Builder Gold or Builder
  Elixir is about 95% full. Settings also offer both at ≈95%, both at ≈90%, or a
  fixed battle limit. The app reads both HUD bars before each new battle,
  including after a scheduled cart visit. If auto cart was off, selecting a
  storage goal sets it to every three battles. The goal replaces the usual
  limit with a 100-battle safety cap. Plug in the phone for longer runs.
  **Check** reads the bars without tapping. If either bar is unreadable, the
  app stops instead of guessing.
- **Plan**: **Focus** sends the army through one entry point. **Split**
  alternates between two points on the left side, falling back to the first
  if the second is blocked. **Compare** tries all four combinations of
  Focus/Split and Game/50% HP Overcharge. It reverses the order after
  each four-battle block and picks up where it left off after a stop or restart.
  A storage goal or battle limit can end a run midway through a block.
- **P.E.K.K.A**: **Game** leaves Overcharge to the game. **50% HP** taps
  Overcharge once per confirmed, deployed Power P.E.K.K.A per stage, as soon
  as a fresh combat frame shows its health at about half or less.
- **Elixir Cart**: from Builder Base, switch to Elixir Cart in the settings,
  then tap **Start**. The runner looks along the shoreline, opens the cart, and taps
  Collect once, only if it recognizes the cart title and a green button. It
  leaves the cart open so you can see what's left. If it can't find the cart,
  center the first Builder Base area and retry. **Check** never moves the map
  or taps the cart.
- **Compact overlay**: **Start / Stop / Resume** is independent of the
  single-line status. Tap the status to open settings or **Check**. Tap the
  game outside the controls to collapse them; that tap still reaches the game.
- **Pause / Resume / Stop**: opening notifications, leaving Clash, or locking
  the screen pauses the run when it loses access to the game. Return to Clash
  and tap **Resume** on the compact bar. It reads the current battle, result,
  or Builder Base before tapping again; it does not replay an old selection.
  To play manually without leaving Clash, use **Keep paused** before touching
  the game. An input already sent cannot be taken back, and the battle timer
  keeps running in the game.
  **Stop** ends the run without leaving the battle or closing the game.
  **×** also closes the overlay. Dragging the panel pauses an active run.
- **Run alerts**: Android can notify you when the goal is reached, the run
  pauses, or it stops and needs attention. Turn on notifications through
  **Manage run alerts** on the COCFARM setup screen.
- **Troop bar**: if a battle stops because it can't find ready cards, this
  setting shifts the expected troop positions left or right, in normalized
  pixels. It's best to leave the default for the first run.

### Battle history

Open **History** from the expanded controls. The app saves each recognized
result before tapping **Return Home**, including the active plan, damage,
Builder Gold, trophies, stage count, tracked time, and ability taps.
Unreadable results are marked `—`, not counted as zero. The log includes
regular runs and Compare runs. Joined or interrupted battles are marked
**Joined / resumed** and excluded from comparisons. Averages only use Compare
battles matching the current Hero, Stage 2, and Troop bar settings. Use the `n` beside each
average to see how many readings went into it. The older six-way rotation
stays in the log but does not count toward the current comparison. Keep the
army consistent, and run several blocks before drawing conclusions: the app
doesn't control which opponents you get. OCR can also misread digits. For
results that affect your conclusion, check the saved frames from a host run.

Android backup and device transfer can include the app's private settings,
battle history, and `files/window.xml` if you keep a UI dump there. COCFARM
doesn't create that dump or read `/sdcard/window.xml`. A UI dump can contain
text that was visible on the screen when it was captured.

The app does not start on boot or resume input automatically after losing
focus. A force-stop or process restart clears the active session; **Start**
can still take over a live battle. It doesn't switch villages, touch upgrades
or the shop, or dismiss unrelated dialogs. During auto-cart battle runs, the overlay moves to the upper left
when you start, to leave the storage bars and cart close button visible.

## Build from source

With JDK 17 or later, Gradle 9.9, and Android SDK 36 installed, run from the
repository root:

```sh
gradle -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The signed debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
For a non-debuggable build, run `gradle -p android :app:assembleRelease`.
Gradle writes an unsigned APK to `android/app/build/outputs/apk/release/`.
Use Android SDK `zipalign` before signing with `apksigner`. For an in-place
update, use the same signing key as the installed APK so Android can keep
settings and battle history. Don't uninstall the existing app to work around
a signature mismatch unless you intend to erase its data.

OpenCV and its license notice are bundled in the APK; screenshots are processed
in memory. Battle result reading uses the bundled on-device ML Kit text
recognizer. COCFARM stores history in app data and doesn't upload it itself.

## Run from a computer over USB

The USB runner does the image processing on your computer. Your phone still
runs the game and sends screenshots over USB. Actual battery savings have
not been measured. You don't need the APK or root for this mode.
This Python runner doesn't support scheduled cart visits, storage goals, or
Compare. Use the Android app or the Java host runner below for those.

1. Install Android Platform Tools (`adb`) and [uv](https://docs.astral.sh/uv/).
2. Enable **USB debugging** on the phone, connect it with a data cable, and
   accept the debugging prompt. `adb devices -l` should list it as `device`.
3. Leave Clash of Clans open in English and landscape on Builder Base. If the
   rooted app is installed, stop it and close its overlay.

Run from the repository root, first without any taps:

```sh
uv run --no-project --with opencv-python-headless python -m farmbot.marble_pc --check
uv run --no-project --with opencv-python-headless python -m farmbot.marble_pc --battles 1
```

Only an authorized USB connection to a physical `marble` is accepted. If more
than one USB device is connected, use `--serial SERIAL` with the value from
`adb devices -l`. To find and collect defense elixir instead, start on Builder
Base and use `--cart-only`. Press Ctrl+C to stop a run.

## Record and replay a test battle on a computer

`android/host/run` uses the APK's Java battle controller and OpenCV detectors.
The computer does the vision work; the phone only runs Clash, captures the
screen, and accepts standard Android input. This is separate from the USB
farming script above. Use JDK 17+, desktop OpenCV Java bindings and matching
native libraries, `adb`, and Tesseract with English data for results. If your
distribution installs OpenCV in different locations, set `OPENCV_JAR` and
`OPENCV_LIB` (the directory containing its native library). The Java bindings
must match the native library and be readable by your JDK.

Close the rooted runner before live testing; the host refuses to send input
while it is running. Leave the phone unlocked in Builder Base. `--check`
doesn't tap; `--live` defaults to one battle. On an already authorized
wireless connection, add `--wireless --hardware PHONE_SERIAL`, where
`PHONE_SERIAL` is the physical handset's `ro.serialno`.

```sh
./android/host/run --serial ADB_NAME --out .scratch/host-analytics/test-01 --live
./android/host/run --replay .scratch/host-analytics/test-01 --out .scratch/host-analytics/replay-01
```

Each capture is saved as a lossless PNG. `trace.jsonl` links it to controller
state, hero portrait scores, ready cards, inputs, and errors; `battles.jsonl`
holds recognized results. The trace records the Git revision, a fingerprint of
the compiled sources and templates, the OpenCV version, and capture timing.
To compare versions, replay the same frames with the same options and a fresh
output directory. Replay never sends input, cannot show how the game would
respond to a different tap, and does not reproduce interruptions between captures.
For those, use the live trace. `--plan COMPARE --battles 4` exercises the
four combinations in one live session. For a fixed plan, use `--overcharge
HALF_HP` to enable the health trigger.

To run Compare until either Builder Gold or Builder Elixir reaches about 95%,
replace `--battles 4` with `--either-95`. The host collects from the Elixir Cart
every three completed battles by default; change this with `--cart-every N`.
Use `--minutes N` to extend the default 15-minute limit for a live recording.
If you're continuing a comparison with the same hero, stage, and troop offset
settings, pass `--comparison-start N` with the number of completed, non-partial
results from the current four-way rotation. This picks up the rotation without
importing those results. Host results stay in `battles.jsonl`; they don't appear
in the Android app's History.

To inspect an existing run without competing for input, use `--observe SECONDS`
and analyze it with `--replay ... --inspect --anchor BASE.png`, using a
same-resolution screenshot of Builder Base for calibration.

Recordings stay on the computer and can contain player names. Keep them out of
Git; expect several hundred megabytes per battle. USB sends raw pixels, while
wireless debugging sends lossless PNGs to reduce transfer time. USB is
preferable when timing-sensitive behavior needs to match an on-phone run.
