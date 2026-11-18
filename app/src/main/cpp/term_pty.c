#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>

static void throw_runtime(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, message);
        (*env)->DeleteLocalRef(env, cls);
    }
}

static char **to_cstr_array(JNIEnv *env, jobjectArray array) {
    jsize count = array != NULL ? (*env)->GetArrayLength(env, array) : 0;
    char **out = (char **) calloc((size_t) count + 1, sizeof(char *));
    if (out == NULL) {
        return NULL;
    }
    for (jsize i = 0; i < count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *utf = item != NULL ? (*env)->GetStringUTFChars(env, item, NULL) : NULL;
        out[i] = strdup(utf != NULL ? utf : "");
        if (utf != NULL) {
            (*env)->ReleaseStringUTFChars(env, item, utf);
        }
        if (item != NULL) {
            (*env)->DeleteLocalRef(env, item);
        }
    }
    return out;
}

static void free_cstr_array(char **array) {
    if (array == NULL) {
        return;
    }
    for (int i = 0; array[i] != NULL; i++) {
        free(array[i]);
    }
    free(array);
}

JNIEXPORT jint JNICALL
Java_app_term_one_core_NativePty_open(JNIEnv *env, jclass clazz, jstring jcmd,
                                      jobjectArray jargv, jobjectArray jenvp, jstring jcwd,
                                      jint rows, jint cols, jintArray jpid) {
    const char *cmd = NULL;
    const char *cwd = NULL;
    char **argv = NULL;
    char **envp = NULL;
    char devname[64];
    struct termios tios;
    struct winsize ws;
    sigset_t sigset;
    jint result = -1;
    jint pid_value = 0;
    int ptm = -1;
    pid_t pid;

    cmd = (*env)->GetStringUTFChars(env, jcmd, NULL);
    cwd = (*env)->GetStringUTFChars(env, jcwd, NULL);
    argv = to_cstr_array(env, jargv);
    envp = to_cstr_array(env, jenvp);

    if (cmd == NULL || cwd == NULL || argv == NULL || envp == NULL) {
        goto fail;
    }

    ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        goto fail;
    }

    if (grantpt(ptm) != 0 || unlockpt(ptm) != 0 || ptsname_r(ptm, devname, sizeof(devname)) != 0) {
        goto fail;
    }

    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(ptm, TCSANOW, &tios);
    }

    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(ptm, TIOCSWINSZ, &ws);

    pid = fork();
    if (pid < 0) {
        goto fail;
    }

    if (pid == 0) {
        int pts;
        int max_fd;

        sigemptyset(&sigset);
        sigprocmask(SIG_SETMASK, &sigset, NULL);

        close(ptm);
        setsid();

        pts = open(devname, O_RDWR);
        if (pts < 0) {
            _exit(1);
        }

        ioctl(pts, TIOCSCTTY, 0);
        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);

        max_fd = (int) sysconf(_SC_OPEN_MAX);
        if (max_fd < 0 || max_fd > 4096) {
            max_fd = 4096;
        }
        for (int fd = 3; fd < max_fd; fd++) {
            close(fd);
        }

        if (chdir(cwd) != 0) {
            /* keep the inherited directory when the target is missing */
        }

        execve(cmd, argv, envp);
        _exit(127);
    }

    pid_value = (jint) pid;
    (*env)->SetIntArrayRegion(env, jpid, 0, 1, &pid_value);
    result = ptm;
    goto done;

fail:
    if (ptm >= 0) {
        close(ptm);
    }
    throw_runtime(env, "Failed to open pseudo terminal");

done:
    if (cmd != NULL) {
        (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    }
    if (cwd != NULL) {
        (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    }
    free_cstr_array(argv);
    free_cstr_array(envp);
    return result;
}

JNIEXPORT void JNICALL
Java_app_term_one_core_NativePty_setSize(JNIEnv *env, jclass clazz, jint fd, jint rows,
                                         jint cols, jint width_px, jint height_px) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ws.ws_xpixel = (unsigned short) width_px;
    ws.ws_ypixel = (unsigned short) height_px;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_app_term_one_core_NativePty_read(JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer,
                                      jint offset, jint length) {
    unsigned char tmp[16384];
    ssize_t n;

    if (length > (jint) sizeof(tmp)) {
        length = (jint) sizeof(tmp);
    }
    do {
        n = read(fd, tmp, (size_t) length);
    } while (n < 0 && errno == EINTR);

    if (n <= 0) {
        return -1;
    }
    (*env)->SetByteArrayRegion(env, buffer, offset, (jsize) n, (const jbyte *) tmp);
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_app_term_one_core_NativePty_write(JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer,
                                       jint offset, jint length) {
    unsigned char tmp[4096];
    jint total = 0;

    while (total < length) {
        jint chunk = length - total;
        ssize_t n;
        jint done = 0;

        if (chunk > (jint) sizeof(tmp)) {
            chunk = (jint) sizeof(tmp);
        }
        (*env)->GetByteArrayRegion(env, buffer, offset + total, chunk, (jbyte *) tmp);

        while (done < chunk) {
            n = write(fd, tmp + done, (size_t) (chunk - done));
            if (n < 0) {
                if (errno == EINTR) {
                    continue;
                }
                return -1;
            }
            done += (jint) n;
        }
        total += chunk;
    }
    return total;
}

JNIEXPORT jint JNICALL
Java_app_term_one_core_NativePty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    pid_t result;

    do {
        result = waitpid((pid_t) pid, &status, 0);
    } while (result < 0 && errno == EINTR);

    if (result < 0) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    }
    return -1;
}

JNIEXPORT void JNICALL
Java_app_term_one_core_NativePty_kill(JNIEnv *env, jclass clazz, jint pid, jint signal_number) {
    if (kill((pid_t) -pid, signal_number) != 0) {
        kill((pid_t) pid, signal_number);
    }
}

JNIEXPORT void JNICALL
Java_app_term_one_core_NativePty_close(JNIEnv *env, jclass clazz, jint fd) {
    close(fd);
}