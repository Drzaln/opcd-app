---
name: verify-on-device
description: Run OpenCode Mobile against a local opencode test server on an Android emulator or USB phone, drive it with adb, and check the result with screenshots or screen recordings (phone, 7" tablet and 10" tablet widths, light/dark). Use after any UI or behavior change, before saying it works.
---

# Verify a change on a device

Unit tests (`./gradlew :app:testDebugUnitTest`) don't cover UI. Check UI changes in the real app.

## Ground rules

- **Ask before booting an emulator or touching a phone** unless the user asked for it in this task. Use
  what's already attached (`adb devices`); with several, pass `-s <serial>` to every adb command.
- **Test credentials only.** Use the local test server below (user `opencode`, password `secret`).
  Never type the user's real opencode password, and don't run prompts or builds in their real projects
  — make a throwaway folder in a temp dir instead.
- Don't change system settings on a real phone (VPN, airplane mode, metered). On an emulator, reset
  anything you change (`wm size reset`, `wm density reset`, app theme back to System).
- Security/permission dialogs on a real phone are the user's to answer.

## 1. Test server

```bash
mkdir -p "$TMPDIR/oc-test" && cd "$TMPDIR/oc-test" && git init -q    # throwaway project
OPENCODE_SERVER_PASSWORD=secret nohup opencode serve --port 4199 --hostname 127.0.0.1 > /tmp/opencode-serve.log 2>&1 &
```

(`make serve` does the same from the repo root, but then the server's default folder is this repo.)
Stop it with `make stop` (or `pkill -f "opencode serve --port 4199"`) when done.

Reach it from the device with a reverse port (works for emulators and USB phones; it is lost when the
emulator restarts, so re-run it if the app suddenly says offline):

```bash
adb reverse tcp:4199 tcp:4199          # add tcp:4097 too if you need the Mac helper
```

## 2. Install and add the server

```bash
./gradlew -q :app:installDebug 2>&1 | grep -E "^e:|error:"   # also catches kapt errors, not just ^e:
adb shell am start -n dev.opencode.mobile/.MainActivity
```

In the app: Add server → Base URL `http://127.0.0.1:4199`, username `opencode`, password `secret`.

## 3. Drive it with adb

- Screenshot (downscaled so it's cheap to look at):
  `adb exec-out screencap -p > shot.png && sips -Z 1280 shot.png >/dev/null` (macOS; elsewhere use ImageMagick).
  Screenshot pixels ÷ density = dp; `adb shell input tap X Y` takes **device pixels**.
- Find things instead of guessing coordinates:
  `adb shell uiautomator dump /sdcard/u.xml && adb exec-out cat /sdcard/u.xml` → `text="…" bounds="[x1,y1][x2,y2]"`.
- Typing: tap the field, then `adb shell input text 127.0.0.1:4199`. Hardware shortcuts:
  `adb shell input keycombination 113 30` (Ctrl+B). Back = `input keyevent 4`, hide keyboard = `keyevent 111`
  (careful: with no keyboard open, BACK navigates away).
- Drag: `adb shell input swipe x1 y1 x2 y2 700`.
- **Fresh emulators** show a Gboard "Try out your stylus" tutorial the first time you type — close it
  and re-check the field contents with a uiautomator dump.

## 4. Sizes and themes

- Phone: any phone AVD or `adb shell wm size 1080x2400 && adb shell wm density 420`.
- 7" tablet: Small Tablet AVD, or `wm size 1920x1200` at density 320 → 960×600 dp.
- 10" tablet: Medium Tablet AVD (2560×1600 @ 320 → 1280×800 dp). Rotate with `adb emu rotate`.
- Landscape phone: `wm size 2400x1080` at density 420.
- Always `adb shell wm size reset && adb shell wm density reset` afterwards.
- Light/dark: Settings → Theme in the app (put it back to what it was).

## 5. Flicker, animation and streaming

A screenshot can't show a one-frame glitch. Record and measure:

```bash
adb shell screenrecord --time-limit 4 --size 1280x800 /sdcard/r.mp4 & sleep 1.5; adb shell input tap X Y; wait
adb pull /sdcard/r.mp4 .
ffmpeg -loglevel error -i r.mp4 -vf "fps=30,scale=64:40,format=gray" -f rawvideo - | python3 -c "
import sys; d=sys.stdin.buffer.read(); n=64*40
print([round(sum(d[i:i+n])/n) for i in range(0,len(d),n)])"     # per-frame brightness; a dip = flash
ffmpeg -loglevel error -i r.mp4 -vf "fps=30,scale=320:-1,tile=6x5" -frames:v 1 sheet.png   # contact sheet
```

Repeat a few runs (a tap can land before the screen is ready); confirm the last frame shows the screen
you meant to open.

## 6. Report

Say what you checked and on what (device/AVD, size, theme, server version), and what you didn't.
Update `PROGRESS.md` for anything non-obvious you learned.
