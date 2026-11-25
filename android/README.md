# Marble Runner

A standalone Builder Base runner for a physical `marble` phone running
Android 13 or newer with KernelSU. The APK bundles its screen recognition and
templates. It uses on-device `su` for screenshots, taps and map swipes. It
does not need an ADB connection, an emulator, or a computer after installation.

**Supercell can ban accounts for automation.** This is an experimental build.
The app completed both stages and returned to Builder Base on a physical `marble`
in a live test. Still watch the first battle on your setup: screen recognition
and deployment can vary with the layout.

## Install and check

1. Copy `app-debug.apk` to the phone and install it. If Android asks, allow
   installs from the app you use to open the APK.
2. In KernelSU Manager, grant **Marble Runner** (`org.cocfarm.marble`)
   superuser access. Open Marble Runner and tap **Check KernelSU**.
3. Use **Manage overlay permission** to allow Marble Runner to display over
   other apps. Return to the app and tap **Show field controls**. You can also
   allow notifications for a Pause and Stop shortcut.
4. Open Clash of Clans in English and full-screen landscape. Sail to Builder
   Base and dismiss any dialogs yourself. Drag the overlay by its status if
   it covers a button or the battle countdown.
5. Tap the status to expand the overlay, then tap **Check**. This reads the
   screen without sending game input. If it recognizes Builder Base, tap the
   status again, tap **Start**, and watch the first battle. Check does not
   validate troop placement.

## Field controls

Setup saves changes automatically. Your selections apply the next time you tap
**Start** or **Collect**, even after you close and reopen the app.

- **Builder Battles**: start matchmaking, deploy recognized troops and
  optionally the hero, and return to Builder Base after each result. Set a
  battle limit of 1–20. Stage 2 is optional and only occurs if offered by the
  game. If the attack menu shows a cooldown, the app waits for **Find Now!**
  for up to two minutes without tapping the timer.
- **Hero**: deploys behind the first troop at the same entry point, or alone
  if no other troop is available. If the hero card is missed during deployment,
  the app can still try it during combat. It taps the ability when all three
  charge bars are visibly full, then waits for a new charge cycle.
- **Auto cart**: check the cart every 1, 3, 5 or 10 completed battles. The app
  collects available defense elixir, closes the cart even if it was empty, and
  confirms Builder Base before starting the next battle.
- **Stop: either ≈95%** (default): stop when either Builder Gold or Builder
  Elixir is about 95% full. Setup also offers both at ≈95%, both at ≈90%, or a
  fixed battle limit. The app reads both HUD bars before each new battle,
  including after a scheduled cart visit. If auto cart was off, selecting a
  storage goal sets it to every three battles. The goal replaces the usual
  limit with a 100-battle safety cap. Plug in the phone for longer runs.
  **Check** reads the bars without tapping. If either bar is unreadable, the
  app stops instead of guessing.
- **Plan**: **Focus** sends the army through one entry point. **Split**
  alternates between two points on the left side, falling back to the first
  if the second is blocked. **A/B** alternates in balanced pairs (Focus, Split,
  Split, Focus, then repeat). Use a fixed battle limit to guarantee equal
  samples; the storage goal can stop midway through a pair. Compare average
  Builder Gold and total damage per battle. Unreadable results are excluded from each average;
  `[gold/damage]` shows the number of readings used when some were missing.
  Different opponents make a single pair noisy; keep the army and other
  settings unchanged and try several pairs before choosing.
- **P.E.K.K.A**: **game** leaves Overcharge alone. **low HP** lets the app
  tap Overcharge once per confirmed, deployed Power P.E.K.K.A per stage, when
  its visible health bar drops to roughly one-third. **nearby** is experimental:
  it reads the troop's numbered marker and looks for two distinct buildings,
  or a building with a wall in the way, on two consecutive frames. It uses the
  low-HP rule instead if it can't read the marker. Reach and target recognition
  are estimates, and the game's own automatic behavior is unchanged.
- **Elixir Cart**: from Builder Base, switch modes under **Setup**, then tap
  **Collect**. The runner looks along the shoreline, opens the cart, and taps
  Collect once, only if it recognizes the cart title and a green button. It
  leaves the cart open so you can see what's left. If it can't find the cart,
  center the first Builder Base area and retry. **Check** never moves the map
  or taps the cart.
- **Compact overlay**: the single-line status defaults to compact. Tap it to
  show Start, Check, Setup, or Stop. Tap the game outside the overlay to
  collapse it without interrupting the run. **Pause / Resume** stays available
  on the compact bar while a run is active.
- **Pause / Resume / Stop**: pause prevents any new inputs. An input already
  sent cannot be taken back, and the battle timer keeps running in the game.
  **Stop** ends the run without leaving the battle or closing the game.
  **×** also closes the overlay. Dragging the panel pauses an active run.
- **Troop bar**: if a battle stops because it can't find ready cards, this
  setting shifts the expected troop positions left or right, in normalized
  pixels. It's best to leave the default for the first run.

The app does not start on boot or restart a failed run. It stops on a locked
screen, lost game focus, or an unrecognized screen that blocks progress. It
doesn't switch villages, touch upgrades or the shop, or dismiss unrelated
dialogs. During auto-cart battle runs, the overlay moves to the upper left
when you start, to leave the storage bars and cart close button visible.

## Build from source

With JDK 17 or later, Gradle 9.9, and Android SDK 36 installed, run from the
repository root:

```sh
gradle -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The signed debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
OpenCV and its license notice are bundled in the APK; screenshots are processed
in memory. A/B result reading uses the bundled on-device ML Kit text recognizer.

## Run from a computer over USB

The USB runner does the image processing on your computer. Your phone still
runs the game and sends screenshots over USB. Actual battery savings have
not been measured. You don't need the APK or root for this mode.
Auto cart between battles, the storage goal, Plan, A/B, and Overcharge are
only in the Android app.

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
