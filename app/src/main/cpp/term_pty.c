#include <jni.h>
#include <errno.h>
#include <dirent.h>
#include <stdio.h>
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

#define ENV_LIMIT (256 * 1024)

static int env_has_entry(const char *data, size_t length, const char *entry, size_t entry_length) {
    size_t start = 0;

    while (start < length) {
        size_t end = start;
        while (end < length && data[end] != '\0') {
            end++;
        }
        if (end - start == entry_length && memcmp(data + start, entry, entry_length) == 0) {
            return 1;
        }
        start = end + 1;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_app_term_one_core_NativePty_killSession(JNIEnv *env, jclass clazz, jstring jtoken) {
    const char *token = (*env)->GetStringUTFChars(env, jtoken, NULL);
    char entry[256];
    char *buffer;
    DIR *proc;
    struct dirent *item;
    pid_t self = getpid();
    int killed = 0;
    int entry_length;

    if (token == NULL) {
        return 0;
    }
    entry_length = snprintf(entry, sizeof(entry), "TERM_SESSION_ID=%s", token);
    (*env)->ReleaseStringUTFChars(env, jtoken, token);
    if (entry_length <= 0 || entry_length >= (int) sizeof(entry)) {
        return 0;
    }

    buffer = (char *) malloc(ENV_LIMIT);
    proc = opendir("/proc");
    if (buffer == NULL || proc == NULL) {
        free(buffer);
        if (proc != NULL) {
            closedir(proc);
        }
        return 0;
    }

    while ((item = readdir(proc)) != NULL) {
        char *end = NULL;
        long pid = strtol(item->d_name, &end, 10);
        char path[64];
        size_t length = 0;
        int fd;

        if (end == item->d_name || *end != '\0' || pid <= 1 || (pid_t) pid == self) {
            continue;
        }
        snprintf(path, sizeof(path), "/proc/%ld/environ", pid);
        fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            continue;
        }
        while (length < ENV_LIMIT) {
            ssize_t n = read(fd, buffer + length, ENV_LIMIT - length);
            if (n < 0 && errno == EINTR) {
                continue;
            }
            if (n <= 0) {
                break;
            }
            length += (size_t) n;
        }
        close(fd);

        if (env_has_entry(buffer, length, entry, (size_t) entry_length)) {
            kill((pid_t) pid, SIGKILL);
            killed++;
        }
    }

    closedir(proc);
    free(buffer);
    return killed;
}
