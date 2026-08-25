# Happy for Rokid Glasses

This Expo module connects the Happy Android app to consumer **Rokid Glasses**
through the official **Hi Rokid** app and Rokid's CXR-L SDK.

## Current scope

- Uses `com.rokid.cxr:client-l:1.1.1` and a `CUSTOM_VIEW` session.
- Opens the view through `CXRLink` only after both the Hi Rokid service and
  glasses Bluetooth link are ready, avoiding the SDK session wrapper's early
  CustomView callback race.
- Shows up to three active Happy sessions across Codex, Claude, Gemini, and
  other Happy agent flavors.
- Selects the primary session by explicit Rokid pin, the session open on the
  phone, pending approval, active work, then recent activity.
- Surfaces approval requests from every active session while keeping the
  approve/deny controls on the phone.
- Runs a `connectedDevice` foreground service while enabled so Android keeps
  the Happy sync and CXR-L binder connection alive when the phone is locked.
- Does not require a separate APK to be installed on the glasses.
- Requests no glasses camera or microphone permission.
- Keeps approval and denial controls in the Happy phone app for this phase.

`CUSTOM_VIEW` can render and update a view, but it does not provide the custom
command channel required for approve/deny input. That channel belongs to a
`CUSTOM_APP` session and requires a small CXR-S glasses helper. Voice input also
requires explicit microphone permission and a speech-recognition path. Those
capabilities should be added as a separate, opt-in phase rather than hidden in
the display-only bridge.

## Connection flow

1. Install Hi Rokid (`com.rokid.sprite.global.aiapp`) on the Android phone.
2. Pair Rokid Glasses in Hi Rokid.
3. In Happy, open **Settings → Rokid Glasses**.
4. Enable the bridge and approve the authorization screen opened by Hi Rokid.
5. Happy opens a CXR-L custom view and updates it as Happy sessions change.

The Android notification shown while relaying is required for reliable
screen-off operation. Force-stopping Happy, stopping its foreground service,
turning off Bluetooth/network access, or disconnecting Hi Rokid still stops
live updates until the app is opened again.

Happy stores only an app-private boolean indicating that authorization was
completed. It re-reads the token from Hi Rokid at startup, keeps it in memory
only for the connection handshake, and never syncs it through Happy.

Known upstream issue: CXR-L 1.1.1 writes the token to Android logcat while
connecting. Happy does not add its own token logging, but debug logs from a
connected device should still be treated as sensitive until Rokid removes that
SDK log statement.
