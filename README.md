# Jonnyskeys

Plug a USB keyboard into your Android phone and use it to play touch-only
games — built for **Geometry Dash**, where one key is all you need.

Android already recognizes USB keyboards (via USB-OTG), but Geometry Dash
ignores key presses because it only listens for touches. Jonnyskeys bridges
that gap with an **accessibility service** (the same no-root technique used by
commercial key-mapper apps):

- **Key down** → a touch-down is injected at a configurable screen position
- **Key held** → the touch stays held (so ship / wave / hold mechanics work)
- **Key up** → touch-up

The mapped key defaults to **Space**; tap "Mapped key" in the app and press
any key to remap it.

## Building

Requires Android Studio (or the Android SDK + JDK 17):

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Install it with
`adb install` or by copying it to the phone.

## Setup on the phone

1. Install the APK and open **Jonnyskeys**.
2. Tap **Open Accessibility settings** → find **Jonnyskeys key mapper** →
   turn it on (Android will warn that the service can perform gestures —
   that's exactly what it does, and all it does).
3. Plug the USB keyboard into the phone (USB-C keyboards plug straight in;
   USB-A keyboards need a cheap OTG adapter).
4. Open Geometry Dash and press **Space** — the cube jumps. Hold it to fly.

## Settings

- **Mapping active** — master on/off switch without disabling the service.
- **Mapped key** — press the button, then press any keyboard key.
- **Tap position X/Y** — where on screen the touch lands, as a percentage of
  the screen. Any position works for Geometry Dash; the default (50%, 70%)
  stays clear of the pause button.

## Notes

- The mapped key is consumed system-wide while the service is on, so flip
  **Mapping active** off if you need that key for typing.
- Minimum Android version: 8.0 (API 26).
- The service declares `canPerformGestures` and key-event filtering only —
  it does not read screen content.
