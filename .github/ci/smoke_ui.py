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
        say("bottom-bar item '%s' not found; texts on screen:" % tab)
        for row in texts(nodes, 25):
            say(row)
        return
    tap(target)
    time.sleep(4)
    found = None
    for step in range(10):
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
        say("NO native-ad 'Ad' badge found on %s after scrolling (10 dumps). Last texts on screen:" % tab)
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


def main():
    width, height = screen_size()
    dpi = density()
    say("screen %dx%d px @ %d dpi (1dp = %.3f px)" % (width, height, dpi, dpi / 160.0))
    nodes = dump("scanner")
    if nodes is None:
        say("the first UI dump failed completely; nothing more to report")
        return
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


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # report only
        say("smoke_ui crashed: %r" % (exc,))
    sys.exit(0)
