# Wi-Fi Keep Online

A resident background watcher for cheap MTK Chinese head units on Android 8.0 (API 26).
It watches for the *absence* of a connection and kicks the Wi-Fi radio back on, then
gets out of the way.

Register it in the radio's own **boot item / autostart** list.

## The one rule that overrides everything

**If the unit is running its own hotspot, do nothing.** A softAP on these units means
wireless CarPlay is holding the radio to talk to a phone, and the chipset cannot do STA
and AP at the same time — switching client mode on would tear CarPlay's hotspot down.

There is a trap here: while the AP is up, `getWifiState()` reports **DISABLED**. So the
naive "Wi-Fi is off, I'll turn it on" read is exactly backwards at that moment. The AP
check therefore runs *first* on every pass, and if the AP appears after we enabled the
radio, we switch it back off and hand the chip back.

`NetState.isApActive()` reads `WifiManager.getWifiApState()` by reflection — it's
`@hide`, but this targets Oreo, where hidden-API reflection still works (the greylist
clampdown landed in Android 9). If that fails it falls back to looking for a live
softAP interface (`ap0`, `swlan0`, `wlan1`), which no ROM can hide.

## What each pass does

`WifiWatchService.evaluateNow()`, in order:

1. **AP up?** → stand down, revert our own radio change if we made one, re-check in 15s.
2. **Online already** (Wi-Fi, built-in modem, dongle — anything)? → idle, re-check in 20s.
3. **AP just ended?** → 15s cooldown so we don't race its teardown.
4. **Offline, radio already on?** → the framework is scanning and will auto-join a saved
   network by itself. Give association 30s before calling it a miss.
5. **Offline, radio off?** → `setWifiEnabled(true)`. That is the whole kick.

Failed passes back off: 20s → 1 min → 5 min.

### Why there is no scanning or network picking

An app cannot join a secured AP it has no password for. What it *can* do is let the
framework auto-join the best saved network once the radio is up — so "enable Wi-Fi and
wait" **is** the connect step. This also dodges a real trap: `getScanResults()` on 8.0
needs location permission *and* the location toggle switched on, which is usually off on
a head unit, so a scan-based approach would silently return an empty list forever.

If nothing is in range, the radio is deliberately left **on** so the framework keeps
retrying on its own as you drive back into coverage.

## Staying alive

These ROMs ship "one-key clean" process killers, so the foreground service alone is not
enough:

- **Foreground service** — immune to Oreo's background execution limits. Costs one
  permanent notification, on an `IMPORTANCE_MIN` channel so it stays silent and collapsed
  at the bottom of the shade.
- **`START_STICKY`** — the system restarts it.
- **`AlarmManager` watchdog** (`Watchdog.kt`) — an inexact repeating alarm every 15 min
  pokes the service back up. Starting an already-running service is a no-op, so this is
  free when nothing is wrong.
- **`onTaskRemoved`** → one-shot poke 3s later, for launchers that kill on clear-recents.
- **`MY_PACKAGE_REPLACED`** → restart after reinstall.
- **Battery-optimisation whitelist** — requested once, the first time you tap the icon.
- **`android:persistent="true"`** — ignored for a normal app, but it takes effect if you
  ever move this to `/system/priv-app`, which is the only way to be genuinely unkillable.

## Tapping the icon

Opens the system Wi-Fi settings page (with fallbacks to the
`com.android.settings/.wifi.WifiSettings` component, then the top-level settings list)
and makes sure the service is running.

The boot path deliberately does **not** open settings — throwing the Wi-Fi page over the
radio's UI at every startup is the opposite of staying out of the way.

## Build & install

    ./gradlew assembleDebug
    adb install -r wifi-settings-opener.apk

## Icon

Generated from `~/Downloads/imageedit_1_2351653096.png` into legacy square PNGs at all
five densities, a round variant (artwork at 78% — the droid bleeds into the frame corner
and gets its leg clipped by the circle above ~80%), and an adaptive icon whose flat
`#1868E1` background is sampled from the artwork.

## Not verified

Built and manifest-checked only. No device was attached, so nothing here has been run on
hardware — in particular the reflection path in `isApActive()` and the softAP interface
names are the two things most likely to need adjusting on your specific ROM.
