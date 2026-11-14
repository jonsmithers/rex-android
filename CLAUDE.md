# CLAUDE.md

Barebones Android client for [Rex](https://www.superlogical.com/rex/docs/installation)
(a terminal multiplexer with a client/server split). Kotlin + Jetpack Compose, OkHttp
WebSockets, and Ghostty's libghostty-vt (via a small JNI shim) for terminal emulation, drawn
on a plain Canvas. See README.md for user-facing docs.

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./scripts/build-libghostty-vt.sh   # once: → app/libghostty-vt/ (gitignored)
./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk (debug-signed, sideloadable)
```

- **libghostty-vt** is built by `scripts/build-libghostty-vt.sh` from a temporary
  `git clone --shared` of `~/git/ghostty` at the commit in `scripts/ghostty.version`. Never
  modify or move `~/git/ghostty` (myterm uses it). It needs zig 0.16
  (`/opt/homebrew/opt/zig@0.16`; zig 0.17 is the default `zig` and too new) and the NDK.
  The static `.a` is linked into `librexvt.so` by `app/src/main/cpp/CMakeLists.txt`.
  Only `arm64-v8a` is built (phones and the Apple-silicon emulator).
- NDK 29.0.14206865 and CMake 4.1.2 come from the SDK (`sdkmanager`), and are pinned in
  `app/build.gradle.kts`.
- When bumping the Ghostty pin, check that a real server snapshot still decodes: capture a
  stream from the loopback server and decode it with a host build of libghostty-vt
  (`zig build -Demit-lib-vt` in a temp clone; `ghostty_snapshot_decoder_*`). The format is
  version 1 and has no compatibility guarantee yet. Logcat tag `rexvt` logs the linked
  version at startup and any decode failure.

- AGP 9.5 (built-in Kotlin, so don't apply `org.jetbrains.kotlin.android`), Gradle 9.9.1,
  Kotlin compose plugin 2.4.21.
- **compileSdk 37** is required: Compose BOM 2026.09, lifecycle 2.11, and OkHttp 5.5 refuse
  anything lower. targetSdk 36, minSdk 26.
- SDK: `~/Library/Android/sdk` (`local.properties`, gitignored). The SDK-local cmdline-tools
  (`$ANDROID_HOME/cmdline-tools/latest/bin`) work. Homebrew's `avdmanager` can't find
  system images there.
- No XML comments containing `--` in AndroidManifest.xml (the manifest merger fails).
- The disk is nearly full (about 6 GB free after the NDK). Check `df -h` before installing
  SDK packages.

## Source map

| File | Role |
| --- | --- |
| `RexControl.kt` | `rex.control.v1` client: hello/welcome, request/response by id, events, reconnect token, typed helpers, `SessionView` parsing |
| `TerminalStream.kt` | `rex.data.terminal.v1`: `data.connect` ticket → `/api/data` → HELLO/WELCOME → bytes. Collects the snapshot (record framing up to FINISH) for `onSnapshot`, strips `rex1` APCs; falls back to `terminal.format` vt seeding if the snapshot won't decode |
| `TerminalScreen.kt` | `TerminalController` glue: stream → `VtTerminal`, input → serialized `terminal.write`, serialized own/resize, disown; Compose keys bar and status overlay |
| `TerminalView.kt` | Canvas renderer for a `VtTerminal` frame; tap shows the keyboard, drag scrolls; custom `InputConnection` + `dispatchKeyEvent` through Ghostty's key encoder; sticky Ctrl |
| `GhosttyVt.kt` | JNI declarations and `VtTerminal` (thread-safe wrapper: write, resize, colors, scroll, frame, encodeKey) |
| `cpp/rexvt.c` | JNI shim: terminal create/decode/free, `vt_write`, resize, theme colors, render state → flat int arrays, synchronized-output holds, key encoding |
| `RexViewModel.kt` | Navigation state (`Screen`), connection lifecycle, auto-reconnect |
| `MainActivity.kt` | Compose UI: Connect → Sessions → Session (blocks) → Terminal |

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
- **Stream contents:** a binary Ghostty snapshot comes first: `GHOSTSNP` + u16 version 1,
  then records of u16 tag, u32 length, u32 CRC32C, and payload, ending with tag 6 (FINISH).
  Raw VT output follows, with interleaved `ESC _ rex1;<nonce decimal>;<json> ESC \`
  messages: `resize` (`cols`/`rows`/`cell_width_px`/`cell_height_px`), `theme`, and
  `child_exited`. The first `resize` follows the snapshot immediately.
- **Ownership:** the client calls `session.own_block` (take) so `terminal.resize` applies,
  and `session.disown_block` when leaving, so Rex.app can size the block again.
- **Network access:** remote servers need TCP (Tailscale `https://rex.<tailnet>.ts.net`, or
  `--extra-listen tailnet://:7297`). `ssh://` is not supported by this client.

## Android gotchas (learned the hard way)

- **Never let the client-side emulator answer terminal queries** (DA, DSR, …). The server's
  terminal already answers them, and replies from the client would duplicate them as input.
  `rexvt.c` leaves `GHOSTTY_TERMINAL_OPT_WRITE_PTY` unset for this reason.
- **Input must commit per character.** IMEs compose words unless the `InputConnection` says
  visible-password with no suggestions (`TerminalView.onCreateInputConnection`).
- **`showSoftInput` is unreliable** right after focus changes. `TerminalView.showKeyboard()`
  also uses the insets controller and retries.
- **Compose doesn't clip `AndroidView`s.** `canvas.drawColor` in `TerminalView.onDraw` painted
  the whole window until the view clipped to its own bounds.
- **Block calls race.** Concurrent `terminal.resize` calls can land out of order (a stale size
  wins), so size requests go through one conflated channel. A closing view's `disown_block`
  is awaited before the next view of that block claims it.
- **Android kills a backgrounded app's sockets within about 4s.** `RexViewModel` reconnects
  with the welcome's `reconnect_token` (the server keeps the client for 60s) on drop and in
  `onStart`, then re-attaches. `TerminalScreen` is keyed on the control instance, so a new
  connection rebuilds the view and stream.
- The activity handles config changes itself (`android:configChanges`), so the terminal view
  survives rotation.

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
  at y ≈ 1465 while the keyboard is up (⌨ x≈58, Esc 174, Tab 290, Ctrl 416, ← 544, ↑ 660,
  ↓ 776, → 892).
- `rex send -s demo $'…\r'` types on the server side, for commands `adb shell input text`
  can't express.
- `adb shell input text` breaks on `;` and quotes. Use `%s` for spaces.
- `--allow-anonymous` grants full access. Only use it on loopback, and stop the server afterwards.
- AVD `rex_test` (API 36, arm64) has a 2 GB data partition because of the disk constraints.

## Conventions

- Commit messages end with the Co-Authored-By trailer given in the session.
- Don't commit `HANDOFF-*.md` docs.
