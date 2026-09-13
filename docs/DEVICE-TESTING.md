# What the hardware said

Everything here was measured on the target unit, not reasoned about. Where a number
contradicted something the code assumed, the code changed.

**Unit:** AC8257 / `UJC201_64`, build `UJC201-V1.0.66R3-231020_0857`.

## It is Android 9, not Android 8

`ro.build.version.release` says **12**. `ro.build.version.sdk` says **28**. The release
string is marketing; the SDK level is the truth, so this is Pie.

Android 8.0 stays the *base* — `minSdk`/`targetSdk` remain 26, and the app must keep
running on a genuine 8.0 unit. But one justification written into the code was wrong
because of this: the `@hide` reflection in `isApActive()` was excused as safe "because
Oreo, and the greylist clampdown landed in Android 9." This *is* Android 9.

**Tested anyway, and it works.** `logcat` shows no hidden-API warnings for the package at
all, because non-SDK restrictions in Pie go easy on apps whose `targetSdk` is below 28.
The reflection stands, but on its measured behaviour rather than on that reasoning.

## Association is slow and not reliably bounded

Time from `setWifiEnabled(true)` to a verified link, four runs:

| run | time |
|---|---|
| 1 | 34s |
| 2 | 49s |
| 3 | 57s (manual enable) |
| 4 | 69s |

There is **no progress signal to watch**. Sampling every 3s through a full cycle, the
unit reports `DISCONNECTED` for ~54s and then jumps straight to `CONNECTED`. Nothing
intermediate is ever visible, so `isAssociating()` cannot carry the wait on this ROM.

Two defects fell out of this:

- The window was 30s, so the menu went up **four seconds before the connection completed
  on its own**. Window is now 120s.
- The service *slept* the whole window instead of polling, so a link that landed at 34s
  went unnoticed until the window expired. It now polls every 5s.

## The bug that made it give up forever

With the radio on and nothing joining, the miss counter was gated on `lastKick != 0`—
but `lastKick` is zeroed on the first miss. So `strikes` could never reach the menu
threshold: the app sat with Wi-Fi on, offline, doing nothing, indefinitely. Found by
leaving a unit disconnected and watching it never recover. The counter now increments
every cycle.

## Log tag collided with the system

The tag was `WifiSettings`, which is also what `com.android.settings` logs under — its
lines appeared interleaved with ours and looked like our warnings. Renamed `ZWifiKeep`.

## Priority: already at the ceiling

`oom_score_adj` on this unit, lower survives longer:

| process | adj |
|---|---|
| `/init`, vendor HALs | -1000 |
| `systemui`, `com.jancar.*` services | -800 |
| `app.lawnchair` (visible launcher) | 0 |
| **this app** | **200** |
| **`com.autochips.carplayapp`** | **200** |
| `com.android.settings` | 700 |

200 is `PERCEPTIBLE_APP_ADJ`, what a foreground service with no visible UI gets — the
same tier as the vendor's own CarPlay app, with everything killable sitting at 300+.

**The battery whitelist does not change this.** Measured directly: whitelisted the
package with `dumpsys deviceidle whitelist +`, restarted, `adj` stayed 200. It protects
against Doze and app standby, not against the low-memory killer. Worth having, but not
for the reason it is usually cited.

Going below 200 requires being a persistent system app — `/system/priv-app` plus a
platform signature. That is the real ceiling for anything installed normally.

### A grant that was never asked for

The whitelist prompt sat *after* the already-connected early return in `MainActivity`, so
on a healthy unit — the normal case — it was never requested once. Confirmed on device:
whitelist empty. The grants now run before the early returns, on every path.

## Verified working end to end

- `handoff -> app.lawnchair` on link verification, resolved from whatever is set as home.
- `MY_PACKAGE_REPLACED` restarts the service after reinstall.
- Service runs `isForeground=true`.
- `link verified, pdanet=true` — the `192.168.49.1` gateway check fires correctly against
  a real PdaNet hotspot.
- After the fixes, a full disable/recover cycle completed with **no menu interruption**;
  the resumed activity stayed on the launcher throughout.
