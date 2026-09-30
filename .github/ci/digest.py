#!/usr/bin/env python3
"""Turn a build log / JUnit XML into GitHub workflow annotations.   (TEMPORARY - removed with the workflow)

Why this exists: the authoring sandbox can reach the GitHub REST API but NOT the blob storage that
serves raw job logs and artifacts, so check-run annotations are the only channel through which CI
output can be read back there.

usage:
  digest.py step  <id> <mode> <exit_code> <logfile>
        mode = gradle : error digest when exit_code != 0, short notice otherwise
        mode = full   : the whole log, chunked, as notices (errors when exit_code != 0)
  digest.py junit <title> <results-dir>...
  digest.py lint  <lint-results.xml>
  digest.py smoke <title> <smoke-out-dir>
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

CHUNK = 3400      # characters per annotation message
MAX_CHUNKS = 9    # GitHub shows at most 10 annotations of each level per step


def esc_data(s):
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def esc_prop(s):
    return esc_data(s).replace(":", "%3A").replace(",", "%2C")


def emit(level, title, text):
    chunks, cur, size = [], [], 0
    for line in text.splitlines():
        line = line[:380]
        if cur and size + len(line) + 1 > CHUNK:
            chunks.append("\n".join(cur))
            cur, size = [], 0
        cur.append(line)
        size += len(line) + 1
    if cur:
        chunks.append("\n".join(cur))
    if not chunks:
        chunks = ["(empty)"]
    if len(chunks) > MAX_CHUNKS:
        dropped = len(chunks) - (MAX_CHUNKS - 1)
        chunks = chunks[: MAX_CHUNKS - 1] + ["... %d more chunk(s) omitted" % dropped]
    for i, chunk in enumerate(chunks, 1):
        t = title if len(chunks) == 1 else "%s [%d/%d]" % (title, i, len(chunks))
        print("::%s title=%s::%s" % (level, esc_prop(t), esc_data(chunk)))
    sys.stdout.flush()


def read(path):
    try:
        with open(path, "r", errors="replace") as fh:
            return fh.read()
    except OSError as exc:
        return "(could not read %s: %s)" % (path, exc)


def gradle_failure_digest(text):
    lines = text.splitlines()
    parts = []

    m = re.search(r"\* What went wrong:\n(.*?)(?:\n\* Try:|\n\* Exception is:|\Z)", text, re.S)
    if m:
        parts.append("WHAT WENT WRONG:\n" + m.group(1).strip())

    errs = [l for l in lines if l.startswith("e: ") or re.search(r"\berror:", l)]
    if errs:
        parts.append("COMPILER ERRORS (%d):\n%s" % (len(errs), "\n".join(errs[:30])))

    failed_tasks = [l for l in lines if re.match(r"> Task \S+ FAILED", l)]
    if failed_tasks:
        parts.append("FAILED TASKS:\n" + "\n".join(failed_tasks))

    failed_tests = []
    for i, l in enumerate(lines):
        if l.rstrip().endswith(" FAILED") and not l.startswith("> Task"):
            failed_tests.append("\n".join(lines[i:i + 7]))
    if failed_tests:
        parts.append("FAILED TESTS (%d):\n%s" % (len(failed_tests), "\n\n".join(failed_tests[:8])))

    caused = [l.strip() for l in lines if l.lstrip().startswith("Caused by:")]
    if caused:
        seen, uniq = set(), []
        for c in caused:
            if c not in seen:
                seen.add(c)
                uniq.append(c)
        parts.append("CAUSED BY:\n" + "\n".join(uniq[:8]))

    parts.append("LAST 25 LINES:\n" + "\n".join(lines[-25:]))
    return "\n\n".join(parts)


def own_code_warnings(text):
    """Kotlin/Java warnings that point at the files this change touched (deprecations etc.)."""
    mine = ("EdgeToEdge.kt", "NativeAdCard.kt", "MainActivity.kt", "AdUnitConfigTest.kt")
    ws = [re.sub(r"^w: file://\S*/app/src/", "w: ", l) for l in text.splitlines()
          if l.startswith("w: ") and any(f in l for f in mine)]
    return ws


def step(step_id, mode, rc, path):
    text = read(path)
    rc = int(rc)
    if mode == "full":
        emit("error" if rc else "notice", "%s (exit %d)" % (step_id, rc), text)
        return
    # mode == gradle
    if rc:
        emit("error", "%s FAILED (exit %d)" % (step_id, rc), gradle_failure_digest(text))
    else:
        tail = "\n".join(text.splitlines()[-8:])
        emit("notice", "%s OK" % step_id, tail)
    ws = own_code_warnings(text)
    if ws:
        emit("warning", "%s: compiler warnings in the touched files (%d)" % (step_id, len(ws)),
             "\n".join(ws[:30]))


def junit(title, dirs):
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    details, suites = [], []
    files = []
    for d in dirs:
        files += sorted(glob.glob(os.path.join(d, "*.xml")))
    if not files:
        emit("warning", "%s: no JUnit XML found in %s" % (title, ", ".join(dirs)),
             "(the test task probably did not run - see the step's own annotation)")
        return
    for f in files:
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as exc:
            details.append("unparseable %s: %s" % (f, exc))
            continue
        suite = root if root.tag == "testsuite" else root.find("testsuite")
        if suite is None:
            continue
        for k in totals:
            totals[k] += int(suite.get(k, 0) or 0)
        suites.append("%s: tests=%s failures=%s errors=%s skipped=%s" % (
            suite.get("name"), suite.get("tests"), suite.get("failures"),
            suite.get("errors"), suite.get("skipped")))
        for case in suite.findall("testcase"):
            bad = case.find("failure")
            if bad is None:
                bad = case.find("error")
            if bad is not None:
                msg = (bad.get("message") or "").strip().replace("\n", " ")[:500]
                body = "\n".join((bad.text or "").strip().splitlines()[:9])
                details.append("FAIL %s > %s\n  %s\n%s" % (case.get("classname"), case.get("name"), msg, body))
    bad_total = totals["failures"] + totals["errors"]
    head = "TOTAL tests=%(tests)d failures=%(failures)d errors=%(errors)d skipped=%(skipped)d" % totals
    text = head + "\n" + "\n".join(suites)
    if details:
        text += "\n\n" + "\n\n".join(details[:10])
    emit("error" if bad_total else "notice", "%s JUnit results" % title, text)


def lint(xml_path):
    if not os.path.exists(xml_path):
        emit("warning", "lint: no report found", "%s does not exist (lint task did not get that far)" % xml_path)
        return
    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError as exc:
        emit("warning", "lint: unparseable report", repr(exc))
        return
    issues = root.findall("issue")
    by = {}
    for i in issues:
        by.setdefault((i.get("id"), i.get("severity")), []).append(i)
    rows = ["%-28s %-8s x%d" % (k[0], k[1], len(v))
            for k, v in sorted(by.items(), key=lambda kv: (-len(kv[1]), kv[0][0] or ""))]
    emit("notice", "lint: %d issue(s) by id" % len(issues), "\n".join(rows) or "(no issues)")
    interesting = re.compile(r"(?i)outdated|risky|edge|deprecat|fragment|newapi|inlinedapi|sdkindex|"
                             r"insets|window|gradledependency|oldtarget|expiring|statusbar|navigationbar")
    sel = [i for i in issues if i.get("severity") in ("Error", "Fatal") or interesting.search(i.get("id") or "")]
    lines = []
    for i in sel:
        loc = i.find("location")
        where = "?"
        if loc is not None:
            where = "%s:%s" % (os.path.basename(loc.get("file") or "?"), loc.get("line") or "?")
        lines.append("%s [%s] %s -- %s" % (i.get("id"), i.get("severity"), where,
                                           (i.get("message") or "").replace("\n", " ")[:420]))
    if lines:
        emit("warning", "lint: findings relevant to this change (%d)" % len(lines), "\n".join(lines[:60]))
    else:
        emit("notice", "lint: nothing relevant to edge-to-edge / outdated SDKs / NewApi",
             "no Error/Fatal issues and no issue whose id matches: " + interesting.pattern)


def smoke(title, out):
    def rd(name):
        return read(os.path.join(out, name)) if os.path.exists(os.path.join(out, name)) else ""

    env = rd("env.txt")
    emit("notice", "%s: device + launch" % title,
         env + "\n" + rd("install.txt").strip() + "\n" + rd("launch.txt").strip())

    logcat = rd("logcat.txt").splitlines()
    crash_pat = re.compile(r"FATAL EXCEPTION|AndroidRuntime: |ANR in |Process com\.goshbuzz\.emfsentinel .*has died|"
                           r"Fatal signal|am_crash|am_anr")
    crash_idx = [i for i, l in enumerate(logcat) if crash_pat.search(l)]
    if crash_idx:
        blocks = []
        for i in crash_idx[:3]:
            blocks.append("\n".join(logcat[max(0, i - 2): i + 18]))
        emit("error", "%s: CRASH / ANR in logcat (%d hit(s))" % (title, len(crash_idx)), "\n\n".join(blocks))
    else:
        emit("notice", "%s: no crash / ANR / fatal signal in logcat (%d lines)" % (title, len(logcat)), "clean")

    ads_pat = re.compile(r"NativeAdCard|AnchoredAdaptiveBanner|AdConsentManager|MobileAdsController|"
                         r"\bAds\b|GoogleMobileAds|UserMessagingPlatform|AdLoader")
    ads = [re.sub(r"^\S+\s+\S+\s+\d+\s+\d+\s+", "", l) for l in logcat if ads_pat.search(l)]
    emit("notice", "%s: ads-related logcat lines (%d)" % (title, len(ads)),
         "\n".join(ads[:70]) or "(none - the ads pipeline logged nothing)")

    ui = rd("ui_findings.txt")
    if ui:
        emit("notice", "%s: what the screen showed" % title, ui)

    win = rd("dumpsys_window.txt").splitlines()
    insets = [l.strip() for l in win if re.search(r"type=(statusBars|navigationBars|displayCutout|ITYPE_STATUS_BAR|"
                                                   r"ITYPE_NAVIGATION_BAR)|mStatusBarHeight|mNavigationBarHeight", l)]
    wins = rd("dumpsys_windows.txt").splitlines()
    appwin = [l.strip() for l in wins if re.search(r"mAttrs=|layoutInDisplayCutoutMode|cutout|Frames: ", l)
              and ("goshbuzz" in l or "MainActivity" in l or "Frames:" in l or "cutout" in l.lower())]
    emit("notice", "%s: window-inset facts (dumpsys window)" % title,
         "INSET SOURCES:\n" + "\n".join(dict.fromkeys(insets))[:1600] +
         "\n\nAPP WINDOW:\n" + "\n".join(appwin[:14]))

    hier = rd("dumpsys_activity_top.txt").splitlines()
    views = [l.strip() for l in hier if re.search(r"NativeAdView|MediaView|AdChoices|nativead|AndroidComposeView", l)]
    emit("notice", "%s: real view classes (dumpsys activity top)" % title,
         "\n".join(views[:30]) or "(no NativeAdView / MediaView in the hierarchy)")


def main(argv):
    if len(argv) >= 6 and argv[1] == "step":
        step(argv[2], argv[3], argv[4], argv[5])
    elif len(argv) >= 4 and argv[1] == "junit":
        junit(argv[2], argv[3:])
    elif len(argv) >= 3 and argv[1] == "lint":
        lint(argv[2])
    elif len(argv) >= 4 and argv[1] == "smoke":
        smoke(argv[2], argv[3])
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception as exc:  # never let the reporter break the build
        print("::warning title=digest.py crashed::%s" % esc_data(repr(exc)))
        sys.exit(0)
