#include <jni.h>
#include <fcntl.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_stormv_vpn_util_NativeUtils_clearFdCloexec(JNIEnv*, jclass, jint fd) {
    int flags = fcntl(fd, F_GETFD, 0);
    if (flags < 0) return -1;
    return fcntl(fd, F_SETFD, flags & ~FD_CLOEXEC);
}
