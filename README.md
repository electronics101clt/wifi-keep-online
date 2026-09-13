# Wi-Fi Keep Online

A resident background watcher for cheap MTK Chinese head units on Android 8.0 (API 26).
It watches for the *absence* of a connection and kicks the Wi-Fi radio back on, then
gets out of the way.

Register it in the radio's own **boot item / autostart** list.

## The cycle

```
             boot item launches the app
                        |
         +--------------+--------------+
         |  hotspot up?  -> yes: dismiss, stay off the screen
         |  wifi already connected? -> yes: dismiss
         +--------------+--------------+
                        | no to both
                   ENTER DIALOG
              (system Wi-Fi settings page)
                        |
              service kicks the radio on
              framework auto-joins a saved network
                        |
                 verify the link:
        associated + saved networkId + has IP
        + still there 4s later + validated (20s grace)
                        |
                    LEAVE
          handoff -> ZLauncher (app.lawnchair)
                        |
        ZLauncher's KeepAliveService wires the tunnel
```

## The one rule that overrides everything

**If the unit is running its own hotspot, do nothing.** A softAP on these units means
wireless CarPlay is holding the radio to talk to a phone, and the chipset cannot do STA
and AP at the same time — switching client mode on would tear CarPlay's hotspot down.

There is a trap here: while the AP is up, `getWifiState()` reports **DISABLED**. So the
naive "Wi-Fi is off, I'll turn it on" read is exactly backwards at that moment. The AP
check therefore runs *first* on every pass, and if the AP appears after we enabled the
radio, we switch it back off and hand the chip back.

`NetState.isApActive()` leads with `WifiManager.isWifiApEnabled()` by reflection — the
same call ZLauncher's `KeepAliveService` uses to decide whether to tear its tunnel down,
so it is already proven on this hardware. It falls back to the `getWifiApState()` state
int, then to looking for a live softAP interface (`ap0`, `swlan0`, `wlan1`), which no ROM
can hide. All `@hide`, which is fine on Oreo — the greylist clampdown landed in Android 9.

Both apps agree on this rule, from opposite ends: ZLauncher tears the tunnel down when the
hotspot comes up, and this app refuses to touch the radio for the same reason.

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

## The handoff

When the first connection verifies, the service goes HOME so ZLauncher comes forward and
its `app.lawnchair.service.KeepAliveService` wires the tunnel. That also clears the Wi-Fi
settings page off the screen — the "initial screen" has its connection, so it gets out of
the way.

ZLauncher is set as the home app on the unit, so a plain `CATEGORY_HOME` reaches it. It is
still targeted **explicitly** first (`app.lawnchair`), with `CATEGORY_HOME` as the
fallback, because these ROMs have a habit of taking default-home back after an update and
the handoff has to reach the app that owns the tunnel either way.

It does **not** reach into `KeepAliveService` directly — that is another app's component
and not ours to start. Foregrounding the launcher is the whole handoff; it wires its own
tun from there.

Not an injected keypress: `KEYCODE_HOME` needs `INJECT_EVENTS` (signature-level) and
`input keyevent 3` needs root. Launching the home activity does the same job with no
permission.

**Android 10/11 caveat.** Background activity starts are blocked there, and — contrary to
a widespread belief — running a foreground service is *not* on the exemption list.
`SYSTEM_ALERT_WINDOW` is, so on those two versions the app asks once for "draw over other
apps". It draws nothing; the permission is only there to make the HOME start land. On 8.0
and 9 it is never requested and never needed.

Before it leaves, the link has to actually check out. `isOnline()` is too loose to hand
off on — it also goes true for the built-in modem or a USB dongle, and `NetworkInfo` can
read connected while DHCP is still in flight. So `NetState.isWifiConnected()` requires all
three of: supplicant state `COMPLETED`, a real saved `networkId`, and a non-zero IP. That
has to hold for two passes 4s apart, because a link that survives one poll is not worth
handing the screen over for. (It deliberately ignores the SSID — on 8.0 `getSSID()`
returns `<unknown ssid>` without location permission, which this app never asks for.)

On top of that it prefers the framework's own internet verdict — `NET_CAPABILITY_VALIDATED`,
which the system stamps after probing on connect, free to read and costing no traffic of
ours. But it is a preference with a 20s grace, never a requirement: a phone hotspot with
no cell data, or one the probe cannot reach, reads unvalidated while still being a
perfectly good link for the tun to sit on. Hard-gating on it would strand the handoff in
exactly the field case this app exists for.

If the unit is online through the modem rather than Wi-Fi, nothing is handed off, because
nothing was handed to us.

It fires **once**, and only on a transition it actually watched happen:

- `sawOffline` — if the service starts while the unit is already online (a watchdog poke
  an hour into a drive), there was no handoff to make, and pressing HOME would just yank
  the driver out of whatever they were using.
- `homeSent` — a mid-drive reconnect is not an "initial screen", so it does not re-fire.

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

Same entry test as the boot item — those lists launch the launcher activity, so it is
literally the same code path. If a hotspot is up or Wi-Fi is already connected, it
dismisses without taking the screen. Otherwise it opens the system Wi-Fi settings page
(falling back to the `com.android.settings/.wifi.WifiSettings` component, then the
top-level settings list).

The dismiss is **silent at boot** and shows a toast on a finger tap. A toast popping over
the radio's UI at every startup is exactly the noise this app exists to avoid, but on a
deliberate tap it is the difference between "already connected" and a dead icon. Both
arrive as the same MAIN/LAUNCHER intent, so time since boot (3 min) is the only thing
separating them — a wrong guess costs one stray toast or one missing one, which is why the
heuristic is allowed here and nowhere that matters.

Either way it makes sure the watcher is running.

## Division of labour with ZLauncher

ZLauncher deliberately makes **no** `WifiManager` writes at all — no `enableNetwork()`,
no toggling; association is entirely WifiManager's business as far as it is concerned. It
only watches the SSID and builds or destroys the tunnel off it.

This app is the other half: it does the radio writes ZLauncher won't, and stops at the
point ZLauncher takes over. Neither one touches the other's job.

One consequence worth knowing: ZLauncher builds a tunnel only when the connected SSID
contains "pdanet". If this app gets the unit onto some other network, the handoff still
happens and ZLauncher still comes forward — it just won't raise a tunnel, which is correct
behaviour, not a failure.

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
hardware. The three things most likely to need adjusting on a specific ROM:

- the reflection path in `isApActive()`,
- the softAP interface names it falls back to,
- whether the handoff lands on ZLauncher — `logcat` prints `handoff -> ZLauncher` or
  `handoff -> default home`, which tells you immediately.
