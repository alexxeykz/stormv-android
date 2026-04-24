package com.stormv.vpn.util

object NativeUtils {
    init { System.loadLibrary("stormv-native") }

    external fun clearFdCloexec(fd: Int): Int
}
