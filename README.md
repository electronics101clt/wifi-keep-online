# WiFi Bootstrap

WiFi Bootstrap is a resident background watcher for cheap MTK Chinese head units on Android 8.0 (API 26).
It watches for the *absence* of a connection and kicks the Wi-Fi radio back on, then
gets out of the way.

Register it in the radio's own **boot item / autostart** list.

## The cycle

```
             boot item launches the app
                        |
         +--------------+--------------+
         |  hotspot up?   -> yes: touch nothing, get off the screen
         |  wifi already connected? -> yes: dismiss, go to home screen
         +--------------+--------------+
                        | no to both
            service switches the radio on
        framework joins a saved profile if visible
                        |
            +-----------+-----------+
            | joined                | nothing in range after 30s,
            |                       | or setWifiEnabled() refused (10/11)
            |                       |
      verify the link:         OPEN THE MENU (once)
   associated + saved networkId       |
   + has IP + still there 4s          | user picks a network
            |                         |
            +-----------+-------------+
                        |
                  handoff -> ZLauncher (app.lawnchair)
                        |
        ZLauncher's KeepAliveService wires the tunnel
```

The menu is a **fallback, not the entry point**. The automatic path gets tried first,
because on 8.0 and 9 it usually just works: enable the radio and the framework joins a
saved profile on its own.

### Android 10 and 11

There the menu is not a fallback, it is the only path. `setWifiEnabled()` is a no-op for
third-party apps from API 29, so the app cannot switch the radio on at all and instead
puts the toggle in front of the user. API 29 added a slide-up Wi-Fi panel for exactly
this, which is much less intrusive than the full settings page, so it is preferred where
it exists.

## The one rule that overrides everything

**If the unit is running its own hotspot, do nothing.** A softAP on these units means
wireless CarPlay is holding the radio to talk to a phone, and the chipset cannot do STA
and AP at the same time — switching client mode on would tear CarPlay's hotspot down.

There is a trap here: while the AP is up, `getWifiState()` reports **DISABLED**. So the
naive "Wi-Fi is off, I'll turn it on" read is exactly backwards at that moment. The AP
check therefore runs *first* on every pass.

The hotspot path is **purely passive — it detects, and does nothing else.** The app has
no way to start or stop a hotspot: the only two AP calls in it are `isWifiApEnabled()`
and `getWifiApState()`, both getters. It does not even switch its own client radio back
off when a hotspot appears; that was a write to the radio at the exact moment a
projection session starts, which is the riskiest possible instant to touch it, and the
framework tears STA down by itself anyway.

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

It deliberately does **not** consult `NET_CAPABILITY_VALIDATED`. ZLauncher strips
`INTERNET` and `VALIDATED` out of its own `NetworkRequest` precisely because "Android's
own INTERNET/VALIDATED opinion is exactly what's unreliable against this network" — a
PdaNet hotspot routinely reads unvalidated while working perfectly. Waiting on that probe
would put a timeout in front of every handoff.

The honest signal is the **default-route gateway**. `NetState.isPdaNetLink()` checks it
against `192.168.49.1`, the same value ZLauncher's `checkPdaNetGateway()` tests before
calling `Tun2HttpVpnService.start()`. So a match means the handoff is landing on a
launcher that has work to do — and it costs no permission beyond `ACCESS_WIFI_STATE`,
unlike reading the SSID.

If the unit is online through the modem rather than Wi-Fi, nothing is handed off, because
nothing was handed to us.

It fires **once**, and only on a transition it actually watched happen:

- `sawOffline` — if the service starts while the unit is already online (a watchdog poke
  an hour into a drive), there was no handoff to make, and pressing HOME would just yank
  the driver out of whatever they were using.
- `homeSent` — a mid-drive reconnect is not an "initial screen", so it does not re-fire.

## Other head units

These radios sleep on ACC-off far more often than they boot, and the ROM kills background
apps when they do. The forum answer — register the vendor wake broadcast in your manifest
— provably does not work for a normal app on this target. See
[docs/CROSS-UNIT.md](docs/CROSS-UNIT.md) for what does, and for a porting checklist.

## Staying alive

These ROMs ship "one-key clean" process killers, so the foreground service alone is not
enough:

- **Foreground service** — immune to Oreo's background execution limits. Costs one
  permanent notification, on an `IMPORTANCE_MIN` channel so it stays silent and collapsed
  at the bottom of the shade.
- **`START_STICKY`** — the system restarts it.
- **`AlarmManager` watchdog** (`Watchdog.kt`) — an exact, self-rescheduling dead-man's
  switch. Re-armed every evaluation pass, so while the service lives it never fires; when
  the service dies it pokes it back up within 5 minutes. This is the *only* revival that
  works on an ordinary install, because its `PendingIntent` targets our own component and
  explicit broadcasts escape the O+ implicit restriction.
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

ZLauncher raises a tunnel on two signals: an SSID containing "pdanet" (which needs the
location permission it requests itself), and the `192.168.49.1` default-route gateway.
This app checks the gateway one, since it needs no location grant, and reports it in the
notification — "Connected to PdaNet" against a plain "Connected". So you can tell at a
glance whether the link is merely real or is the one ZLauncher will tunnel.

If this app gets the unit onto some other network, the handoff still happens and ZLauncher
still comes forward — it just won't raise a tunnel, which is correct behaviour, not a
failure.

## Build & install

    ./gradlew assembleRelease
    adb install -r wifi-bootstrap.apk

`wifi-bootstrap.apk` at the repo root is the signed release build (`CN=ZScreen`, SHA-256
`686f90d4…`), signed with **both v1 and v2** — v1 because these head-unit ROMs ship
modified package installers and some still want a JAR signature.

Note that `apksigner verify` reports `v1 scheme: false` on this APK. That is not a
missing signature: with `minSdk 26`, apksigner does not exercise v1 at all, because v2
arrived in API 24. The JAR signature is there — `META-INF/CERT.SF`, `CERT.RSA`,
`MANIFEST.MF` — and `apksigner verify --min-sdk-version 21` confirms it verifies.

Signing is configured through `keystore.properties` at the repo root, which is
**gitignored**, as is the keystore itself (which lives outside the tree entirely). A
fresh clone without it still builds; the release APK just comes out unsigned rather than
the build failing.

Installing over a debug build needs an uninstall first — different signature.

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
