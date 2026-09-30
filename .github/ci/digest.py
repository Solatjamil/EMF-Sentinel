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


def emit(level, title, text, max_chunks=MAX_CHUNKS):
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
    if len(chunks) > max_chunks:
        dropped = len(chunks) - (max_chunks - 1)
        chunks = chunks[: max_chunks - 1] + ["... %d more chunk(s) omitted" % dropped]
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
    mine = ("NativeAdCard.kt", "EdgeToEdge.kt", "AdUnitConfigTest.kt", "themes.xml", "proguard-rules.pro")
    own = []
    for i in issues:
        for loc in i.findall("location"):
            base = os.path.basename(loc.get("file") or "")
            if base in mine:
                own.append("%s [%s] %s:%s -- %s" % (i.get("id"), i.get("severity"), base, loc.get("line") or "?",
                                                    (i.get("message") or "").replace("\n", " ")[:300]))
                break
    emit("warning" if own else "notice", "lint: findings INSIDE the files added/changed by this work (%d)" % len(own),
         "\n".join(own) or "none")
    if lines:
        emit("warning", "lint: findings relevant to this change (%d)" % len(lines), "\n".join(lines[:60]))
    else:
        emit("notice", "lint: nothing relevant to edge-to-edge / outdated SDKs / NewApi",
             "no Error/Fatal issues and no issue whose id matches: " + interesting.pattern)


def smoke(title, out):
    """One emulator case (release or debug). Sections are merged into few annotations because
    GitHub keeps at most 10 annotations per level and step."""
    def rd(name):
        path = os.path.join(out, name)
        return read(path) if os.path.exists(path) else ""

    env = rd("env.txt").strip()
    exit_info = [l.rstrip() for l in rd("exit_info.txt").splitlines() if l.strip()]
    events = [l.strip() for l in rd("events.txt").splitlines() if l.strip()]
    summary = env
    summary += "\n\n-- install: " + " ".join(rd("install.txt").split())[:160]
    summary += "\n-- launch: " + " ".join(rd("launch.txt").split())[:200]
    summary += "\n\n-- ApplicationExitInfo (dumpsys activity exit-info):\n" + "\n".join(exit_info[:26])
    summary += "\n\n-- events buffer, lines about the app:\n" + "\n".join(events[:24])
    emit("notice", "%s: device, launch, process lifetime, exit reasons" % title, summary, max_chunks=2)

    # real crash evidence only. The crash buffer also collects crashes of OTHER processes (the uiautomator
    # tool, system apps); only blocks that name the app or carry its pid are the app's.
    crash = [l for l in rd("crash.txt").splitlines() if l.strip()]
    logcat = rd("logcat.txt").splitlines()
    pids = set(re.findall(r"pid_at_\w+=(\d+)", env))
    crash_body = [l for l in crash if not l.startswith("---------")]

    def names_app(ctx):
        return "goshbuzz" in ctx or any(re.search(r"(?:PID: |pid |tid )%s\b" % pid, ctx) for pid in pids)

    fatal_idx = [i for i, l in enumerate(crash_body) if "FATAL EXCEPTION" in l or "Fatal signal" in l]

    def block(i):       # the header plus the next lines, but never into the following crash block
        later = [j for j in fatal_idx if j > i]
        return crash_body[i: min(i + 4, later[0] if later else i + 4)]

    app_fatal = [i for i in fatal_idx if names_app(" ".join(block(i)))]
    other_fatal = [i for i in fatal_idx if i not in app_fatal]
    app_lines = [l for l in crash_body if "goshbuzz" in l]
    main_hits = [i for i, l in enumerate(logcat)
                 if re.search(r"Process: com\.goshbuzz|ANR in com\.goshbuzz|Force finishing activity com\.goshbuzz|"
                              r"Process com\.goshbuzz\S* \(pid \d+\) has died", l)
                 or ("FATAL EXCEPTION" in l and "goshbuzz" in " ".join(logcat[i:i + 4]))]
    if app_fatal or app_lines or main_hits:
        first = crash_body[app_fatal[0]:][:70] if app_fatal else crash_body[:70]
        text = "APP CRASH BLOCKS in the crash buffer: %d\n%s" % (len(app_fatal), "\n".join(first))
        if main_hits:
            i = main_hits[0]
            text += "\n\nMAIN LOG around the first hit:\n" + "\n".join(logcat[max(0, i - 4): i + 22])
        emit("error", "%s: CRASH evidence" % title, text)
    elif other_fatal:
        heads = [crash_body[i].split(": ", 1)[-1][:80] for i in other_fatal]
        emit("notice", "%s: no crash of the app (crash buffer has %d block(s) from OTHER processes)" % (title, len(other_fatal)),
             "app pids %s do not appear; the blocks are: %s" % (sorted(pids) or "?", "; ".join(heads)))
    else:
        emit("notice", "%s: crash buffer is empty and no fatal/ANR line for the app in the main log (%d lines)" % (title, len(logcat)), "clean")

    ads_pat = re.compile(r"NativeAdCard|AnchoredAdaptiveBanner|AdConsentManager|MobileAdsController|"
                         r"\bAds\b|GoogleMobileAds|UserMessagingPlatform|AdLoader")
    ads = [re.sub(r"^\S+\s+\S+\s+\d+\s+\d+\s+", "", l) for l in logcat
           if ads_pat.search(l) and "AndroidRuntime" not in l]
    # consent-storage chatter and JS bridge noise hide the lines that matter; one chunk keeps the
    # per-step annotation budget (10) intact now that the UI walk-through report is longer
    noise = re.compile(r"Stored info not exists|Writing to storage|Action\[|Receive consent action|"
                       r"jsLoaded GMSG|Refused to get unsafe header")
    ads = [l for l in ads if not noise.search(l)]
    emit("notice", "%s: ads-related logcat lines (%d, consent/JS chatter filtered)" % (title, len(ads)),
         "\n".join(ads[:60]) or "(none - the ads pipeline logged nothing)", max_chunks=1)

    ui = rd("ui_findings.txt")
    if ui:
        emit("notice", "%s: what the screen showed" % title, ui, max_chunks=6)


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
