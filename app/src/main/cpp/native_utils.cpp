#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <sys/wait.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_stormv_vpn_util_NativeUtils_clearFdCloexec(JNIEnv*, jclass, jint fd) {
    int flags = fcntl(fd, F_GETFD, 0);
    if (flags < 0) return -1;
    return fcntl(fd, F_SETFD, flags & ~FD_CLOEXEC);
}

// Запускает tun2socks через fork()+execv() напрямую, без ProcessBuilder.
// Android API 28+ использует posix_spawn с POSIX_SPAWN_CLOEXEC_DEFAULT,
// поэтому ProcessBuilder закрывает все fd в дочернем процессе. fork() не имеет
// этого ограничения и копирует все открытые fd из родительского процесса.
// Возвращает [pid, pipe_read_fd] или null при ошибке.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_stormv_vpn_util_NativeUtils_startTun2socksNative(
        JNIEnv* env, jclass,
        jstring j_path, jint tun_fd, jint proxy_port) {

    const char* path = env->GetStringUTFChars(j_path, nullptr);

    int pipe_fds[2];
    if (pipe(pipe_fds) != 0) {
        env->ReleaseStringUTFChars(j_path, path);
        return nullptr;
    }

    char fd_str[64];
    snprintf(fd_str, sizeof(fd_str), "fd://%d", (int)tun_fd);

    char proxy_str[64];
    snprintf(proxy_str, sizeof(proxy_str), "socks5://127.0.0.1:%d", (int)proxy_port);

    pid_t pid = fork();
    if (pid < 0) {
        close(pipe_fds[0]);
        close(pipe_fds[1]);
        env->ReleaseStringUTFChars(j_path, path);
        return nullptr;
    }

    if (pid == 0) {
        // Дочерний процесс: перенаправляем вывод в pipe и запускаем tun2socks.
        // tun_fd наследуется автоматически (FD_CLOEXEC снят в clearFdCloexec).
        dup2(pipe_fds[1], STDOUT_FILENO);
        dup2(pipe_fds[1], STDERR_FILENO);
        close(pipe_fds[0]);
        close(pipe_fds[1]);

        char* argv[] = {
            (char*)path,
            (char*)"-device",   fd_str,
            (char*)"-proxy",    proxy_str,
            (char*)"-loglevel", (char*)"info",
            nullptr
        };
        execv(path, argv);
        _exit(127);
    }

    // Родительский процесс
    close(pipe_fds[1]);
    env->ReleaseStringUTFChars(j_path, path);

    jlongArray result = env->NewLongArray(2);
    jlong values[2] = { (jlong)pid, (jlong)pipe_fds[0] };
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

// WNOHANG: возвращает 0 если процесс ещё жив, PID если завершился (и пожинает зомби), -1 при ошибке.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_stormv_vpn_util_NativeUtils_isProcessAlive(JNIEnv*, jclass, jlong pid) {
    int status = 0;
    pid_t ret = waitpid((pid_t)pid, &status, WNOHANG);
    return ret == 0; // 0 = ещё жив; >0 = завершился (пожат зомби); -1 = уже не существует
}

// Только SIGKILL, без waitpid — зомби пожинается через isProcessAlive или при выходе сервиса.
// Не вызывает waitpid чтобы не гоняться с корутиной, которая следит за процессом через isProcessAlive.
extern "C" JNIEXPORT void JNICALL
Java_com_stormv_vpn_util_NativeUtils_killProcess(JNIEnv*, jclass, jlong pid) {
    kill((pid_t)pid, SIGKILL);
}
