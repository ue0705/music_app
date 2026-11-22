#!/usr/bin/env python3
"""Check relative links and #anchors in the repo's Markdown files.

Anchors are computed the way GitHub does (github-slugger): lower-case, drop
everything that is not a letter, digit, '_', '-' or space (this removes
full-width punctuation such as （）：), then spaces -> '-'. Duplicate headings get
-1, -2, ... suffixes. Links inside fenced code blocks are ignored.

    python3 scripts/check-doc-links.py        # exit 1 if anything is broken
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SKIP_DIRS = {".git", ".gradle", "build", "node_modules", ".idea"}
LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")


def strip_fences(text: str) -> list[str]:
    out, fenced = [], False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            fenced = not fenced
            out.append("")
            continue
        out.append("" if fenced else line)
    return out


def slug(title: str) -> str:
    title = re.sub(r"`([^`]*)`", r"\1", title)                 # inline code -> text
    title = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", title)     # links -> text
    s = title.strip().lower()
    s = re.sub(r"[^\w\- ]", "", s)
    return s.replace(" ", "-")


def anchors(path: str, cache: dict) -> set:
    if path not in cache:
        seen, result = {}, set()
        with open(path, encoding="utf-8") as f:
            for line in strip_fences(f.read()):
                m = HEADING.match(line)
                if not m:
                    continue
                base = slug(m.group(2))
                n = seen.get(base, 0)
                result.add(base if n == 0 else f"{base}-{n}")
                seen[base] = n + 1
        cache[path] = result
    return cache[path]


def main() -> int:
    cache, broken, checked = {}, [], 0
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if not name.endswith(".md"):
                continue
            src = os.path.join(dirpath, name)
            with open(src, encoding="utf-8") as f:
                lines = strip_fences(f.read())
            for lineno, line in enumerate(lines, 1):
                for target in LINK.findall(line):
                    if re.match(r"^[a-z][a-z0-9+.-]*:", target):   # http:, mailto:, ...
                        continue
                    checked += 1
                    file_part, _, anchor = target.partition("#")
                    dest = os.path.normpath(os.path.join(dirpath, file_part)) if file_part else src
                    rel = os.path.relpath(src, ROOT)
                    if not os.path.exists(dest):
                        broken.append(f"{rel}:{lineno}: missing file -> {target}")
                    elif anchor and dest.endswith(".md") and anchor not in anchors(dest, cache):
                        broken.append(f"{rel}:{lineno}: missing anchor -> {target}")
    for b in broken:
        print(b)
    print(f"{checked} links checked, {len(broken)} broken")
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main())
