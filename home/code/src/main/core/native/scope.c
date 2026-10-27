#define _GNU_SOURCE
#include <linux/landlock.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>

/* Explicit host-network sharing does not implicitly grant abstract IPC. The
 * caller must pass --allow-abstract only when that separate capability was
 * selected. Path-based AF_UNIX endpoints remain controlled by the mount plan.
 * Unlike an optional hardening knob, failure here must stop the payload. */
int main(int argc, char **argv) {
    int command = 1;
    int allow_abstract = 0;
    int allow_signals = 0;
    while (command < argc) {
        if (strcmp(argv[command], "--allow-abstract") == 0)
            allow_abstract = 1;
        else if (strcmp(argv[command], "--allow-signals") == 0)
            allow_signals = 1;
        else
            break;
        ++command;
    }
    if (argc <= command || (allow_abstract && allow_signals)) {
        fprintf(stderr, "usage: pwrap-ipc-scope [--allow-abstract | --allow-signals] COMMAND [ARG...]\n");
        return 2;
    }
    int abi = syscall(SYS_landlock_create_ruleset, NULL, 0,
                      LANDLOCK_CREATE_RULESET_VERSION);
    if (abi < 6) {
        fprintf(stderr, "pwrap: shared-network IPC scope requires Landlock ABI >= 6\n");
        return 1;
    }
    struct landlock_ruleset_attr rules = {
        /* systemd launches lifecycle commands independently; reload/stop must
         * still signal the main process from a sibling invocation. */
        .scoped = (allow_signals ? 0 : LANDLOCK_SCOPE_SIGNAL) |
                  (allow_abstract ? 0 : LANDLOCK_SCOPE_ABSTRACT_UNIX_SOCKET),
    };
    int fd = syscall(SYS_landlock_create_ruleset, &rules, sizeof(rules), 0);
    if (fd < 0 || prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) ||
        syscall(SYS_landlock_restrict_self, fd, 0)) {
        perror("pwrap: Landlock scope");
        return 1;
    }
    close(fd);
    execvp(argv[command], argv + command);
    perror("pwrap: exec");
    return 1;
}
