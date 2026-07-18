# Play Store listing — copy-paste material

Assets in this folder:

- `icon-512.png` — app icon (512×512, required)
- `feature-graphic.png` — feature graphic (1024×500, required)
- Screenshots: take at least 2 phone screenshots of the app yourself
  (Play requires real screenshots; grab the main screen and the
  accessibility-settings step).

## App name (30 chars max)

```
Jonnyskeys — Keyboard to Touch
```

## Short description (80 chars max)

```
Play touch-only games with a USB keyboard. One key becomes a tap. No root.
```

## Full description (4000 chars max)

```
Plug a USB keyboard into your Android phone and play touch-only games with real keys — built for Geometry Dash, where one key is all you need.

Android recognizes USB keyboards out of the box, but most games ignore key presses because they only listen for touch. Jonnyskeys bridges that gap: it turns one keyboard key of your choice into a touch on the screen.

HOW IT WORKS
• Key down — a touch is pressed at a screen position you choose
• Key held — the touch stays held, so ship, wave and hold mechanics work
• Key up — the touch is released

FEATURES
• Map any key on the keyboard (Space by default)
• Adjustable tap position with simple sliders
• Master on/off switch, so your key still types normally when you're not gaming
• No root required, no ads, no tracking, no network access

SETUP
1. Enable the Jonnyskeys service in Accessibility settings
2. Plug in your USB keyboard (USB-C directly, or USB-A with an OTG adapter)
3. Open your game and press your key

WHY ACCESSIBILITY PERMISSION?
The accessibility API is the only way Android allows an app, without root, to receive hardware key events while a game is in the foreground and to inject touch gestures. Jonnyskeys uses it for exactly that and nothing else: it does not read screen content, does not log your typing (only the one mapped key is intercepted), and never sends any data anywhere. Full privacy policy is linked below.
```

## Category / tags

- Application type: App
- Category: Tools
- Tags: keyboard, key mapper, accessibility, games

## Privacy policy URL

```
https://github.com/StinkyB4/Jonnyskeys/blob/main/PRIVACY.md
```

(Use the branch URL until the branch is merged to main.)

## App content declarations (Play Console → Policy → App content)

- **Privacy policy**: URL above.
- **Ads**: No, the app contains no ads.
- **App access**: All functionality is available without special access
  (reviewers can open the app freely; note in the instructions field that
  full functionality needs a USB keyboard plugged in).
- **Content rating questionnaire**: Utility/Tools app, no objectionable
  content — will come out "Everyone".
- **Target audience**: 13+ (do not select children's ages; that triggers
  the Families policy).
- **Data safety**: "No data collected", "No data shared". The app has no
  internet permission.
- **Government app / financial features / health**: No to all.
- **AccessibilityService API declaration** (the important one):
  - Purpose: alternative input for users who need or prefer a physical
    keyboard — the app converts one hardware keyboard key into a touch
    gesture so touch-only apps and games can be operated with a keyboard.
  - Why the API is needed: it is the only Android API that allows
    receiving hardware key events while another app is in the foreground
    and injecting touch gestures without root.
  - Data handling: the service reads no window/screen content and stores
    nothing but the user's own settings on-device; nothing is transmitted.
  - Prominent disclosure: shown in-app before the user is sent to enable
    the service, and repeated in the service's system consent dialog.
