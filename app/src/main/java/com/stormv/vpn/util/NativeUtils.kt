package com.stormv.vpn.util

object NativeUtils {
    init { System.loadLibrary("stormv-native") }

    external fun clearFdCloexec(fd: Int): Int

    /** fork()+execv() запуск tun2socks с наследованием tun_fd. Возвращает [pid, pipe_read_fd]. */
    external fun startTun2socksNative(path: String, tunFd: Int, proxyPort: Int): LongArray?

    /** WNOHANG: true = ещё жив, false = завершился (зомби пожат). */
    external fun isProcessAlive(pid: Long): Boolean

    /** Посылает SIGKILL. Не ждёт завершения. */
    external fun killProcess(pid: Long)
}
