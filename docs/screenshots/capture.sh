#!/usr/bin/env bash
# Regenerates the README screenshots in this directory from scratch.
#
# Starts a throwaway Rex server on loopback, seeds it with demo sessions whose
# shells use a neutral home directory (no personal paths or prompts), installs
# the debug APK on the running emulator, and walks the app through Connect,
# Sessions, Session, and Terminal in light and dark mode.
#
# Needs: a running emulator (one adb device), a built debug APK, and `rex` on
# PATH. See README.md here. Usage: docs/screenshots/capture.sh

set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB=${ADB:-$ANDROID_HOME/platform-tools/adb}
APK=$ROOT/app/build/outputs/apk/debug/app-debug.apk
PKG=dev.rexclient.android
PORT=${REX_DEMO_PORT:-7297}   # the connect screenshot shows this port
SERVER=http://127.0.0.1:$PORT
WIDTH=540                     # committed PNG width (the emulator is 1080 wide)

say() { printf '\033[1m==> %s\033[0m\n' "$*"; }
die() { printf 'capture.sh: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- preflight

[ -x "$ADB" ] || die "adb not found at $ADB (set ANDROID_HOME or ADB)"
command -v rex >/dev/null || die "rex is not on PATH"
if command -v magick >/dev/null; then
  shrink() { magick "$1" -resize "${WIDTH}x" -alpha off -strip -define png:compression-level=9 \
    -define png:compression-filter=5 -define png:compression-strategy=1 "PNG24:$2"; }
elif command -v sips >/dev/null; then
  # Works, but the connect screens' gradients come out ~60% larger.
  shrink() { sips --resampleWidth "$WIDTH" "$1" --out "$2" >/dev/null; }
else
  die "need ImageMagick (magick) or macOS sips to downscale the screenshots"
fi
[ -f "$APK" ] || die "no APK at $APK; run ./gradlew assembleDebug first"

devices=$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if [ -z "${ANDROID_SERIAL:-}" ]; then
  case $(printf '%s\n' "$devices" | grep -c .) in
    0) die "no emulator connected; start one first (see CLAUDE.md, AVD rex_test)" ;;
    1) export ANDROID_SERIAL=$devices ;;
    *) die "several adb devices; pick one with ANDROID_SERIAL=<serial>" ;;
  esac
fi
[ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || die "the emulator hasn't finished booting"
size=$("$ADB" shell wm size | awk '/Physical/ { print $3 }' | tr -d '\r')
[ "$size" = 1080x2400 ] || echo "warning: screen is $size, not 1080x2400 (Pixel 7); tap targets are looked up, but shots will differ" >&2

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  die "port $PORT is already in use; stop whatever listens there (or set REX_DEMO_PORT, which then shows in connect.png)"
fi

# ---------------------------------------------------------------- cleanup

# /tmp, not $TMPDIR: the shells' directories show up in the app, and the
# default TMPDIR is long and machine-specific.
DEMO=$(cd "$(mktemp -d /tmp/rex-demo.XXXXXX)" && pwd -P)
SERVER_PID=
cleanup() {
  set +e
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null && wait "$SERVER_PID" 2>/dev/null
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
  "$ADB" shell settings put global sysui_demo_allowed 0
  "$ADB" shell cmd uimode night no >/dev/null
  rm -rf "$DEMO"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# ---------------------------------------------------------------- demo files

# The shells run with HOME=$DEMO, so the app shows ~/api-server and the user's
# own ~/.zshrc (prompt, hostname, paths) never loads.
mkdir -p "$DEMO"/{api-server,dotfiles,notes,.state}
: > "$DEMO/.zshenv"
: > "$DEMO/rex-init.lua"
cat > "$DEMO/.zshrc" <<'EOF'
PROMPT='%F{green}%~%f %F{magenta}❯%f '
EOF
cat > "$DEMO/.vimrc" <<'EOF'
syntax on
set number termguicolors background=dark
colorscheme habamax
" let the terminal's own background show through
highlight Normal guibg=NONE ctermbg=NONE
highlight LineNr guibg=NONE ctermbg=NONE
highlight EndOfBuffer guibg=NONE ctermbg=NONE
set laststatus=2 noswapfile viminfo= shortmess+=I
EOF
# Lines stay under 50 columns so nothing wraps on a phone, and the file fits
# on one screen (38 rows with the keyboard hidden).
cat > "$DEMO/api-server/server.py" <<'EOF'
"""A tiny JSON API for the todo app."""
import json
from dataclasses import asdict, dataclass
from http.server import (
    BaseHTTPRequestHandler,
    HTTPServer,
)

PORT = 8080
JSON = "application/json"

@dataclass
class Todo:
    id: int
    title: str
    done: bool = False

TODOS = [
    Todo(1, "Write the README"),
    Todo(2, "Take screenshots", done=True),
    Todo(3, "Ship it 🚀"),
]

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/todos":
            return self.send_error(404)
        items = [asdict(t) for t in TODOS]
        body = json.dumps(items).encode()
        self.send_response(200)
        self.send_header("Content-Type", JSON)
        self.end_headers()
        self.wfile.write(body)

if __name__ == "__main__":
    server = HTTPServer(("", PORT), Handler)
    print(f"listening on :{PORT}")
    server.serve_forever()
EOF

# ---------------------------------------------------------------- server

say "Starting a loopback Rex server on $SERVER"
# A clean environment, so nothing personal leaks into the shells.
env -i HOME="$DEMO" USER=demo LOGNAME=demo SHELL=/bin/zsh LANG=en_US.UTF-8 \
  PATH=/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin TMPDIR="$DEMO/.state" \
  "$(command -v rex)" server run --listen "$SERVER" --allow-anonymous \
  --state-dir "$DEMO/.state" --restore=false --autostart=false --config "$DEMO/rex-init.lua" \
  > "$DEMO/.state/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 50); do
  curl -sf -o /dev/null "$SERVER/llms.txt" && break
  kill -0 "$SERVER_PID" 2>/dev/null || { cat "$DEMO/.state/server.log" >&2; die "the server exited"; }
  sleep 0.2
done
curl -sf -o /dev/null "$SERVER/llms.txt" || die "the server didn't come up"

r() { rex -S "$SERVER" "$@" >/dev/null; }
in_demo=(/usr/bin/env HOME="$DEMO" ZDOTDIR="$DEMO" LANG=en_US.UTF-8)

say "Seeding sessions"
for s in api-server dotfiles notes; do
  r new "$s" --window main --cwd "$DEMO/$s" --shell=none -- "${in_demo[@]}" /bin/zsh -i
done
# Tab 1: the shell plus a split running top. Tab 2 (not focused): vim.
r run -s api-server --split=right --cwd "$DEMO/api-server" --shell=none -- "${in_demo[@]}" top -o cpu
r run -s api-server --focus=false --cwd "$DEMO/api-server" --shell=none -- "${in_demo[@]}" vim server.py

# ---------------------------------------------------------------- device

say "Setting up the emulator"
"$ADB" shell settings put global sysui_demo_allowed 1
demo() { "$ADB" shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false -e powersave false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile hide
demo notifications -e visible false
demo status -e volume hide -e bluetooth hide -e location hide -e alarm hide -e sync hide

say "Installing $(basename "$APK")"
"$ADB" install -r "$APK" >/dev/null
"$ADB" shell am force-stop "$PKG"
"$ADB" shell pm clear "$PKG" >/dev/null
# Prefill the remembered server address, so the connect screen never needs
# the keyboard (and Gboard's suggestion strip can't show the clipboard).
printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" \
  '<map><string name="server">10.0.2.2:'"$PORT"'</string></map>' |
  "$ADB" shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/rex.xml'"

# ---------------------------------------------------------------- UI helpers

dump() { "$ADB" exec-out uiautomator dump /dev/tty 2>/dev/null || true; }
# Prints the centre "x y" of the first node whose text or content-desc is $1.
find_text() {
  dump | perl -ne 'BEGIN { $t = shift }
    while (/<node [^>]*?(?:text|content-desc)="\Q$t\E"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g) {
      print int(($1 + $3) / 2), " ", int(($2 + $4) / 2), "\n"; exit }' "$1"
}
wait_text() {   # wait_text TEXT [seconds]: prints its centre once it shows
  local p end=$((SECONDS + ${2:-20}))
  while [ $SECONDS -lt $end ]; do
    p=$(find_text "$1") && [ -n "$p" ] && { echo "$p"; return; }
    sleep 0.5
  done
  die "timed out waiting for '$1' on screen"
}
tap_text() { "$ADB" shell input tap $(wait_text "$1"); }
ime_shown() { "$ADB" shell dumpsys input_method | grep -q 'mInputShown=true'; }
shot() {   # shot NAME: screenshot, downscaled to $WIDTH, into this directory
  local raw="$DEMO/.state/$1.png"
  "$ADB" exec-out screencap -p > "$raw"
  shrink "$raw" "$HERE/$1.png"
  echo "    $1.png"
}
settle() { sleep 2; }   # screen slides and card fade-ins take about 0.5s

# ---------------------------------------------------------------- capture

capture() {   # capture SUFFIX: one pass through the app in the current theme
  local sfx=$1
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
  wait_text "10.0.2.2:$PORT" >/dev/null
  settle
  shot "connect$sfx"

  tap_text Connect
  wait_text api-server >/dev/null
  wait_text notes >/dev/null
  settle
  shot "sessions$sfx"

  tap_text api-server
  wait_text ACTIVE >/dev/null
  wait_text "vim · ~/api-server" >/dev/null
  settle
  shot "session$sfx"

  tap_text "vim · ~/api-server"
  # The terminal raises the keyboard on open (with retries); let it, then
  # hide it with the keys bar's keyboard keycap, left of Esc.
  local ex ey tx ty
  read -r ex ey <<< "$(wait_text Esc)"
  read -r tx ty <<< "$(wait_text Tab)"
  sleep 2
  for _ in 1 2 3 4; do
    ime_shown || break
    "$ADB" shell input tap $((ex - (tx - ex))) "$ey"
    sleep 1.5
  done
  ime_shown && die "couldn't hide the soft keyboard"
  settle
  shot "terminal$sfx"
}

say "Capturing (light)"
"$ADB" shell cmd uimode night no >/dev/null
capture ""

say "Capturing (dark)"
"$ADB" shell cmd uimode night yes >/dev/null
sleep 1
capture "-dark"

say "Done"
for f in "$HERE"/*.png; do printf "    %4d KB  %s\n" $(($(stat -f %z "$f") / 1024)) "$(basename "$f")"; done
