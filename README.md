# Rex for Android (barebones)

A minimal Android client for [Rex](https://www.superlogical.com/rex/docs/installation).
It connects to a Rex server over the network, lists sessions, and opens a terminal block
as a live, interactive terminal.

What it does:

- Connect to a Rex server by address (`host:7297`, `http://…`, `https://rex.<tailnet>.ts.net`).
- List sessions, create a new shell session, and list a session's terminal blocks.
- Show a terminal live, with colors and full-screen apps (vim, top, …), and type into it.
  Rendering and key encoding use Ghostty's terminal library (libghostty-vt), so the screen
  matches the server's exactly, scrollback included. Drag to scroll through history.
  The keyboard opens when a terminal opens, and tapping the terminal brings it back.
  The keys bar has a ⌨ toggle, Esc, Tab, sticky Ctrl, arrows, and `| ~ / -`.
  Hardware keyboards work too.
- Size the terminal to the phone while you view it. The client takes ownership of the block
  and gives it back when you leave.
- Reconnect automatically when Android drops the connection, using the server's
  `reconnect_token`. Android cuts a backgrounded app's sockets after a few seconds.

## Making your server reachable

The client speaks Rex's network API directly. It needs a server listening on TCP.
SSH (`ssh://`) endpoints are not supported. Pick one:

| Setup | Server | Address in the app |
| --- | --- | --- |
| Built-in Tailscale (recommended) | `rex server tailscale …` / `--tailscale` | `https://rex.<tailnet>.ts.net` |
| Tailnet listener | `--extra-listen tailnet://:7297` | `http://<machine>.<tailnet>.ts.net:7297` |
| Emulator testing only | `rex server run --listen http://127.0.0.1:7297 --allow-anonymous --state-dir /tmp/rex-test --restore=false` | `10.0.2.2:7297` |

`--allow-anonymous` gives every connection full access. Bind it to loopback only, as above,
and use it only for testing.

## Building

Requires JDK 17+ (21 tested), the Android SDK with platform 37, NDK 29.0.14206865, and
CMake 4.1.2 from the SDK. Building libghostty-vt also needs zig 0.16 and a Ghostty checkout
(the script clones from `~/git/ghostty`, or from GitHub if there's none).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
sdkmanager "ndk;29.0.14206865" "cmake;4.1.2"
./scripts/build-libghostty-vt.sh   # once; rebuild after changing scripts/ghostty.version
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## How it works

```
RexControl.kt     rex.control.v1: WebSocket /api/control, hello/welcome, JSON request/response, events
TerminalStream.kt rex.data.terminal.v1: data.connect ticket → WebSocket /api/data → HELLO/WELCOME → byte stream
TerminalScreen.kt glue: stream → Ghostty terminal, keyboard → com.superlogical.terminal.write (serialized); keys bar
TerminalView.kt   draws the terminal on a Canvas; owns touch, the IME, and hardware keys
GhosttyVt.kt      Kotlin side of the JNI shim; VtTerminal wraps one Ghostty terminal
cpp/rexvt.c       JNI shim over libghostty-vt (snapshot decode, VT parsing, render state, key encoding)
RexViewModel.kt   connection lifecycle, navigation, auto-reconnect
```

Details that weren't obvious from the docs:

- **The Ghostty snapshot.** A terminal stream starts with a binary Ghostty snapshot
  (`GHOSTSNP…`) of the whole terminal, scrollback included. The client finds its end by
  walking the record framing to the FINISH record, restores it with libghostty-vt's
  snapshot decoder, and feeds everything after it to that terminal. The Ghostty version is
  pinned in `scripts/ghostty.version`. If a snapshot ever fails to decode (say, the server's
  Ghostty writes a newer format), the client falls back to seeding the screen from
  `com.superlogical.terminal.format` with `format: "vt"`.
- **Keyboard.** Input is taken natively: a visible-password `InputConnection` (so keyboards
  commit each character) plus `dispatchKeyEvent`. Keys go through Ghostty's key encoder,
  which follows the terminal's modes (application cursor keys, kitty keyboard flags, …).
- **No query replies.** The client's terminal never answers queries (DA/DSR). The server's
  terminal already answers them, so doing it here would send duplicate replies.
- **Size.** The grid follows the server's `resize` messages. The client asks for the size
  that fits the phone (with its cell size in pixels), but if another client owns the block,
  a grid too big for the screen is scaled down to fit.
- **Ordering.** Block methods such as `terminal.write` aren't run in order on the server.
  Writes are sent one at a time, and keystrokes typed meanwhile are batched together.

## Limitations

- One block at a time. No split layouts, window switching, or floating layers.
- No mouse reporting, text selection, or copy. In full-screen apps, dragging sends arrow keys.
- Composing keyboards (CJK, swipe) only send text once it's committed.
- Uses the system monospace font, so Nerd Font and powerline glyphs show as boxes.
- Cleartext HTTP is allowed so the client can reach tailnet or LAN listeners.
- No SSH transport and no login-server accounts.
