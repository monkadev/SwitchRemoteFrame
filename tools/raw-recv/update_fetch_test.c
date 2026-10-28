/*
 * update_fetch_test.c - v1.1.3: upd_fetch's curl -> wget fallback.
 *
 * The real upd_run/upd_fetch code runs, but the libc entry points that look
 * programs up are stubbed: no PATH, no /usr/bin, scripted curl and wget. That
 * makes a minimal distro (no curl, only wget) reproducible in a unit test.
 * Fork/wait stay real, so the child runs the viewer's own runner code.
 *
 * Build: see tools/run_pc_tests.sh (Linux only - the fallback is).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>

/* 0: curl missing, wget works; 1: both missing; 2: curl works */
static int g_mode;

static void stub_write_output(char *const argv[], const char *marker)
{
    for (int i = 0; argv[i]; i++) {
        if ((!strcmp(argv[i], "-o") || !strcmp(argv[i], "-O")) && argv[i + 1]) {
            FILE *f = fopen(argv[i + 1], "wb");
            if (f) { fputs(marker, f); fclose(f); }
        }
    }
}

static int stub_execvp(const char *file, char *const argv[])
{
    if (!strcmp(file, "curl")) {
        if (g_mode == 2) { stub_write_output(argv, "curl-bytes"); _exit(0); }
        errno = ENOENT;
        return -1;                    /* the child then exits 127, like a shell */
    }
    if (!strcmp(file, "wget")) {
        if (g_mode == 0) { stub_write_output(argv, "wget-bytes"); _exit(0); }
        errno = ENOENT;
        return -1;
    }
    errno = ENOENT;
    return -1;
}
#define execvp stub_execvp

/* pretend the fixed bin directories hold neither tool, so the only success
 * path left is upd_fetch's own wget attempt */
static int stub_access(const char *path, int mode)
{ (void)path; (void)mode; errno = ENOENT; return -1; }
#define access stub_access

typedef int SDL_atomic_t;
typedef unsigned int Uint32;
static int SDL_AtomicSet(SDL_atomic_t *a, int v) { int old = *a; *a = v; return old; }
static int SDL_AtomicGet(SDL_atomic_t *a) { return *a; }
static Uint32 SDL_GetTicks(void) { return 0; }
#define SDL_TICKS_PASSED(a, b) ((Sint32)(a) - (Sint32)(b) >= 0)
typedef int Sint32;
typedef void SDL_Thread;
typedef int (*SDL_ThreadFunction)(void *);
static SDL_Thread *SDL_CreateThread(SDL_ThreadFunction fn, const char *name, void *arg)
{ (void)fn; (void)name; (void)arg; return (SDL_Thread *)(intptr_t)1; }
static void SDL_DetachThread(SDL_Thread *t) { (void)t; }

#define SFT_VERSION "1.1.3"
#include "update.h"

static int failures;
#define CHECK(cond, msg) do { \
    if (cond) { printf("  PASS %s\n", msg); } \
    else { printf("  FAIL %s\n", msg); failures++; } \
} while (0)

int main(void)
{
    char tmp[] = "/tmp/sft-fetch-test-XXXXXX";
    if (!mkdtemp(tmp)) return 2;
    char out[1100];
    snprintf(out, sizeof(out), "%s/asset", tmp);
    setenv("PATH", tmp, 1);          /* nothing usable on PATH */

    g_mode = 0;
    int rc = upd_fetch("https://example.invalid/x", out, 5);
    FILE *f = fopen(out, "rb");
    char got[32] = "";
    if (f) { if (!fgets(got, sizeof(got), f)) got[0] = 0; fclose(f); }
    CHECK(rc == 0, "curl missing: upd_fetch succeeds through wget");
    CHECK(!strcmp(got, "wget-bytes"), "the wget fallback really produced the file");

    remove(out);
    g_mode = 2;
    rc = upd_fetch("https://example.invalid/x", out, 5);
    f = fopen(out, "rb");
    got[0] = 0;
    if (f) { if (!fgets(got, sizeof(got), f)) got[0] = 0; fclose(f); }
    CHECK(rc == 0 && !strcmp(got, "curl-bytes"), "curl present: it is used and wget is not tried");

    remove(out);
    g_mode = 1;
    rc = upd_fetch("https://example.invalid/x", out, 5);
    CHECK(rc == 127, "neither tool available: the 127 contract survives both attempts");
    f = fopen(out, "rb");
    CHECK(!f, "no downloader: no output file is left behind");
    if (f) fclose(f);

    if (failures) { printf("update_fetch_test FAILED (%d)\n", failures); return 1; }
    printf("update_fetch_test OK\n");
    return 0;
}
