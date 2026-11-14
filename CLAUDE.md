# CLAUDE.md

Barebones Android client for [Rex](https://www.superlogical.com/rex/docs/installation)
(a terminal multiplexer with a client/server split). Kotlin + Jetpack Compose, OkHttp
WebSockets, xterm.js in a WebView for rendering. See README.md for user-facing docs.

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk (debug-signed, sideloadable)
```

- AGP 9.5 (built-in Kotlin, so don't apply `org.jetbrains.kotlin.android`), Gradle 9.9.1,
  Kotlin compose plugin 2.4.21.
- **compileSdk 37** is required: Compose BOM 2026.09, lifecycle 2.11, and OkHttp 5.5 refuse
  anything lower. targetSdk 36, minSdk 26.
- SDK: `~/Library/Android/sdk` (`local.properties`, gitignored). The SDK-local cmdline-tools
  (`$ANDROID_HOME/cmdline-tools/latest/bin`) work. Homebrew's `avdmanager` can't find
  system images there.
- No XML comments containing `--` in AndroidManifest.xml (the manifest merger fails).
- The disk is nearly full (about 10 GB free). Check `df -h` before installing SDK packages.

## Source map

| File | Role |
| --- | --- |
| `RexControl.kt` | `rex.control.v1` client: hello/welcome, request/response by id, events, reconnect token, typed helpers, `SessionView` parsing |
| `TerminalStream.kt` | `rex.data.terminal.v1`: `data.connect` ticket → `/api/data` → HELLO/WELCOME → bytes. Skips the snapshot, seeds via `terminal.format` vt, strips `rex1` APCs |
| `TerminalScreen.kt` | `TerminalController` glue: stream → page, page input → serialized `terminal.write`, own/resize/disown |
| `TermWebView.kt` | WebView that owns the keyboard: custom `InputConnection` + `dispatchKeyEvent`, native tap-to-show-keyboard |
| `RexViewModel.kt` | Navigation state (`Screen`), connection lifecycle, auto-reconnect |
| `MainActivity.kt` | Compose UI: Connect → Sessions → Session (blocks) → Terminal |
| `assets/terminal/index.html` | xterm.js 6.0 + fit addon page, keys bar, `window.rex` API called from Kotlin |

## Rex protocol essentials

The authoritative reference is served by every Rex server at `GET /llms.txt`. It's about
300 KB, covering protocol plus method and event reference, and matches that server's version.
`rex api describe <method>` shows a method's schemas. The local server is on a unix socket:
`curl --unix-socket "$HOME/Library/Application Support/rex/server.sock" http://x/llms.txt`.

- **Control:** WebSocket `/api/control`, subprotocol `rex.control.v1`, one JSON object per text
  message. Send `hello` within 5s (under 512 bytes, uncompressed; `api_revision: 11`).
  Requests are `{type:"request", id, method, payload}`; responses come back by id and can arrive
  out of order. Events are nested: `event.payload = {type, payload}`, and session events wrap
  again in `session_event`.
- **Block methods** take `{session_id, block_id, args:{…}}`. They run concurrently on the
  server, so `com.superlogical.terminal.write` calls must be sent one at a time (see
  `TerminalController`). Resize args are `columns`/`rows`, not `cols`.
- **Data:** `data.connect {session_id, block_id, data_ref_id:"terminal", subprotocol:"rex.data.terminal.v1"}`
  needs a `session.attach` first; `attach_all` isn't enough. The ticket is base64url,
  one-use, valid for 60s. Binary HELLO is `0x01` + 16 ticket bytes. WELCOME is 32 bytes:
  `0x81`, 16-byte token, 3 zero bytes, u32 keep-ms, u64 nonce (all little-endian; the nonce
  is unsigned). REJECT is `0x82` + a reason byte.
- **Stream contents:** a binary Ghostty snapshot (`GHOSTSNP` + u16 version 1) comes first,
  then raw VT output with interleaved `ESC _ rex1;<nonce decimal>;<json> ESC \` messages:
  `resize` (`cols`/`rows`), `theme`, `child_exited`. The first `resize` follows the snapshot
  immediately.
- **Ownership:** the client calls `session.own_block` (take) so `terminal.resize` applies,
  and `session.disown_block` when leaving, so Rex.app can size the block again.
- **Network access:** remote servers need TCP (Tailscale `https://rex.<tailnet>.ts.net`, or
  `--extra-listen tailnet://:7297`). `ssh://` is not supported by this client.

## Android gotchas (learned the hard way)

- **xterm.js's own keyboard input is broken on Android.** Its textarea diffing drops and
  repeats characters. Input is handled natively in `TermWebView` (visible-password
  `InputConnection` and `dispatchKeyEvent`). xterm.js runs with `disableStdin: true`.
- **Never let the client-side emulator answer terminal queries** (DA, DSR, …). The server's
  terminal already answers them, and replies from the client would duplicate them as input.
- **`showSoftInput` is unreliable** right after focus changes. `TermWebView.showKeyboard()`
  also uses the insets controller and retries.
- **Android kills a backgrounded app's sockets within about 4s.** `RexViewModel` reconnects
  with the welcome's `reconnect_token` (the server keeps the client for 60s) on drop and in
  `onStart`, then re-attaches. `TerminalScreen` is keyed on the control instance, so a new
  connection rebuilds the view and stream.
- The activity handles config changes itself (`android:configChanges`), so the WebView survives
  rotation.

## Testing in the emulator

There are no unit tests. Verification is end-to-end in the emulator against a throwaway Rex
server bound to loopback (the emulator reaches the host as `10.0.2.2`):

```sh
rex server run --listen http://127.0.0.1:7297 --allow-anonymous --state-dir <tmpdir> --restore=false --autostart=false
$ANDROID_HOME/emulator/emulator -avd rex_test -no-snapshot -no-boot-anim -gpu swiftshader_indirect -no-audio -partition-size 2048
rex -S http://127.0.0.1:7297 new demo --window main          # something to open
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Drive it with `adb shell input tap/text/keyevent`, and check results with
  `rex -S http://127.0.0.1:7297 capture -s demo` (what the server saw) and
  `adb exec-out screencap -p` (what the app shows).
- Check the IME with `adb shell dumpsys input_method | grep mInputShown`.
- In the Pixel 7 AVD the terminal list's first row is at about (300, 370) and the keys bar is
  at y ≈ 1465 while the keyboard is up.
- `adb shell input text` breaks on `;` and quotes. Use `%s` for spaces.
- `--allow-anonymous` grants full access. Only use it on loopback, and stop the server afterwards.
- AVD `rex_test` (API 36, arm64) has a 2 GB data partition because of the disk constraints.

## Conventions

- Commit messages end with the Co-Authored-By trailer given in the session.
- Don't commit `HANDOFF-*.md` docs.
