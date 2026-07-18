# Jonnyskeys Privacy Policy

_Last updated: July 2026_

Jonnyskeys maps a key on a connected USB keyboard to a touch input on the
device screen, so that touch-only games can be played with a keyboard.

## Data collection

Jonnyskeys does **not** collect, store, share, or transmit any data.

- No personal information is collected.
- No analytics, advertising, or tracking SDKs are included.
- The app makes no network connections of any kind.

## Accessibility service

Jonnyskeys uses Android's accessibility API because it is the only no-root
mechanism Android provides for (a) receiving hardware key events while
another app is in the foreground and (b) injecting touch gestures. The
service:

- reads only hardware key events for the single key you choose to map;
- injects touch gestures at the position you configure;
- does **not** read screen content, window content, or text you type in
  other apps (the one mapped key is intercepted; all other keys pass
  through untouched);
- keeps all processing on the device.

Your settings (the mapped key, tap position, and on/off state) are stored
locally on your device and never leave it.

## Contact

Questions about this policy can be raised by opening an issue on this
repository.
