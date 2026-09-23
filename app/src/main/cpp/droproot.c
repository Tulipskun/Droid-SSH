// droproot: drop privileges then exec (for Termux guest login as app user).
// Runs as root (via su), then setgroups/setgid/setuid to the app identity
// so apt/dpkg and file ownership behave like real Termux (apt refuses root).
// usage: droproot <uid> <gid> <g1,g2,...> -- <cmd> [args...]
#include <errno.h>
#include <grp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 6) {
        fprintf(stderr, "usage: droproot <uid> <gid> <g1,g2,...> -- <cmd> [args...]\n");
        return 2;
    }
    uid_t uid = (uid_t) atoi(argv[1]);
    gid_t gid = (gid_t) atoi(argv[2]);

    gid_t groups[32];
    int ngroups = 0;
    char *save = NULL;
    for (char *tok = strtok_r(argv[3], ",", &save);
         tok && ngroups < 32;
         tok = strtok_r(NULL, ",", &save)) {
        groups[ngroups++] = (gid_t) atoi(tok);
    }

    int dashdash = -1;
    for (int i = 4; i < argc; i++) {
        if (strcmp(argv[i], "--") == 0) { dashdash = i; break; }
    }
    if (dashdash < 0 || dashdash + 1 >= argc) {
        fprintf(stderr, "usage: droproot <uid> <gid> <g1,g2,...> -- <cmd> [args...]\n");
        return 2;
    }

    if (ngroups > 0 && setgroups(ngroups, groups) != 0) {
        fprintf(stderr, "droproot: setgroups: %s\n", strerror(errno));
    }
    if (setgid(gid) != 0) { perror("droproot: setgid"); return 1; }
    if (setuid(uid) != 0) { perror("droproot: setuid"); return 1; }
    execvp(argv[dashdash + 1], &argv[dashdash + 1]);
    fprintf(stderr, "droproot: exec %s: %s\n", argv[dashdash + 1], strerror(errno));
    return 127;
}
