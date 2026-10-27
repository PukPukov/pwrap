#define _GNU_SOURCE
#include <seccomp.h>
#include <errno.h>
#include <sched.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <endian.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/socket.h>
#ifndef PWRAP_SOCKET_FAMILIES
#error "Supply the reviewed socket allowlist from kernel-policy.nix"
#endif
/* Deny privileged kernel interfaces even for a process whose numeric UID is 0.
 * clone3 cannot be safely inspected by classic seccomp: ENOSYS makes libc use
 * clone(), whose namespace flags can be inspected. Normal threads still work.
 */
/* Seccomp sees 64-bit registers before the kernel narrows ABI arguments.
 * A uint32_t syscall parameter must ignore register high bits: plain EQ (even
 * SCMP_CMP32, which only casts constants) would admit an equivalent command.
 */
static int deny_u32_argument(scmp_filter_ctx filter, int syscall,
                             unsigned int argument, uint32_t value) {
    return seccomp_rule_add(filter, SCMP_ACT_ERRNO(EPERM), syscall, 1,
                           SCMP_CMP(argument, SCMP_CMP_MASKED_EQ, UINT32_MAX, value));
}

/* Prefix the libseccomp program with a genuine allowlist for both entrypoints.
 * libseccomp cannot express several NE comparisons on the same argument in one
 * rule. Keep its native-ABI/syscall guard intact instead of enumerating today's
 * denied families. Load only the low32 bits, as the kernel does for int domain.
 * Every accepted branch continues into libseccomp, never returns ALLOW itself.
 */
static int socket_guard(void) {
    const uint32_t families[] = { PWRAP_SOCKET_FAMILIES };
    enum { count = sizeof(families) / sizeof(families[0]) };
    _Static_assert(count > 0 && count <= 250, "Invalid socket allowlist size");
    const unsigned int low = offsetof(struct seccomp_data, args[0]) +
        (__BYTE_ORDER == __BIG_ENDIAN ? 4 : 0);
    struct sock_filter prefix[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, seccomp_arch_native(), 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SCMP_SYS(socket), 1, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SCMP_SYS(socketpair), 0, count + 2),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, low),
    };
    if (fwrite(prefix, sizeof(prefix), 1, stdout) != 1) return -1;
    for (unsigned int i = 0; i < count; i++) {
        struct sock_filter allow = BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, families[i], count - i, 0);
        if (fwrite(&allow, sizeof(allow), 1, stdout) != 1) return -1;
    }
    struct sock_filter deny = BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EAFNOSUPPORT);
    return fwrite(&deny, sizeof(deny), 1, stdout) == 1 && fflush(stdout) == 0 ? 0 : -1;
}

int main(void) {
    scmp_filter_ctx c = seccomp_init(SCMP_ACT_ALLOW);
    if (!c) return 1;
    const char *deny[] = {"mount","umount2","pivot_root","chroot","setns","unshare",
        "ptrace","process_vm_readv","process_vm_writev","process_madvise","pidfd_getfd",
        "bpf","perf_event_open","userfaultfd","keyctl","add_key","request_key",
        "init_module","finit_module","delete_module","kexec_load","kexec_file_load",
        "reboot","swapon","swapoff","open_by_handle_at","name_to_handle_at",
        "fsopen","fsconfig","fsmount","fspick","open_tree","move_mount","mount_setattr",
        "io_uring_setup","io_uring_enter","io_uring_register",NULL};
    for (int i=0;deny[i];i++) {
        int nr=seccomp_syscall_resolve_name(deny[i]);
        if(nr!=__NR_SCMP_ERROR && seccomp_rule_add(c,SCMP_ACT_ERRNO(EPERM),nr,0)<0) return 2;
    }
    int nr=seccomp_syscall_resolve_name("clone3");
    if(nr!=__NR_SCMP_ERROR && seccomp_rule_add(c,SCMP_ACT_ERRNO(ENOSYS),nr,0)<0) return 2;
    /* clone flags use the native unsigned-long ABI. Each bit mask tests only
     * its namespace bit, so unrelated upper bits cannot bypass these rules. */
    unsigned long flags[]={CLONE_NEWNS,CLONE_NEWCGROUP,CLONE_NEWUTS,CLONE_NEWIPC,
        CLONE_NEWUSER,CLONE_NEWPID,CLONE_NEWNET};
    for(unsigned i=0;i<sizeof(flags)/sizeof(flags[0]);i++)
        if(seccomp_rule_add(c,SCMP_ACT_ERRNO(EPERM),SCMP_SYS(clone),1,
            SCMP_A0(SCMP_CMP_MASKED_EQ,flags[i],flags[i]))<0) return 3;
    /* ioctl's cmd is unsigned int, unlike its register-sized arg pointer. */
    if(deny_u32_argument(c,SCMP_SYS(ioctl),1,TIOCSTI)<0) return 4;
    int result=socket_guard();
    if(result==0) result=seccomp_export_bpf(c,STDOUT_FILENO);
    seccomp_release(c);
    return result<0;
}
