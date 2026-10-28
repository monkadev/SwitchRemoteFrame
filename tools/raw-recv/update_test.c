/*
 * update_test.c - v1.1.3: the self-updater's platform handling.
 *
 * The viewer cannot be updated from inside the test process (it needs network
 * and a writable install), so this covers the parts that decide *how* an
 * update happens: AppImage detection and asset naming, the streaming file copy
 * that replaces an AppImage (no 32 MB heap cap), and the not-found contract
 * (127) the curl -> wget fallback in upd_fetch is built on. The fallback's
 * branch logic itself lives in update_fetch_test.c with a stubbed runner.
 *
 * Build: see tools/run_pc_tests.sh (Linux only - the AppImage path is).
 */
#define _GNU_SOURCE
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>

/* minimal SDL surface so update.h compiles outside the viewer */
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

static void write_file(const char *path, const void *data, size_t n)
{
    FILE *f = fopen(path, "wb");
    assert(f && fwrite(data, 1, n, f) == n);
    fclose(f);
}

int main(void)
{
    char tmp[] = "/tmp/sft-update-test-XXXXXX";
    assert(mkdtemp(tmp));
    char path[1100], link[1100];

    /* ---- asset suffix follows the detected packaging -------------------- */
    g_upd_appimage = 0;
    CHECK(!strcmp(upd_asset_suffix(), "-linux.tar.gz"), "plain Linux wants the tarball");
    g_upd_appimage = 1;
    CHECK(!strcmp(upd_asset_suffix(), "-linux.AppImage"), "an AppImage wants the AppImage asset");
    g_upd_appimage = 0;

    /* ---- no AppImage env: the self path is /proc/self/exe --------------- */
    g_upd_check = 0;
    unsetenv("APPIMAGE");
    upd_init(NULL);
    ssize_t n = readlink("/proc/self/exe", link, sizeof(link) - 1);
    assert(n > 0); link[n] = 0;
    CHECK(!g_upd_appimage, "no APPIMAGE: not treated as an AppImage");
    CHECK(!strcmp(g_upd_self, link), "no APPIMAGE: self resolves through /proc/self/exe");

    /* ---- APPIMAGE pointing at a real file: that file is the swap target - */
    snprintf(path, sizeof(path), "%s/switch-remote-frame-1.1.3-linux.AppImage", tmp);
    const char payload[] = "fake AppImage bytes";
    write_file(path, payload, strlen(payload));
    setenv("APPIMAGE", path, 1);
    upd_init(NULL);
    CHECK(g_upd_appimage == 1, "APPIMAGE set to a file: AppImage mode on");
    CHECK(!strcmp(g_upd_self, path), "APPIMAGE: the swap target is the original file, not the squashfs mount");
    CHECK(!strcmp(upd_asset_suffix(), "-linux.AppImage"), "APPIMAGE: the release asset suffix switches");

    /* ---- the streaming copy is byte-exact and survives binary content ---- */
    char src[1100], dst[1100];
    snprintf(src, sizeof(src), "%s/source.AppImage", tmp);
    snprintf(dst, sizeof(dst), "%s/source.AppImage.new", tmp);
    unsigned char blob[4096];
    for (size_t i = 0; i < sizeof(blob); i++) blob[i] = (unsigned char)(i * 31 + 7);
    write_file(src, blob, sizeof(blob));
    CHECK(upd_copy_file(src, dst), "upd_copy_file copies a whole file");
    unsigned char copy[4096];
    FILE *f = fopen(dst, "rb");
    size_t got = fread(copy, 1, sizeof(copy), f);
    fclose(f);
    CHECK(got == sizeof(blob) && !memcmp(copy, blob, sizeof(blob)), "the copy is byte-exact");
    CHECK(!upd_copy_file(src, "/nonexistent-dir-xyz/out"), "a missing destination reports failure");

    /* ---- a tool that exists nowhere gives the 127 the fallback keys on --- */
    const char *missing[] = { "sft-no-such-tool-12345", "arg", NULL };
    char clean_path[64];
    snprintf(clean_path, sizeof(clean_path), "%s/empty-bin", tmp);
    mkdir(clean_path, 0755);
    setenv("PATH", clean_path, 1);
    CHECK(upd_run(missing) == 127, "an unfindable tool reports 127, not -1");

    /* an explicit path that is missing keeps the 127 as well, no scan */
    const char *abs_missing[] = { "/tmp/sft-no-such-tool-12345", NULL };
    CHECK(upd_run(abs_missing) == 127, "an absolute missing path reports 127");

    /* ---- an explicit executable runs and its code comes back ------------- */
    char prog[1100];
    static const char script[] = "#!/bin/sh\nexit 42\n";
    snprintf(prog, sizeof(prog), "%s/exit42.sh", tmp);
    write_file(prog, script, sizeof(script) - 1);
    chmod(prog, 0755);
    const char *run42[] = { prog, NULL };
    CHECK(upd_run(run42) == 42, "an explicit executable's exit code is returned");

    if (failures) { printf("update_test FAILED (%d)\n", failures); return 1; }
    printf("update_test OK\n");
    return 0;
}
