# Running on other Chinese head units

What has to be true for this to work on a unit other than the AC8257 it was built
against, and which of the usual advice turns out to be wrong.

## These units sleep; they almost never boot

The thing that breaks a naive port is that a head unit does not reboot. It *sleeps* on
ACC-off and wakes on ACC-on, many times a day, and on sleep the ROM kills background
apps outright. On the AC8257 the killer is a system app: `com.autochips.quickbootmanager`,
which owns two broadcasts it fires around the event —

| action | meaning |
|---|---|
| `autochips.intent.action.KILL_APPS` | the ROM is about to kill things |
| `autochips.intent.action.RESUME_APPS` | apps are being resumed after wake |
| `autochips.intent.action.QB_POWERON` / `QB_POWEROFF` | wake / sleep |
| `autochips.intent.action.HOME_READY` | launcher up |

`BOOT_COMPLETED` covers the rarest case and misses the common one entirely.

Other families use their own names. FYT: `com.fyt.boot.ACCON` / `ACCOFF`. The PX3/Cayboy
lineage: `com.cayboy.action.ACC_ON` / `ACC_OFF`. MediaTek generally:
`android.intent.action.QUICKBOOT_POWERON`.

## The advice that does not work

Every forum answer says to register those wake broadcasts in your manifest. **Measured on
the target unit, that cannot work for an ordinary app.**

Sending `autochips.intent.action.RESUME_APPS` with `am broadcast`, the manifest receiver
did not fire and the process did not start. Repeated with the app already *alive* — still
nothing. That is Android O's implicit-broadcast restriction, which applies from
`targetSdk` 26.

`com.autochips.carplayapp` does receive `HOME_READY` from its manifest, which is what
makes the forum advice look correct. It lives in `/vendor/app/ivi-CarPlay/` — it is a
**system app**, and that is the entire difference.

So the vendor actions are registered **dynamically**, in `WifiWatchService`, where the
restriction does not apply. They help on every unit whose names we know, but only while
the service is still alive — which is exactly the case they are good for: the unit dims
and comes back without the ROM having reaped us.

## What actually survives a kill

One mechanism, and it is vendor-agnostic: **the watchdog alarm**.

Its `PendingIntent` targets our own `BootReceiver` explicitly, and an explicit broadcast
is never subject to the implicit restriction. Verified directly: the same broadcast that
was ignored as an implicit action started the app immediately when sent to the component.

It is an exact, self-rescheduling dead-man's switch rather than a repeating alarm:

- `setInexactRepeating` at 15 minutes was batched into an 11-minute window on this unit —
  `maxWhenElapsed=+25m18s`. Twenty-five minutes of driving with no internet.
- `setExactAndAllowWhileIdle` at 5 minutes, re-armed on every evaluation pass, gives
  `window=0`. While the service lives the alarm never fires; when it dies, it fires
  within five minutes.

`AllowWhileIdle` matters because the alarm's whole job is to run when we are dead.

## Porting checklist

1. **Read the ROM's own broadcasts** rather than guessing:
   `adb shell dumpsys package | grep -oE "^ +[a-z][a-zA-Z0-9_.]*\.action\.[A-Z_]+:"`,
   then add the wake-ish ones to `Wake.ACTIONS`. They cost nothing when absent.
2. **Re-measure association time.** `ASSOCIATE_MS` is 120s because this unit took 34–69s
   with no watchable progress signal. A slower radio needs more.
3. **Check `isApActive()` picks the right path.** It leads with `isWifiApEnabled()` and
   falls back to `getWifiApState()` then to softAP interface names (`ap0`, `swlan0`,
   `wlan1`). Only the first is proven, and only here.
4. **Confirm the Wi-Fi menu resolves.** `Settings.ACTION_WIFI_SETTINGS` is the documented
   default and should exist everywhere, but the component fallback
   `com.android.settings/.wifi.WifiSettings` is a guess that varies by vendor.
5. **Check `oom_score_adj`.** 200 is the ceiling for a normal install. Below that means
   `/system/priv-app` plus a platform signature.

## Android 10 and 11

Untested. `setWifiEnabled()` is a no-op for third-party apps from API 29, so the app
cannot switch the radio on and falls back to putting the Wi-Fi panel in front of the
user. The `SYSTEM_ALERT_WINDOW` exemption that lets the service start activities from the
background is likewise written from documentation, not from a device.

## Sources

Forum background on head-unit sleep behaviour and vendor intents:

- [Start app/service on wake without root](https://xdaforums.com/t/start-app-service-on-wake-without-root.3803636/)
- [\[Solution\] Start Tasker after head unit sleep](https://xdaforums.com/t/solution-start-tasker-after-head-unit-sleep.4102439/)
- [Help! Background services getting killed](https://xdaforums.com/t/help-background-services-getting-killed.3878190/)
- [MtcdTools](https://github.com/f1xpl/MtcdTools) — Microntek/MTCD service autostart
- [FYT Android Head Units forum](https://xdaforums.com/f/fyt-android-head-units.12445/)

Everything in the sections above was measured on the unit; the forums supplied the names
to go looking for, not the conclusions.
