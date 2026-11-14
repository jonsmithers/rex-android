# Screenshots

The README screenshots, captured from the `rex_test` emulator (Pixel 7, 1080x2400) and
downscaled to 540x1200.

| Light | Dark | Shows |
| --- | --- | --- |
| `connect.png` | `connect-dark.png` | Connect screen, server `10.0.2.2:7297` filled in |
| `sessions.png` | `sessions-dark.png` | Sessions list: `api-server`, `dotfiles`, `notes` |
| `session.png` | `session-dark.png` | `api-server`: Tab 1 (ACTIVE) with a shell and a `top` split, Tab 2 with `vim` |
| `terminal.png` | `terminal-dark.png` | `vim` editing a small Python file, keyboard hidden, keys bar visible |

## Regenerating

```sh
docs/screenshots/capture.sh
```

It takes about 90 seconds and overwrites all eight PNGs.

Before you run it:

- **Emulator running**, and the only adb device (or set `ANDROID_SERIAL`). The script doesn't
  start one. Use the `rex_test` AVD (see CLAUDE.md) so the shots keep the same size and density.
- **Debug APK built**: `./gradlew assembleDebug`. The script installs it with `adb install -r`.
- **`rex` on PATH**, plus `vim` and `top` (the demo programs).
- **Port 7297 free** on the host. The script refuses to run if something listens there.
  `REX_DEMO_PORT` picks another port, but connect.png then shows that port.
- **ImageMagick** (`magick`) for downscaling and compression. Without it the script falls back
  to macOS `sips`, which works but writes larger files (the connect screens come out at 300–380 KB).

What it does:

1. Starts a throwaway Rex server on `127.0.0.1:7297` with `--allow-anonymous`, a temp state
   dir, and a clean environment. A trap stops it on exit, including on failure or Ctrl-C.
2. Seeds the sessions. Every shell runs with `HOME`/`ZDOTDIR` set to a temp directory
   (`/tmp/rex-demo.XXXXXX`) that holds a minimal `.zshrc` and `.vimrc`, so your own prompt,
   paths, and hostname never reach a screenshot, and the app shows `~/api-server`.
3. Turns on System UI demo mode (9:41, full battery and Wi-Fi, no notifications).
4. Installs the APK, clears its data, and writes the server address into its preferences with
   `run-as`, so the connect screen never needs the keyboard.
5. Walks Connect → Sessions → `api-server` → the `vim` terminal, once in light mode and once in
   dark mode, finding tap targets by their text in `uiautomator dump`. On the terminal it
   hides the soft keyboard with the keys bar's keyboard keycap and checks `mInputShown`.
6. On exit: stops the server, force-stops the app, leaves demo mode, switches back to light
   mode, and deletes the temp directory.

## Before committing

Nothing is manual in the capture itself, but look at every PNG before committing them:

- Nothing personal: no username, `/Users/...` path, hostname, or email. The demo home
  directory is there to prevent this, but check if you change what the shells run.
- No half-finished animations, error cards, or reconnect banners. The script waits 2 seconds
  after each navigation (`settle`). Raise that on a slow machine.
- The session avatar colors and the `#xxxxxx` IDs on the Sessions screen come from the session
  IDs, so they change on every run.

The tap targets are looked up by text: `Connect`, `api-server`, `vim · ~/api-server`, and
`Esc`/`Tab` (the keyboard keycap is assumed to be one key to the left of `Esc`). If those labels
change in the app, update `capture.sh`.
