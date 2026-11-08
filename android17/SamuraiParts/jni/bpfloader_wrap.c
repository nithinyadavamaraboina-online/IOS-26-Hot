/*
 * Freestanding AArch64 platform bpfloader wrapper for Linux 4.14 (RMX1931 / Android 17):
 * Executes /system/bin/bpfloader.real for all 4.14-compatible platform BPF objects, then
 * guarantees bpf.progs_loaded=1 is signaled so init never blocks on wait_for_prop or triggers
 * reboot_on_failure reboot,netbpfload-missing.
 */
typedef unsigned long u64;
typedef long s64;

static inline s64 sys4(u64 n, u64 a, u64 b, u64 c, u64 d) {
    register u64 x8 __asm__("x8") = n;
    register u64 x0 __asm__("x0") = a;
    register u64 x1 __asm__("x1") = b;
    register u64 x2 __asm__("x2") = c;
    register u64 x3 __asm__("x3") = d;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x8) : "memory");
    return (s64)x0;
}

static void run_prog(const char *path, char *const argv[], char *const envp[]) {
    /* __NR_clone = 220, SIGCHLD = 17 */
    s64 pid = sys4(220, 17, 0, 0, 0);
    if (pid == 0) {
        /* __NR_execve = 221 */
        sys4(221, (u64)path, (u64)argv, (u64)envp, 0);
        sys4(93, 0, 0, 0, 0);
    } else if (pid > 0) {
        int status = 0;
        /* __NR_wait4 = 260 */
        sys4(260, (u64)pid, (u64)&status, 0, 0);
    }
}

void _start(void) {
    char *argv_real[] = { (char *)"/system/bin/bpfloader.real", 0 };
    char *envp[] = { (char *)"PATH=/system/bin:/apex/com.android.runtime/bin", 0 };
    run_prog("/system/bin/bpfloader.real", argv_real, envp);

    char *argv_prop[] = { (char *)"/system/bin/setprop", (char *)"bpf.progs_loaded", (char *)"1", 0 };
    run_prog("/system/bin/setprop", argv_prop, envp);

    /* __NR_exit_group = 94 */
    sys4(94, 0, 0, 0, 0);
    for (;;) {}
}
