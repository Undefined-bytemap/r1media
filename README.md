# R1 Media

A small, standalone Android app that lets an **Even Realities R1 ring** act as a
media remote for your phone. It doesn't need the G1/G2 glasses or any other app.

> ## ⚠️ Read this first: no warranty, use at your own risk
>
> This is a **thrown-together, "vibe coded" hobby app**. It was written quickly
> with AI help, has had very little testing, and is **not** a finished or
> supported product. It is not affiliated with, endorsed by, or supported by
> Even Realities.
>
> - It **does not modify the ring's firmware** or the firmware of any other
>   device. It only talks to the ring over ordinary Bluetooth Low Energy, using
>   messages worked out by observing how the ring behaves. Even so, talking to
>   hardware in ways its maker didn't intend can cause unexpected behaviour,
>   such as dropped connections, battery drain, or the ring needing to be
>   re-paired with its official app. **Use it with caution.**
> - The software is provided **"as is", with no guarantees whatsoever**, express
>   or implied. That includes, but isn't limited to, any guarantee that it
>   works, is fit for any purpose, is safe, or won't damage your devices or data.
> - **By building, installing, or using this app you assume all risks.** The
>   authors and contributors are not liable for any damage, loss, or other
>   problem that results from using it.
>
> The full terms are in the [MIT License](LICENSE). If you aren't comfortable
> with them, please don't use this app.

## What it does

The app keeps a Bluetooth connection open to the ring and turns ring gestures
into standard Android media-button presses. These work with whichever music,
podcast or video app is currently playing.

| Gesture on the ring | Action |
|---|---|
| **Hold** | Arms media control for a short window (2 s by default, adjustable from 1 to 30 s) |
| **Tap** (while armed) | Play / pause |
| **Swipe up** (while armed) | Next track |
| **Swipe down** (while armed) | Previous track |

Each armed action restarts the window, so you can skip several tracks in a row.
Once the window runs out, gestures do nothing, so everyday taps on the ring don't
control your music by accident.

It also shows the ring's connection status, battery level, charging state and
whether it's being worn.

## How it works

- `RingService.kt` is a foreground service that runs in its own process. It
  scans for a device whose name starts with `EVEN R1`, connects to it, and
  enables touch reports. It sends a heartbeat to keep the link alive, and
  re-scans and reconnects on its own if the connection drops.
- `MainActivity.kt` shows status, asks for Bluetooth and notification
  permissions, and offers to exempt the app from battery optimisation so
  Android doesn't kill the service.
- `BootReceiver.kt` can restart the service after a reboot, if auto-start is on.
- `Prefs.kt` stores the arm-window length and the auto-start setting.

## Building

You need the Android SDK and JDK 17 or newer.

```bash
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`. Install it with
`adb install` or by copying it to your phone.

Requires Android 8.0 (API 26) or newer.

## Notes and limitations

- Because it connects to the ring directly, it may conflict with the official
  Even app or with Faceclaw if they're also connected to the ring. Run only one
  of them at a time.
- Swipe directions and gesture types reflect what was observed on one ring and
  may differ on other firmware versions.
- It's a debug-quality build with no crash reporting, no localisation and very
  little error handling.

## What's not included

This repository contains **only original source code** for this app, plus the
standard open-source Gradle wrapper. It does **not** include or distribute any
Even Realities firmware, firmware images, SDKs, apps, logos, or other
proprietary files. "Even Realities" and "R1" are used only to say which device
the app works with; those names belong to their owners.

## License

Released under the [MIT License](LICENSE), which also says the software comes
with no warranty of any kind. The licence covers only this app's own code, not
the ring, its firmware, or anything else owned by Even Realities.
