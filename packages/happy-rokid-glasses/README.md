# Happy for Rokid Glass3

This fork uses Happy as the session and permission source, an Android phone as
the relay, and a small native application on Rokid Glass3 as the display and
control surface.

Status: the TypeScript protocol, Expo module, Rokid Phone SDK integration, and
Rokid Glass SDK application compile successfully. A real phone-to-glasses
round-trip still needs to be verified on the target hardware and OTA version.

## Architecture

```text
Codex App Server
      │ structured session state and permission requests
Happy CLI / encrypted Happy sync
      │
Happy Android app
      │ Rokid Phone SDK 2.2.0-E, Classic Bluetooth
Rokid Glass3 app
      ├─ progress and current session display
      └─ one-time approve / deny response
```

This does not connect the glasses directly to the Codex desktop application.
The desktop session must be started through `happy codex`, which gives Happy a
structured permission channel instead of relying on notification text or UI
scraping.

## Current scope

- Shows one primary Codex session: the viewed active session first, then a
  session awaiting permission, a working session, or the most recently active
  Codex session.
- Sends status changes and up to the five oldest pending permission requests.
- Supports only `approve` and `deny`. Persistent approval and permission-mode
  changes are not represented by the wire protocol.
- Short glasses button press approves; long press denies.
- Pressing Enter starts Korean offline-preferred Android speech recognition.
  Say `승인` or `거부`. Voice control is unavailable when the glasses image has
  no local speech recognizer; this milestone does not upload audio to Happy or
  ElevenLabs.
- Uses Classic Bluetooth for small control messages. It does not open a LAN
  port or require the phone and glasses to share Wi-Fi.

## Security boundary

- Each permission request receives a random nonce with a ten-minute lifetime.
- A response is accepted only if the exact Codex request is still pending, the
  session and nonce match, the message is fresh, and the nonce has never been
  consumed.
- Command summaries are length-limited and redact common token, password,
  authorization, API-key, secret, and credential fields before leaving the
  Happy app.
- The glasses app independently limits message size, field lengths, and request
  count. Unknown message types and malformed JSON are ignored.
- The phone's Happy UI remains the recovery path for reviewing full request
  details. The glasses show a deliberately bounded summary.

## Build

Prerequisites are Android Studio/JDK 17, Android SDK 36, an Android phone, and a
Rokid Glass3 Enterprise device supported by the Terminal SDK. The Rokid Maven
repository and SDK `2.2.0-E` are configured in source.

Build and install the Happy Android development app (Expo Go cannot load the
custom native module):

```bash
pnpm install
pnpm --filter happy-app android:dev
```

Build the glasses APK:

```bash
cd packages/happy-rokid-glasses
./gradlew assembleDebug
```

The APK is generated at
`app/build/outputs/apk/debug/app-debug.apk`. Install it on Glass3 using Android
Studio or `adb install -r` over the Rokid debug connection.

## Pair and use

1. Launch **Happy for Rokid** on the glasses. It registers the
   `HappyRokidGlasses` client and makes the device discoverable.
2. In the forked Happy Android app, open **Settings → Rokid Glasses**, enable
   the bridge, grant Nearby Devices permission, and scan.
3. Select the discovered Glass3. The address is saved for later reconnects.
4. On the computer, authenticate Happy and start the desktop session with
   `happy codex`.
5. When Codex requests permission, inspect the bounded summary on the glasses.
   Short-press to approve, long-press to deny, or press Enter and say the
   decision.

## Verification commands

```bash
pnpm --filter happy-app test --run sources/rokid/protocol.spec.ts
pnpm --filter happy-app typecheck
pnpm --filter happy-app exec expo prebuild --platform android --no-install

cd packages/happy-app/android
./gradlew :happy-rokid:compileDebugKotlin

cd ../../../happy-rokid-glasses
./gradlew :app:compileDebugKotlin
```

The generated `packages/happy-app/android` directory is only needed for local
native builds and is normally recreated by Expo prebuild.

## Primary SDK references

- [Happy upstream](https://github.com/slopus/happy)
- [Rokid Terminal SDK quick start](https://x-docs.rokid.com/docs/terminal-sdk/getting-started/%E5%BF%AB%E9%80%9F%E5%BC%80%E5%A7%8B.html)
- [Rokid Phone SDK API](https://x-docs.rokid.com/docs/en/terminal-sdk/api-reference/Glass3%20%20SDK%28%E6%89%8B%E6%9C%BA%E7%AB%AF%29%20API%E6%96%87%E6%A1%A3.html)
- [Rokid Glass SDK API](https://x-docs.rokid.com/docs/en/terminal-sdk/api-reference/Glass3%20%20SDK%28%E7%9C%BC%E9%95%9C%E7%AB%AF%29%20API%E6%96%87%E6%A1%A3.html)
