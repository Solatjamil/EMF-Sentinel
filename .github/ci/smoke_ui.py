#!/usr/bin/env python3
"""TEMPORARY (removed with the workflow): drives the app on the emulator through adb and records
what is on screen, so the native ad can be confirmed without a human looking at a device.

usage: smoke_ui.py <out_dir>          (writes ui_*.xml dumps and ui_findings.txt)
Never raises and always exits 0 - it only reports.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.goshbuzz.emfsentinel"
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/ci-logs/smoke"
os.makedirs(OUT, exist_ok=True)
REPORT = open(os.path.join(OUT, "ui_findings.txt"), "w")
BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def say(*parts):
    line = " ".join(str(p) for p in parts)
    print("[ui] " + line, flush=True)
    REPORT.write(line + "\n")
    REPORT.flush()


def adb(*args, timeout=90):
    try:
        r = subprocess.run(["adb"] + list(args), capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except Exception as exc:  # timeouts etc.
        return 1, repr(exc)


def parse(path):
    nodes = []
    for n in ET.parse(path).getroot().iter("node"):
        m = BOUNDS.match(n.get("bounds", ""))
        if not m:
            continue
        l, t, r, b = (int(v) for v in m.groups())
        nodes.append({"cls": n.get("class", ""), "text": n.get("text", ""), "desc": n.get("content-desc", ""),
                      "pkg": n.get("package", ""), "click": n.get("clickable") == "true",
                      "l": l, "t": t, "r": r, "b": b})
    return nodes


def dump(name):
    """uiautomator dump -> node list, or None. Retries: a screen that keeps animating can fail to idle."""
    path = os.path.join(OUT, "ui_%s.xml" % name)
    for attempt in range(1, 5):
        adb("shell", "rm", "-f", "/sdcard/ui_dump.xml")
        rc, out = adb("shell", "uiautomator", "dump", "/sdcard/ui_dump.xml")
        rc2, _ = adb("pull", "/sdcard/ui_dump.xml", path)
        if rc2 == 0 and os.path.exists(path) and os.path.getsize(path) > 0:
            try:
                return parse(path)
            except ET.ParseError as exc:
                say("  (parse error in %s: %s)" % (name, exc))
        else:
            say("  (dump attempt %d failed: %s)" % (attempt, out.strip()[:140] or "no output"))
        time.sleep(2)
    return None


def label(n):
    return (n["text"] + " " + n["desc"]).strip()


def app_nodes(nodes):
    return [n for n in nodes if n["pkg"] == PKG]


def find_label(nodes, wanted):
    hits = [n for n in app_nodes(nodes) if wanted.lower() in label(n).lower()]
    return max(hits, key=lambda n: n["t"]) if hits else None   # lowest on screen = the bottom-bar item


def tap(n):
    adb("shell", "input", "tap", str((n["l"] + n["r"]) // 2), str((n["t"] + n["b"]) // 2))


def screen_size():
    _, out = adb("shell", "wm", "size")
    m = re.findall(r"(\d+)x(\d+)", out)
    return (int(m[-1][0]), int(m[-1][1])) if m else (1080, 2400)


def density():
    _, out = adb("shell", "wm", "density")
    m = re.findall(r"(\d+)", out)
    return int(m[-1]) if m else 420


def dp(px, dpi):
    return round(px * 160.0 / dpi, 1)


def texts(nodes, limit=45):
    rows = []
    for n in sorted(app_nodes(nodes), key=lambda n: (n["t"], n["l"])):
        if label(n):
            rows.append("    y=%4d-%4d x=%4d-%4d  %s" % (n["t"], n["b"], n["l"], n["r"], label(n).replace("\n", " | ")[:70]))
    return rows[:limit]


def visit(tab, width, height, dpi):
    say("")
    say("=== tab: %s ===" % tab)
    nodes = dump("pre_" + tab.lower())
    if nodes is None:
        say("could not dump the UI before opening %s" % tab)
        return
    target = find_label(nodes, tab)
    if target is None:
        say("bottom-bar item '%s' not found; packages seen: %s; texts on screen:" % (tab, packages(nodes)))
        for row in any_texts(nodes, 25):
            say(row)
        return
    tap(target)
    time.sleep(4)
    found = None
    for step in range(8):
        nodes = dump("%s_%d" % (tab.lower(), step))
        if nodes is None:
            break
        badge = [n for n in app_nodes(nodes) if n["text"].strip() == "Ad"]
        if badge:
            found = (step, nodes, badge[0])
            break
        adb("shell", "input", "swipe", str(width // 2), str(int(height * 0.72)), str(width // 2), str(int(height * 0.28)), "350")
        time.sleep(1.5)
    if not found:
        say("NO native-ad 'Ad' badge found on %s after scrolling (8 dumps). Last texts on screen:" % tab)
        if nodes:
            for row in texts(nodes, 25):
                say(row)
        return
    step, nodes, badge = found
    say("native-ad 'Ad' badge FOUND on %s after %d scroll(s): bounds x=%d-%d y=%d-%d  (%.1fdp x %.1fdp)" % (
        tab, step, badge["l"], badge["r"], badge["t"], badge["b"],
        dp(badge["r"] - badge["l"], dpi), dp(badge["b"] - badge["t"], dpi)))
    # everything horizontally inside the card band around the badge (headline / body / CTA / stars ...)
    top = badge["t"] - 40
    band = [n for n in app_nodes(nodes) if n["b"] > top and n["t"] < top + int(height * 0.6)
            and not ((n["r"] - n["l"]) >= width * 0.95 and (n["b"] - n["t"]) >= height * 0.5)]  # skip whole-screen containers
    say("nodes in the card area (y from %d):" % top)
    for n in sorted(band, key=lambda n: (n["t"], n["l"]))[:40]:
        w, h = n["r"] - n["l"], n["b"] - n["t"]
        say("    %-28s y=%4d-%4d x=%4d-%4d  %5.1fdp x %5.1fdp  %s" % (
            n["cls"].split(".")[-1][:28], n["t"], n["b"], n["l"], n["r"], dp(w, dpi), dp(h, dpi),
            label(n).replace("\n", " | ")[:60]))
    # keep the real class names too (uiautomator only shows accessibility classes)
    rc, hier = adb("shell", "dumpsys", "activity", "top")
    with open(os.path.join(OUT, "hier_%s.txt" % tab.lower()), "w") as fh:
        fh.write(hier)


def packages(nodes):
    counts = {}
    for n in nodes:
        counts[n["pkg"]] = counts.get(n["pkg"], 0) + 1
    return counts


def any_texts(nodes, limit=20):
    rows = []
    for n in sorted(nodes, key=lambda n: (n["t"], n["l"])):
        if label(n):
            rows.append("    [%s] y=%4d x=%4d  %s" % (n["pkg"].split(".")[-1][:14], n["t"], n["l"], label(n).replace("\n", " | ")[:60]))
    return rows[:limit]


def crash_lines():
    _, out = adb("logcat", "-b", "crash", "-d", "-v", "brief")
    return [l for l in out.splitlines() if "FATAL EXCEPTION" in l or "Fatal signal" in l]


def alive():
    _, out = adb("shell", "pidof", PKG)
    return bool(out.strip())


def checkpoint(name):
    ok_alive, fatal = alive(), crash_lines()
    say("  checkpoint %-26s process alive=%s   fatal lines in crash buffer=%d" % (name, ok_alive, len(fatal)))
    return ok_alive and not fatal


def exercise(width, height, dpi):
    """Walk the rest of the app. A missing R8 keep rule can hide in any screen, so after every step the
    process must still be alive and the crash buffer empty."""
    say("")
    say("=== exercising the rest of the app (process must survive every step) ===")
    checkpoint("before exercising")
    for tab in ("Heatmap", "AR Scan", "Scanner"):
        nodes = dump("x_pre_" + tab.lower().replace(" ", "_"))
        target = find_label(nodes, tab) if nodes else None
        if target is None:
            say("  tab '%s' not found on screen" % tab)
            continue
        tap(target)
        time.sleep(6 if tab == "AR Scan" else 3)
        after = dump("x_" + tab.lower().replace(" ", "_"))
        say("  opened %-9s -> %d text nodes on screen" % (tab, len([n for n in app_nodes(after or []) if label(n)])))
        if tab == "AR Scan" and after:
            for row in texts(after, 14):
                say(row)
        checkpoint("tab " + tab)
    nodes = dump("x_pre_chrome") or []
    gear = [n for n in app_nodes(nodes) if "\u2699" in label(n)]          # the settings gear
    if gear:
        tap(gear[0])
        time.sleep(3)
        dialog = dump("x_settings") or []
        say("  settings dialog: %d text nodes; first rows:" % len([n for n in app_nodes(dialog) if label(n)]))
        for row in texts(dialog, 12):
            say(row)
        checkpoint("settings dialog open")
        adb("shell", "input", "keyevent", "4")                              # BACK closes the dialog
        time.sleep(2)
        checkpoint("settings dialog closed")
    else:
        say("  settings gear not found")
    for round_no in (1, 2):                                                  # dark -> light -> dark
        nodes = dump("x_theme_%d" % round_no) or []
        toggle = [n for n in app_nodes(nodes) if "\u2600" in label(n) or "\U0001F319" in label(n)]
        if not toggle:
            say("  theme toggle not found")
            break
        tap(toggle[0])
        time.sleep(3)
        checkpoint("theme toggle #%d" % round_no)


def main():
    width, height = screen_size()
    dpi = density()
    say("screen %dx%d px @ %d dpi (1dp = %.3f px)" % (width, height, dpi, dpi / 160.0))
    nodes = dump("scanner")
    if nodes is None:
        say("the first UI dump failed completely; nothing more to report")
        return
    for attempt in (1, 2):
        if app_nodes(nodes):
            break
        say("attempt %d: no node of %s on screen. packages seen: %s" % (attempt, PKG, packages(nodes)))
        for row in any_texts(nodes):
            say(row)
        # most likely the keyguard / a system dialog is in front: wake, dismiss, bring the app back
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "input", "keyevent", "82")
        time.sleep(1)
        adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(8)
        nodes = dump("scanner_retry%d" % attempt) or nodes
    rows = texts(nodes, 30)
    say("first screen after launch: %d app nodes, top of the list:" % len(app_nodes(nodes)))
    for row in rows:
        say(row)
    # anything that looks like a dialog / consent form in front of the content
    blockers = [label(n) for n in app_nodes(nodes)
                if re.search(r"(?i)consent|privacy|allow|permission|cookies|agree", label(n))]
    say("possible dialog / consent text on the first screen: %s" % (blockers[:6] or "none"))
    for tab in ("Insights", "Health"):
        visit(tab, width, height, dpi)
    exercise(width, height, dpi)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # report only
        say("smoke_ui crashed: %r" % (exc,))
    sys.exit(0)
