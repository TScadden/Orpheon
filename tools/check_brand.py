#!/usr/bin/env python3
"""Brand gate: the display name is Scadify, and "Myspoti" must not come back
as display text in the shipped apps.

Tyson's standing rule (since the v2 rename): the name reads Scadify everywhere
it is mentioned in the app. String resources were swept in v2, but hardcoded
Kotlin literals survived three times (the v1 brand_name, the v10 About header,
the v16 update dialog's "Myspoti $version"). This gate exists so a fourth
cannot ship: CI runs it on every build and it fails the build on any hit.

What is scanned (the surfaces a user can see):
  * Kotlin string literals in the main sources of :app, :wear, :host-android
    and :extensions (the extensions API module carries display text: action
    descriptions, validation and error messages shown in the app). Comments
    are stripped before scanning — documentation may still name the origin;
    only literals that ship as text are judged.
  * Text values in res/ XML and AndroidManifest.xml of :app (main + debug
    source sets), :wear and :host-android — string names and URLs are
    stripped first, so resource names and origin links are not display text.

What is NOT a violation (the allowlist below, one entry per functional use):
"Myspoti" survives legitimately as code identifiers (packages app.myspoti.*,
MyspotiRuntime, ... — never matched here, they are not string literals and
carry no word boundary), in GPL/origin comments (stripped with the other
comments), and in the functional literals allowlisted below. Each allowlist
entry names its reason; a new display use matches none of them and fails.

Run from the repository root:  python3 tools/check_brand.py
Exit 0 = clean, 1 = violations listed on stdout.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

KT_ROOTS = [
    "base/app/src/main/java",
    "base/wear/src/main/kotlin",
    "base/host-android/src/main/kotlin",
    "base/extensions/src/main/kotlin",
]

XML_ROOTS = [
    "base/app/src/main/res",
    "base/app/src/debug/res",
    "base/app/src/main/AndroidManifest.xml",
    "base/app/src/debug/AndroidManifest.xml",
    "base/wear/src/main/res",
    "base/wear/src/main/AndroidManifest.xml",
    "base/host-android/src/main/AndroidManifest.xml",
]

WORD = re.compile(r"\bMyspoti\b")
LITERALS = re.compile(r'"""(.*?)"""|"((?:[^"\\]|\\.)*)"', re.DOTALL)

# Allowlisted functional literals. Each pattern is matched against the source
# line the literal starts on; each entry states why it is not display text.
ALLOWLIST = [
    # Log tags: BetaLogs collects this app's logcat lines by tag, and the Web
    # Player bridge logs under the "Myspoti" tag; renaming a tag would break
    # log collection and the bridge's line matching. Tags are never rendered
    # as the app's name in any UI.
    (re.compile(r'Log\.[a-z]+\("Myspoti"'), "android.util.Log tag"),
    (re.compile(r'const val LOG = "Myspoti"'), "Logbook log tag constant"),
    (re.compile(r'"Myspoti", "MspPlayer"'), "BetaLogs tag list (functional filter)"),
    (re.compile(r'" Myspoti : status "'), "BetaLogs matcher for the Web Player bridge's own log lines"),
    # The media session's tag is an internal identifier; the name a user sees
    # comes from the session metadata / app label, never from this tag.
    (re.compile(r'MediaSessionCompat\(this, "Myspoti"\)'), "media session tag (internal id)"),
    # Wake/wifi lock tags are internal identifiers shown nowhere.
    (re.compile(r'"Myspoti:playback"'), "wake/wifi lock tag (internal id)"),
    # HTTP User-Agent headers: protocol identifiers sent to servers, never
    # rendered in the app. (The Cast CONNECT userAgent is deliberately NOT
    # allowlisted: a Cast receiver can surface the sender's userAgent, so it
    # carries the display name like any other mention.)
    (re.compile(r'"Myspoti \(https://github\.com/\)"'), "LyricsApi HTTP User-Agent"),
    (re.compile(r'"Myspoti-Extensions/1"'), "extension update HTTP User-Agent"),
    (re.compile(r'"Myspoti-Extension-Page/1"'), "extension page HTTP User-Agent"),
    (re.compile(r'"Myspoti-Extension/\$API_VERSION'), "extensions Host HTTP User-Agent"),
    (re.compile(r'"Myspoti/1\.0 \(Live Radio module'), "RadioBrowser HTTP User-Agent"),
]


def allowlisted(line: str) -> bool:
    return any(p.search(line) for p, _why in ALLOWLIST)


def strip_comments(text: str) -> str:
    """Blank out // and /* */ comments (Kotlin block comments nest), keeping
    string/char/raw-string contents and every newline, so what remains is the
    code whose literals actually ship."""
    out: list[str] = []
    i, n = 0, len(text)
    block_depth = 0
    while i < n:
        if block_depth:
            if text.startswith("/*", i):
                block_depth += 1
                out.append("  ")
                i += 2
            elif text.startswith("*/", i):
                block_depth -= 1
                out.append("  ")
                i += 2
            else:
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            continue
        if text.startswith("//", i):
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if text.startswith("/*", i):
            block_depth = 1
            out.append("  ")
            i += 2
            continue
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(text[i:end])
            i = end
            continue
        if text[i] in "\"'":
            quote = text[i]
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == quote or text[j] == "\n":
                    break
                j += 1
            j = min(j + 1, n)
            out.append(text[i:j])
            i = j
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


def scan_kt(path: Path, violations: list[str]) -> None:
    original = path.read_text(encoding="utf-8", errors="replace")
    code = strip_comments(original)
    lines = code.splitlines()
    for m in LITERALS.finditer(code):
        literal = m.group(1) if m.group(1) is not None else m.group(2)
        if literal and WORD.search(literal):
            lineno = code.count("\n", 0, m.start()) + 1
            line = lines[lineno - 1] if lineno - 1 < len(lines) else ""
            if not allowlisted(line):
                shown = literal.replace("\n", " ")[:80]
                violations.append(f"{path.relative_to(ROOT)}:{lineno}: Kotlin literal \"{shown}\"")


def scan_xml(path: Path, violations: list[str]) -> None:
    for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        # String/resource names and URLs are identifiers and links, not display text.
        stripped = re.sub(r'name="[^"]*"', "", line)
        stripped = re.sub(r"https?://\S+", "", stripped)
        if WORD.search(stripped):
            violations.append(f"{path.relative_to(ROOT)}:{lineno}: XML text \"{line.strip()[:90]}\"")


def main() -> int:
    violations: list[str] = []
    for rel in KT_ROOTS:
        root = ROOT / rel
        if root.is_dir():
            for path in sorted(root.rglob("*.kt")):
                if "build" not in path.parts:
                    scan_kt(path, violations)
    for rel in XML_ROOTS:
        root = ROOT / rel
        paths = [root] if root.is_file() else sorted(root.rglob("*.xml")) if root.is_dir() else []
        for path in paths:
            if "build" not in path.parts:
                scan_xml(path, violations)
    if violations:
        print("Brand gate FAILED: \"Myspoti\" appears as display text (the display name is Scadify):")
        for v in violations:
            print(f"  {v}")
        print("If a hit is a functional identifier, not display text, allowlist it in "
              "tools/check_brand.py with its reason.")
        return 1
    print("Brand gate passed: no \"Myspoti\" display literals in the scanned UI surfaces.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
