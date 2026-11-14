# Rex for Android (barebones)

A minimal Android client for [Rex](https://www.superlogical.com/rex/docs/installation).
It connects to a Rex server over the network, lists sessions, and opens a terminal block
as a live, interactive terminal.

What it does:

- Connect to a Rex server by address (`host:7297`, `http://…`, `https://rex.<tailnet>.ts.net`).
- List sessions, create a new shell session, and list a session's terminal blocks.
- Show a terminal live, with colors and full-screen apps (vim, top, …), and type into it.
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

Requires JDK 17+ (21 tested) and the Android SDK with platform 37.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## How it works

```
RexControl.kt     rex.control.v1: WebSocket /api/control, hello/welcome, JSON request/response, events
TerminalStream.kt rex.data.terminal.v1: data.connect ticket → WebSocket /api/data → HELLO/WELCOME → byte stream
TerminalScreen.kt glue: stream → xterm.js, keyboard → com.superlogical.terminal.write (serialized)
TermWebView.kt    WebView that owns the IME/hardware keyboard itself
RexViewModel.kt   connection lifecycle, navigation, auto-reconnect
assets/terminal/  xterm.js 6.0 + fit addon (rendering only; stdin disabled)
```

Details that weren't obvious from the docs:

- **The Ghostty snapshot.** A terminal stream starts with a binary Ghostty snapshot
  (`GHOSTSNP…`), which xterm.js can't read. The client drops bytes up to the first
  `ESC _ rex1;<nonce>;{"type":"resize",…} ESC \`, which the server sends right after the
  snapshot. It then seeds the screen from `com.superlogical.terminal.format` with
  `format: "vt"` (cursor, modes, style, and scrolling region included) and goes live.
  A few bytes printed in the instant between those two steps can be lost.
- **Keyboard.** xterm.js's hidden-textarea input drops and repeats characters with Android
  keyboards. Input is taken natively instead: a visible-password `InputConnection` plus
  `dispatchKeyEvent`. With stdin disabled, xterm.js also doesn't answer terminal queries
  (DA/DSR). The server's terminal already answers them, so doing it here would send
  duplicate replies.
- **Ordering.** Block methods such as `terminal.write` aren't run in order on the server.
  Writes are sent one at a time, and keystrokes typed meanwhile are batched together.

## Limitations

- One block at a time. No split layouts, window switching, or floating layers.
- No mouse reporting, text selection or copy, or scrollback gestures beyond what xterm.js does.
- Composing keyboards (CJK, swipe) only send text once it's committed.
- Uses the system monospace font, so Nerd Font and powerline glyphs show as boxes.
- Cleartext HTTP is allowed so the client can reach tailnet or LAN listeners.
- No SSH transport and no login-server accounts.
