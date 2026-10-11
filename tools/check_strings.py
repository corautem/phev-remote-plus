#!/usr/bin/env python3
"""Fail fast on Android string-resource problems that otherwise only surface as a cryptic
`mergeReleaseResources` error deep in a CI build:

  1. Duplicate keys within a language (across strings.xml, strings_help.xml, … in one values* dir).
     This is what breaks the Android resource merger.
  2. Key parity: every key present in the default `values/` must exist in each translation, and
     vice versa — so no language silently misses (or carries an extra) string.

Exit non-zero and print exactly what's wrong. Run from the repo root:  python3 tools/check_strings.py
"""
import glob
import os
import re
import sys

RES = "app/src/main/res"
NAME = re.compile(r'<(?:string|plurals) name="([^"]+)"')


def keys_per_file(values_dir):
    """Return {key: [files it appears in]} for one values* directory."""
    seen = {}
    for path in sorted(glob.glob(os.path.join(values_dir, "*.xml"))):
        with open(path, encoding="utf-8") as fh:
            for key in NAME.findall(fh.read()):
                seen.setdefault(key, []).append(os.path.basename(path))
    return seen


def main():
    if not os.path.isdir(RES):
        print(f"check_strings: {RES} not found — run from the repo root", file=sys.stderr)
        return 2

    # Only directories that actually hold string files are translations; skip config qualifiers
    # such as values-night (theme colours) that legitimately carry no strings.
    dirs = sorted(
        d for d in glob.glob(os.path.join(RES, "values")) + glob.glob(os.path.join(RES, "values-*"))
        if glob.glob(os.path.join(d, "strings*.xml"))
    )
    problems = []
    per_dir_keys = {}

    for d in dirs:
        seen = keys_per_file(d)
        per_dir_keys[d] = set(seen)
        for key, files in seen.items():
            if len(files) > 1:
                problems.append(f"DUPLICATE  {os.path.basename(d)}: '{key}' defined in {', '.join(files)}")

    base = os.path.join(RES, "values")
    if base in per_dir_keys:
        base_keys = per_dir_keys[base]
        for d in dirs:
            if d == base:
                continue
            missing = base_keys - per_dir_keys[d]
            extra = per_dir_keys[d] - base_keys
            for k in sorted(missing):
                problems.append(f"MISSING    {os.path.basename(d)}: '{k}' (in values/, not here)")
            for k in sorted(extra):
                problems.append(f"EXTRA      {os.path.basename(d)}: '{k}' (here, not in values/)")

    if problems:
        print("String resource check FAILED:\n")
        for p in problems:
            print("  " + p)
        print(f"\n{len(problems)} problem(s).")
        return 1

    total = len(per_dir_keys.get(base, []))
    print(f"String resource check OK — {total} keys, {len(dirs)} languages, no duplicates.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
