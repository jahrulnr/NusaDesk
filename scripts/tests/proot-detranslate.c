/* Execute upstream path.c logic; only the external namespace/procfs are fixtures. */
#include <assert.h>
#include <stdbool.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>

typedef struct { const char *root; } Tracee;
typedef enum { PATHS_ARE_EQUAL, PATH1_IS_PREFIX, PATH2_IS_PREFIX, PATHS_ARE_NOT_COMPARABLE } Comparison;
typedef enum { HOST, GUEST } Side;
static const char *get_root(const Tracee *t) { return t->root; }
Comparison compare_paths(const char *, const char *);
bool belongs_to_guestfs(const Tracee *, const char *);

static const char *get_path_binding(const Tracee *t, Side side, const char *path)
{
    (void)t; assert(side == HOST);
    const char *roots[] = {"/host/shared", "/host/other", "/symmetric"};
    for (unsigned i = 0; i < sizeof(roots)/sizeof(roots[0]); i++) {
        Comparison comparison = compare_paths(roots[i], path);
        if (comparison == PATHS_ARE_EQUAL || comparison == PATH1_IS_PREFIX) return roots[i];
    }
    return NULL;
}
static int substitute_binding(Tracee *t, Side side, char *path)
{
    const char *host = get_path_binding(t, side, path);
    if (!host) return -ENOENT;
    if (!strcmp(host, "/symmetric")) return 0;
    const char *guest = !strcmp(host, "/host/shared") ? "/mnt/shared" : "/mnt/other";
    char result[PATH_MAX];
    snprintf(result, sizeof(result), "%s%s", guest, path + strlen(host));
    strcpy(path, result);
    return 1;
}
static int readlink_proc2(Tracee *t, char *path, const char *referrer)
{ (void)t; (void)path; (void)referrer; return 0; }

#include "path-functions.inc"

int main(void)
{
    const struct { const char *path, *referrer, *expected; int status; } cases[] = {
        /* A kernel/inherited referrer outside all bindings must not abort the tracer. */
        {"/outside/target", "/outside/link", "/outside/target", 0},
        {"/host/shared/target", "/outside/link", "/host/shared/target", 0},
        {"/guest-root/etc/file", "/outside/link", "/etc/file", 10},
        {"/host/shared/target", "/host/shared/link", "/mnt/shared/target", 19},
        {"/host/shared/target", "/host/other/link", "/host/shared/target", 0},
        {"/symmetric/target", "/symmetric/link", "/symmetric/target", 0},
        {"relative/target", "/outside/link", "relative/target", 0},
        {"/host/shared/target", NULL, "/mnt/shared/target", 19},
        {"/outside/target", NULL, "/outside/target", -EPERM},
        {"/guest-root", NULL, "/", 2},
        {"/host/shared/target", "/proc/123/fd/9", "/mnt/shared/target", 19},
    };
    Tracee t = {"/guest-root"};
    for (unsigned i = 0; i < sizeof(cases)/sizeof(cases[0]); i++) {
        char path[PATH_MAX], referrer[PATH_MAX];
        strcpy(path, cases[i].path);
        if (cases[i].referrer) strcpy(referrer, cases[i].referrer);
        int result = detranslate_path(&t, path, cases[i].referrer ? referrer : NULL);
        if (result != cases[i].status || strcmp(path, cases[i].expected)) {
            fprintf(stderr, "case %u: got %d %s; expected %d %s\n", i, result, path, cases[i].status, cases[i].expected);
            return 1;
        }
    }
    puts("PASS: 11 real detranslate_path namespace cases (assertions enabled)");
    return 0;
}
