// Droid-SSH PTY backend (no forkpty on Bionic -> manual ptmx + fork + setsid + TIOCSCTTY)
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <termios.h>

static char **to_c_argv(JNIEnv *env, jobjectArray arr, int *out_len) {
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = (char **) calloc((size_t) n + 1, sizeof(char *));
    if (!out) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(c ? c : "");
        if (c) (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    out[n] = NULL;
    if (out_len) *out_len = (int) n;
    return out;
}

static void free_c_argv(char **argv) {
    if (!argv) return;
    for (int i = 0; argv[i]; i++) free(argv[i]);
    free(argv);
}

// returns master fd, or -errno on failure; child pid via outPid[0]
JNIEXPORT jint JNICALL
Java_com_tulipskun_droidssh_PtyNative_forkPty(JNIEnv *env, jclass cls,
        jobjectArray argvArr, jobjectArray envArr, jstring workDir,
        jint rows, jint cols, jintArray outPid) {
    (void) cls;
    int argc = 0, envc = 0;
    char **argv = to_c_argv(env, argvArr, &argc);
    char **envp = to_c_argv(env, envArr, &envc);
    const char *cwd = workDir ? (*env)->GetStringUTFChars(env, workDir, NULL) : NULL;
    if (!argv || argc < 1 || !envp) {
        free_c_argv(argv);
        free_c_argv(envp);
        if (cwd) (*env)->ReleaseStringUTFChars(env, workDir, cwd);
        return -EINVAL;
    }

    int master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (master < 0) {
        int e = errno;
        free_c_argv(argv);
        free_c_argv(envp);
        if (cwd) (*env)->ReleaseStringUTFChars(env, workDir, cwd);
        return -e;
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        int e = errno;
        close(master);
        free_c_argv(argv);
        free_c_argv(envp);
        if (cwd) (*env)->ReleaseStringUTFChars(env, workDir, cwd);
        return -e;
    }
    char *slave_name = ptsname(master);
    char slave_buf[64];
    if (!slave_name) {
        close(master);
        free_c_argv(argv);
        free_c_argv(envp);
        if (cwd) (*env)->ReleaseStringUTFChars(env, workDir, cwd);
        return -ENOTTY;
    }
    strncpy(slave_buf, slave_name, sizeof(slave_buf) - 1);
    slave_buf[sizeof(slave_buf) - 1] = '\0';

    pid_t pid = fork();
    if (pid < 0) {
        int e = errno;
        close(master);
        free_c_argv(argv);
        free_c_argv(envp);
        if (cwd) (*env)->ReleaseStringUTFChars(env, workDir, cwd);
        return -e;
    }
    if (pid == 0) {
        // ---- child ----
        close(master);
        setsid();
        int slave = open(slave_buf, O_RDWR);
        if (slave < 0) _exit(127);
        ioctl(slave, TIOCSCTTY, 0);
        struct winsize ws;
        memset(&ws, 0, sizeof(ws));
        ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
        ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
        ioctl(slave, TIOCSWINSZ, &ws);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        // apply env
        for (int i = 0; envp[i]; i++) {
            char *eq = strchr(envp[i], '=');
            if (eq) {
                *eq = '\0';
                setenv(envp[i], eq + 1, 1);
            }
        }
        if (cwd && cwd[0]) chdir(cwd);
        // hygiene: อย่าให้ shell ลูกถือ socket ของ server ค้าง (leak + bind ค้าง)
        for (int fd = 3; fd < 1024; fd++) close(fd);
        execvp(argv[0], argv);
        _exit(127);
    }
    // ---- parent ----
    free_c_argv(argv);
    free_c_argv(envp);
    if (cwd) (*env)->ReleaseStringUTFChars(env, workDir, cwd);
    jint jpid = (jint) pid;
    (*env)->SetIntArrayRegion(env, outPid, 0, 1, &jpid);
    return master;
}

JNIEXPORT jint JNICALL
Java_com_tulipskun_droidssh_PtyNative_ptyRead(JNIEnv *env, jclass cls,
        jint fd, jbyteArray buf, jint off, jint len) {
    (void) env;
    (void) cls;
    if (fd < 0 || !buf || len <= 0) return -EINVAL;
    jbyte *tmp = (jbyte *) malloc((size_t) len);
    if (!tmp) return -ENOMEM;
    ssize_t n;
    do {
        n = read(fd, tmp, (size_t) len);
    } while (n < 0 && errno == EINTR);
    jint ret;
    if (n <= 0) {
        ret = (n == 0) ? -1 : -errno; // EOF or EIO (slave closed) -> treat as end
        if (n < 0 && errno == EIO) ret = -1;
    } else {
        (*env)->SetByteArrayRegion(env, buf, off, (jsize) n, tmp);
        ret = (jint) n;
    }
    free(tmp);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_tulipskun_droidssh_PtyNative_ptyWrite(JNIEnv *env, jclass cls,
        jint fd, jbyteArray buf, jint off, jint len) {
    (void) cls;
    if (fd < 0 || !buf || len <= 0) return -EINVAL;
    jbyte *tmp = (jbyte *) malloc((size_t) len);
    if (!tmp) return -ENOMEM;
    (*env)->GetByteArrayRegion(env, buf, off, len, tmp);
    ssize_t total = 0;
    while (total < len) {
        ssize_t n = write(fd, tmp + total, (size_t) (len - total));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        total += n;
    }
    free(tmp);
    return total > 0 ? (jint) total : -errno;
}

JNIEXPORT void JNICALL
Java_com_tulipskun_droidssh_PtyNative_ptyClose(JNIEnv *env, jclass cls, jint fd) {
    (void) env;
    (void) cls;
    if (fd >= 0) close(fd);
}

// blocking wait; returns exit code, or 128+signal
JNIEXPORT jint JNICALL
Java_com_tulipskun_droidssh_PtyNative_ptyWait(JNIEnv *env, jclass cls, jint pid) {
    (void) env;
    (void) cls;
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t) pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    if (r < 0) return -errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_tulipskun_droidssh_PtyNative_ptyResize(JNIEnv *env, jclass cls,
        jint fd, jint rows, jint cols) {
    (void) env;
    (void) cls;
    if (fd < 0) return -EINVAL;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    return ioctl(fd, TIOCSWINSZ, &ws) == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL
Java_com_tulipskun_droidssh_PtyNative_ptyKill(JNIEnv *env, jclass cls, jint pid, jint sig) {
    (void) env;
    (void) cls;
    return kill((pid_t) pid, sig) == 0 ? 0 : -errno;
}
