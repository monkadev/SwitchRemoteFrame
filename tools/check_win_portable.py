#!/usr/bin/env python3
"""Check that the PC viewer's sources stay portable to the Windows build.

The Windows viewer is built with mingw, which has no POSIX libc, so a call like
getline() is a hard error there even though it is fine on Linux and macOS. This
walks the preprocessor conditionals so a call inside `#ifndef _WIN32` is not
reported - only one the Windows build would actually compile.

It is a source-level check, not a cross-compile: the viewer needs SDL and FFmpeg
headers this script does not have. What it does catch is the class of bug that
broke the Windows build in v1.0.2 (getline in a plain function).
"""
import os
import re
import sys

# calls that exist in glibc / Apple's libc but not in mingw's
POSIX_ONLY = {
    "getline", "asprintf", "vasprintf", "strndup", "strsep", "mkdtemp",
    "mkstemps", "realpath", "wordexp", "getifaddrs", "openpty", "forkpty",
    "getdelim", "strverscmp", "memrchr", "rawmemchr",
}

CALL = re.compile(r"\b(" + "|".join(sorted(POSIX_ONLY)) + r")\s*\(")
IF = re.compile(r"^\s*#\s*(ifdef|ifndef|if|elif|else|endif)\s*(.*)$")


def is_windows(cond):
    """Whether a condition is, after normalising, exactly `_WIN32`."""
    c = re.sub(r"defined\s*\(\s*_WIN32\s*\)", "_WIN32", cond)
    return c.strip() == "_WIN32"


def windows_reachable(stack):
    """Whether code under these conditions is compiled when _WIN32 is defined.

    Each entry is (condition in force, the #if's own condition, saw a plain
    #else). The two facts that settle it:

      - `#ifndef _WIN32`, `#if !defined(_WIN32)`, `#ifdef __linux__` and the
        like are false on Windows, so their branch never reaches the build;
      - a plain `#else` on `#ifdef _WIN32` is the *not*-Windows branch. (An
        `#elif` is not: it may still be true on Windows, so it stays reachable.)

    Anything we cannot decide is treated as reachable, so the check reports
    rather than misses.
    """
    for cond, ifcond, saw_else in stack:
        c = cond
        if is_windows(c):
            continue                                  # #ifdef _WIN32 / #if _WIN32
        if c.startswith("!") and is_windows(c.lstrip("!").strip("() ")):
            return False                              # #ifndef _WIN32, or its #else
        if saw_else and is_windows(ifcond):
            return False                              # #else of #ifdef _WIN32
        if re.search(r"\b__linux__\b|\b__APPLE__\b|\b__unix__\b", c):
            return False                              # another OS's branch
    return True


def check(path):
    hits = []
    stack = []        # [condition in force, the #if's condition, saw a plain #else]
    with open(path, encoding="utf-8", errors="replace") as f:
        for lineno, line in enumerate(f, 1):
            m = IF.match(line)
            if m:
                kw, rest = m.group(1), m.group(2).strip()
                if kw in ("if", "ifdef", "ifndef"):
                    # `#ifndef X` means the same as `#if !defined(X)`
                    cond = "!" + rest if kw == "ifndef" else rest
                    stack.append([cond, cond, False])
                elif kw == "elif":
                    if stack:
                        stack[-1] = [rest, stack[-1][1], False]
                elif kw == "else":
                    if stack:
                        stack[-1][2] = True
                        stack[-1][0] = "!(%s)" % stack[-1][1]
                elif kw == "endif":
                    if stack:
                        stack.pop()
                continue
            if line.lstrip().startswith(("*", "//")):
                continue
            for call in CALL.finditer(line):
                if windows_reachable(stack):
                    hits.append((lineno, call.group(1), line.strip()[:100]))
    return hits


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "raw-recv")
    files = [os.path.join(root, "raw-view.c")] + sorted(
        os.path.join(root, n) for n in os.listdir(root) if n.endswith(".h")
    )
    bad = []
    for path in files:
        for lineno, name, text in check(path):
            bad.append("%s:%d: %s() - %s" % (os.path.basename(path), lineno, name, text))
    if bad:
        print("POSIX-only calls the Windows (mingw) build would have to compile:")
        print("\n".join(bad))
        return 1
    print("portable: no POSIX-only call reaches the Windows build "
          "(%d files)" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
