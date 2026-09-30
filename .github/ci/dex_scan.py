#!/usr/bin/env python3
"""TEMPORARY verification helper (removed after the check).

Scans an APK's DEX files for references to the window APIs that Android 15 deprecated and that
Google Play Console reports under "Your app uses deprecated APIs or parameters for edge-to-edge":

  Window.setStatusBarColor / getStatusBarColor
  Window.setNavigationBarColor / getNavigationBarColor
  Window.setNavigationBarDividerColor / getNavigationBarDividerColor
  WindowManager.LayoutParams.layoutInDisplayCutoutMode writes of DEFAULT(0) / SHORT_EDGES(1) / NEVER(2)

For R8-obfuscated builds pass the mapping.txt so locations are printed with their original names.

usage: dex_scan.py <label> <apk> [mapping.txt]
"""
import collections
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

FLAGGED_WINDOW_CALLS = {
    "setStatusBarColor", "getStatusBarColor",
    "setNavigationBarColor", "getNavigationBarColor",
    "setNavigationBarDividerColor", "getNavigationBarDividerColor",
}
INFO_WINDOW_CALLS = {"setDecorFitsSystemWindows"}
CUTOUT_NAMES = {0: "DEFAULT", 1: "SHORT_EDGES", 2: "NEVER", 3: "ALWAYS"}

# "|[0012a0] com.example.Foo.bar:()V"  -> start of a method body in `dexdump -d`
RE_CODE_HEADER = re.compile(r"\|\[[0-9a-f]+\] ([^:\s]+):\(")
RE_INVOKE = re.compile(r"invoke-\S+ \{[^}]*\}, (L[^;]+;)\.([\w$<>]+):")
RE_CONST = re.compile(r"\|[0-9a-f]+: const\S* (v\d+), #int (-?\d+)")
RE_IPUT_CUTOUT = re.compile(
    r"\|[0-9a-f]+: iput\S* (v\d+), v\d+, Landroid/view/WindowManager\$LayoutParams;\.layoutInDisplayCutoutMode:I"
)


def scan_lines(lines):
    """Yield (kind, qualified_method, detail) for every interesting instruction."""
    current = None
    regs = {}
    for line in lines:
        header = RE_CODE_HEADER.search(line)
        if header:
            current = header.group(1)
            regs = {}
            continue
        if current is None:
            continue
        if "invoke-" in line and "Landroid/view/Window;." in line:
            m = RE_INVOKE.search(line)
            if m and m.group(1) == "Landroid/view/Window;":
                name = m.group(2)
                if name in FLAGGED_WINDOW_CALLS:
                    yield ("Window." + name, current, "")
                elif name in INFO_WINDOW_CALLS:
                    yield ("info:Window." + name, current, "")
            continue
        if "layoutInDisplayCutoutMode" in line and "iput" in line:
            m = RE_IPUT_CUTOUT.search(line)
            if m:
                value = regs.get(m.group(1))
                label = CUTOUT_NAMES.get(value, "unresolved(param)" if value is None else "value=%s" % value)
                yield ("cutoutMode=" + label, current, "")
            continue
        if " const" in line:
            m = RE_CONST.search(line)
            if m:
                regs[m.group(1)] = int(m.group(2))


def load_mapping(path):
    class_map = {}
    method_map = collections.defaultdict(set)
    current = None
    with open(path, errors="replace") as handle:
        for line in handle:
            if not line.strip() or line.startswith("#"):
                continue
            if not line.startswith((" ", "\t")):
                m = re.match(r"^(\S+) -> (\S+):\s*$", line)
                if m:
                    original, obfuscated = m.groups()
                    class_map[obfuscated] = original
                    current = obfuscated
                else:
                    current = None
            elif current:
                m = re.match(r"^\s+(?:\d+:\d+:)?\S+ (\S+)\(.*?\)(?::\d+(?::\d+)?)? -> (\S+)\s*$", line)
                if m:
                    method_map[(current, m.group(2))].add(m.group(1))
    return class_map, method_map


def deobfuscate(qualified, class_map, method_map):
    if not class_map:
        return qualified
    cls, _, meth = qualified.rpartition(".")
    original_cls = class_map.get(cls, cls)
    originals = sorted(method_map.get((cls, meth), {meth}))
    shown = ",".join(originals[:3]) + ("…" if len(originals) > 3 else "")
    return "%s.%s" % (original_cls, shown)


def find_callers(dumps, qualified):
    """Methods that invoke `qualified` (dotted "pkg.Class.method"), looked up in the dexdump text."""
    cls, _, meth = qualified.rpartition(".")
    needle = ";." + meth + ":"
    pattern = re.compile(r"invoke-\S+ \{[^}]*\}, L%s;\.%s:" % (re.escape(cls.replace(".", "/")), re.escape(meth)))
    found = set()
    for lines in dumps:
        current = None
        for line in lines:
            header = RE_CODE_HEADER.search(line)
            if header:
                current = header.group(1)
                continue
            if current and needle in line and "invoke-" in line and pattern.search(line):
                found.add(current)
    return found


def resolve_origin(qualified, dumps, class_map, method_map, depth=0):
    """R8 moves API-level-dependent calls into synthetic '...$$ExternalSyntheticApiModelOutline' methods.
    Return the real (non-synthetic) methods that reach `qualified`, following chains of outlines."""
    shown = deobfuscate(qualified, class_map, method_map)
    if "ExternalSynthetic" not in shown or depth >= 3:
        return {shown}
    origins = set()
    callers = find_callers(dumps, qualified)
    if not callers:
        return {shown + " (no caller found)"}
    for caller in callers:
        origins |= resolve_origin(caller, dumps, class_map, method_map, depth + 1)
    return origins


def find_dexdump():
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    candidates = glob.glob(os.path.join(home, "build-tools", "*", "dexdump"))
    if not candidates:
        sys.exit("dexdump not found under %s/build-tools" % home)

    def version_key(path):
        return tuple(int(x) for x in re.findall(r"\d+", os.path.basename(os.path.dirname(path))))

    return sorted(candidates, key=version_key)[-1]


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    label, apk = sys.argv[1], sys.argv[2]
    mapping_path = sys.argv[3] if len(sys.argv) > 3 else None
    class_map, method_map = load_mapping(mapping_path) if mapping_path else ({}, {})

    dexdump = find_dexdump()
    print("=" * 100)
    print("DEX SCAN: %s" % label)
    print("apk: %s (%.1f MB)   dexdump: %s   mapping: %s" % (
        apk, os.path.getsize(apk) / 1e6, dexdump, mapping_path or "none"))

    hits = []
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(apk) as archive:
            dex_names = sorted(n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n))
            archive.extractall(tmp, dex_names)
        print("dex files: %s" % ", ".join(dex_names))
        dumps = []
        for name in dex_names:
            proc = subprocess.Popen(
                [dexdump, "-d", os.path.join(tmp, name)],
                stdout=subprocess.PIPE, text=True, errors="replace", bufsize=1 << 20,
            )
            lines = proc.stdout.readlines()
            proc.wait()
            dumps.append(lines)
            hits.extend(scan_lines(lines))
        origins = {}
        for kind, qualified, _ in set((k, q, d) for k, q, d in hits):
            shown = deobfuscate(qualified, class_map, method_map)
            if "ExternalSynthetic" in shown:
                origins[(kind, qualified)] = resolve_origin(qualified, dumps, class_map, method_map)

    totals = collections.Counter(kind for kind, _, _ in hits)
    flagged_total = sum(v for k, v in totals.items() if not k.startswith("info:")
                        and not k.endswith("ALWAYS"))
    print("-" * 100)
    print("FLAGGED reference counts (call sites / writes):")
    if not totals:
        print("  (none at all)")
    for kind, count in sorted(totals.items()):
        print("  %-40s %4d" % (kind, count))
    print("  => Play-relevant total (excl. info + cutout ALWAYS): %d" % flagged_total)
    print("-" * 100)
    print("Locations (original names where a mapping is available):")
    per_location = collections.Counter(
        (kind, deobfuscate(qualified, class_map, method_map)) for kind, qualified, _ in hits
        if not kind.startswith("info:")
    )
    for (kind, where), count in sorted(per_location.items(), key=lambda kv: (kv[0][0], kv[0][1])):
        print("  %-34s %-90s x%d" % (kind, where, count))
    info_locations = collections.Counter(
        (kind, deobfuscate(qualified, class_map, method_map)) for kind, qualified, _ in hits
        if kind.startswith("info:")
    )
    if info_locations:
        print("-" * 100)
        print("Info-only references (not counted; setDecorFitsSystemWindows is what enableEdgeToEdge() itself calls):")
        for (kind, where), count in sorted(info_locations.items()):
            print("  %-40s %-84s x%d" % (kind, where, count))
    if origins:
        print("-" * 100)
        print("Hits inside R8 API-model outline methods -> the real code that performs them:")
        for (kind, qualified), real in sorted(origins.items()):
            print("  %s  (in %s)" % (kind, deobfuscate(qualified, class_map, method_map)))
            for r in sorted(real):
                print("        <- %s" % r)
    print("=" * 100)


if __name__ == "__main__":
    main()
