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


SYSTEM_DIALOG = re.compile(r"(?i)isn't responding|keeps stopping|has stopped|not responding")


def clear_system_dialog(nodes):
    """An emulator on a shared CI runner sometimes shows "Pixel Launcher isn't responding". That is not the app,
    but it covers it: tap "Wait" (or OK) so the walk-through can continue. True when something was tapped."""
    sysn = [n for n in nodes if n["pkg"] == "android" or n["pkg"].startswith("com.android.systemui")]
    if not any(SYSTEM_DIALOG.search(label(n)) for n in sysn):
        return False
    for wanted in ("Wait", "OK", "Close app"):
        btn = [n for n in sysn if label(n).strip() == wanted]
        if btn:
            say("  (system dialog on top: %s -> tapping '%s')" % (
                [label(n)[:50] for n in sysn if SYSTEM_DIALOG.search(label(n))][:1], wanted))
            tap(btn[0])
            time.sleep(2)
            return True
    return False


def dump(name):
    """uiautomator dump -> node list, or None. Retries: a screen that keeps animating can fail to idle."""
    path = os.path.join(OUT, "ui_%s.xml" % name)
    for attempt in range(1, 5):
        adb("shell", "rm", "-f", "/sdcard/ui_dump.xml")
        rc, out = adb("shell", "uiautomator", "dump", "/sdcard/ui_dump.xml")
        rc2, _ = adb("pull", "/sdcard/ui_dump.xml", path)
        if rc2 == 0 and os.path.exists(path) and os.path.getsize(path) > 0:
            try:
                parsed = parse(path)
                if attempt < 4 and clear_system_dialog(parsed):
                    continue
                return parsed
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


def find_tab(nodes, wanted):
    """The bottom-bar item of a tab. AR Scan is the raised centre button: its clickable ring sits ABOVE its
    text label, so a tap on the label lands just below the ring and does nothing - use the camera emoji."""
    mine = app_nodes(nodes)
    if wanted == "AR Scan":
        icon = [n for n in mine if "\U0001F4F7" in label(n)]
        if icon:
            return max(icon, key=lambda n: n["t"])
    exact = [n for n in mine if label(n).strip().lower() == wanted.lower()]
    if exact:
        return max(exact, key=lambda n: n["t"])
    return find_label(nodes, wanted)


def page_rows(nodes, count=7, below=300):
    """The first text rows of the page content, below the app header (which every tab shares)."""
    rows = []
    for n in sorted(app_nodes(nodes), key=lambda n: (n["t"], n["l"])):
        if label(n) and n["t"] >= below:
            rows.append("    y=%4d x=%4d  %s" % (n["t"], n["l"], label(n).replace("\n", " | ")[:60]))
    return rows[:count]


def page_sig(nodes):
    """What identifies a page: its first two text rows below the shared app header. The ad badge is skipped
    on purpose - an ad that finishes loading must not look like a navigation."""
    texts_ = [label(n) for n in sorted(app_nodes(nodes), key=lambda n: (n["t"], n["l"]))
              if label(n) and n["t"] >= 300 and label(n).strip() != "Ad"]
    return tuple(texts_[:2])


def camera_evidence():
    """CameraX finds its default config class through a manifest meta-data entry and loads it by name -
    exactly the kind of thing R8 breaks. Record what the camera stack says after the AR tab opened."""
    _, out = adb("logcat", "-d", "-v", "brief")
    rows = [l.strip() for l in out.splitlines()
            if re.search(r"(?i)camerax|androidx\.camera|camera2cameraimpl|initializationexception|cameraunavailable", l)]
    say("  camera-related logcat lines: %d" % len(rows))
    shown = rows if len(rows) <= 12 else rows[:8] + ["..."] + rows[-4:]
    for l in shown:
        say("    " + l[:160])
    _, svc = adb("shell", "dumpsys", "media.camera")
    keep = [l.strip() for l in svc.splitlines()
            if re.search(r"(?i)number of camera devices|goshbuzz|Device \d+ maps|Active Camera Clients|^Camera module", l)]
    say("  camera service (dumpsys media.camera): %s" % ("; ".join(k[:110] for k in keep[:6]) or "no matching lines"))


def app_windows():
    """The app's windows from 'dumpsys window windows': the activity window plus everything hanging off it
    (dialogs, popups, ad / consent WebView hosts)."""
    _, out = adb("shell", "dumpsys", "window", "windows")
    wins, cur = [], None
    for line in out.splitlines():
        m = re.match(r"\s*Window #(\d+) Window\{(\w+) u\d+ ([^}]*)\}:", line)
        if m:
            cur = {"n": m.group(1), "id": m.group(2), "title": m.group(3)} if PKG in m.group(3) else None
            if cur is not None:
                wins.append(cur)
            continue
        if cur is None:
            continue
        t = line.strip()
        if t.startswith("mAttrs="):
            cur["attrs"] = t.split(" fmt=")[0][:150]
        elif t.startswith("Requested w="):
            cur["req"] = " ".join(t.split()[1:3])
        elif t.startswith("mBaseLayer="):
            cur["layer"] = " ".join(t.split()[:2])
        elif t.startswith("mAttachedWindow="):
            cur["attached"] = t[:60]
        elif t.startswith("mViewVisibility="):
            cur["vis"] = t.split()[0]
        elif t.startswith("mHasSurface="):
            cur["surf"] = " ".join(w for w in t.split() if w.startswith(("mHasSurface", "isReadyForDisplay")))
        elif t.startswith("Frames:"):
            m2 = re.search(r"\bframe=(\[[^\]]*\]\[[^\]]*\])", t)
            cur["frame"] = m2.group(1) if m2 else "?"
    return wins


def window_report(tag):
    wins = app_windows()
    say("  [windows %s] %d window(s) belong to the app:" % (tag, len(wins)))
    for w in wins:
        say("    #%s %s  %s  %s  frame=%s  %s  %s%s" % (
            w.get("n"), w.get("id"), w.get("attrs", "?"), w.get("req", ""), w.get("frame", "?"),
            w.get("vis", ""), w.get("surf", ""), ("  " + w["attached"]) if "attached" in w else ""))


def input_windows_at(x, y, limit=3):
    """Top-to-bottom: which input windows contain the point, i.e. who would receive a tap there."""
    _, out = adb("shell", "dumpsys", "input")
    hits = []
    for line in out.splitlines():
        m = re.search(r"name='([^']*)'.*?frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", line)
        if not m:
            continue
        name = m.group(1)
        l, t, r, b = (int(v) for v in m.groups()[1:])
        if l <= x < r and t <= y < b:
            hits.append("%s frame=[%d,%d][%d,%d]" % (name[-80:], l, t, r, b))
    return hits[:limit]


def view_roots():
    """Root views of every window of the top activity (class names only) - tells what a stray window contains."""
    _, out = adb("shell", "dumpsys", "activity", "top")
    lines = out.splitlines()
    try:
        start = next(i for i, l in enumerate(lines) if l.strip() == "View Hierarchy:")
    except StopIteration:
        say("  view roots: no 'View Hierarchy:' section in dumpsys activity top")
        return
    body = lines[start + 1:]
    if not body:
        return
    base = len(body[0]) - len(body[0].lstrip())
    roots = []
    for i, l in enumerate(body):
        ind = len(l) - len(l.lstrip())
        if l.strip() and ind < base:
            break
        if l.strip() and ind == base:
            roots.append(i)
    say("  view roots of the top activity: %d" % len(roots))
    for r in roots[:6]:
        say("    root " + body[r].strip()[:130])
        shown = 0
        for l in body[r + 1: r + 12]:
            ind = len(l) - len(l.lstrip())
            if ind <= base:
                break
            if ind == base + 2 and shown < 3:
                say("        " + l.strip()[:120])
                shown += 1


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


def crash_blocks():
    """(app, other): FATAL EXCEPTION / Fatal signal headers in the crash buffer, split by whether the block names
    the app. The uiautomator tool process and system processes can crash too - that is not the app."""
    _, out = adb("logcat", "-b", "crash", "-d", "-v", "threadtime")
    lines = out.splitlines()
    _, pidout = adb("shell", "pidof", PKG)
    pid = pidout.strip().split()[0] if pidout.strip() else ""
    mine, other = [], []
    heads = [i for i, l in enumerate(lines) if "FATAL EXCEPTION" in l or "Fatal signal" in l]
    for i in heads:
        later = [j for j in heads if j > i]
        ctx = " ".join(lines[i: min(i + 4, later[0] if later else i + 4)])   # never into the next block
        named = PKG in ctx or (pid and re.search(r"(?:PID: |pid |tid )%s\b" % re.escape(pid), ctx))
        (mine if named else other).append(lines[i].strip()[:160])
    return mine, other


def crash_lines():
    return crash_blocks()[0]


def alive():
    _, out = adb("shell", "pidof", PKG)
    return bool(out.strip())


def checkpoint(name):
    ok_alive = alive()
    mine, other = crash_blocks()
    say("  checkpoint %-26s process alive=%s   app crashes in crash buffer=%d   (other processes: %d)" % (
        name, ok_alive, len(mine), len(other)))
    return ok_alive and not mine


def exercise(width, height, dpi):
    """Walk the rest of the app. A missing R8 keep rule can hide in any screen, so after every step the
    process must still be alive and the crash buffer empty."""
    say("")
    say("=== exercising the rest of the app (process must survive every step) ===")
    checkpoint("before exercising")
    for tab in ("Heatmap", "AR Scan", "Scanner"):
        nodes = dump("x_pre_" + tab.lower().replace(" ", "_"))
        target = find_tab(nodes, tab) if nodes else None
        if target is None:
            say("  tab '%s' not found on screen" % tab)
            continue
        before = page_sig(nodes)
        tap(target)
        time.sleep(8 if tab == "AR Scan" else 3)                    # CameraX needs a moment to bind
        after = dump("x_" + tab.lower().replace(" ", "_"))
        if after is not None and page_sig(after) == before:          # the tap did not navigate: aim above the label
            say("  (%s: screen unchanged after the first tap at (%d,%d); input windows there, top first: %s)" % (
                tab, (target["l"] + target["r"]) // 2, (target["t"] + target["b"]) // 2,
                input_windows_at((target["l"] + target["r"]) // 2, (target["t"] + target["b"]) // 2) or "(not parseable)"))
            window_report("when the tap did not navigate")
            lab = find_label(nodes, tab)
            if lab is not None:
                adb("shell", "input", "tap", str((lab["l"] + lab["r"]) // 2), str(lab["t"] - 70))
                time.sleep(8 if tab == "AR Scan" else 3)
                after = dump("x_" + tab.lower().replace(" ", "_") + "_retry")
        rows = page_rows(after or [], 7 if tab == "AR Scan" else 4)
        changed = after is not None and page_sig(after) != before
        say("  opened %-9s -> %d text nodes on screen; page content differs from before the tap: %s" % (
            tab, len([n for n in app_nodes(after or []) if label(n)]), changed))
        for row in rows:
            say(row)
        if tab == "AR Scan":
            camera_evidence()
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
    window_report("right after launch")
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
    window_report("after visiting Insights and Health")
    view_roots()
    exercise(width, height, dpi)
    window_report("at the end")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # report only
        say("smoke_ui crashed: %r" % (exc,))
    sys.exit(0)
