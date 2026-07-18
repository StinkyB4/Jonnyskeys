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

## Publishing to Google Play

The repo is set up to produce a Play-ready signed app bundle (`.aab`,
targetSdk 35). What you need to do, in order:

1. **Create an upload keystore** (once, on your own computer — keep it and
   the passwords safe; losing it means you can't update the app):

   ```
   keytool -genkeypair -v -keystore upload.keystore -alias jonnyskeys \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. **Add the signing secrets** in GitHub → repo → Settings → Secrets and
   variables → Actions:

   - `ANDROID_KEYSTORE_BASE64` — output of `base64 -w0 upload.keystore`
     (macOS: `base64 -i upload.keystore`)
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS` — `jonnyskeys`
   - `ANDROID_KEY_PASSWORD`

   The next CI run's **play-bundle** job then produces the signed
   `jonnyskeys-play-bundle` artifact (`app-release.aab`).

3. **Create a Google Play developer account** at
   https://play.google.com/console ($25 one-time, identity verification
   required). Personal accounts must run a closed test with at least 12
   testers for 14 days before they can publish to production.

4. **Create the app** in Play Console, upload the `.aab`, and fill in the
   listing (screenshots, 512×512 icon, feature graphic, descriptions).

5. **Declarations Play will require:**
   - *Privacy policy URL* — use this repo's `PRIVACY.md`
     (e.g. `https://github.com/StinkyB4/Jonnyskeys/blob/main/PRIVACY.md`).
   - *Accessibility API usage* — Play's app-content questionnaire asks why
     the app uses an accessibility service. Answer honestly: it provides
     alternative input (maps a hardware key to a touch gesture) because
     the accessibility API is the only no-root way to inject touches; the
     app reads no screen content and collects no data. Key-mapper apps
     are allowed on Play with this declaration, but approval is Google's
     call and can take a few review cycles.
   - *Data safety form* — declare "no data collected or shared".

## Notes

- The mapped key is consumed system-wide while the service is on, so flip
  **Mapping active** off if you need that key for typing.
- Minimum Android version: 8.0 (API 26).
- The service declares `canPerformGestures` and key-event filtering only —
  it does not read screen content.
