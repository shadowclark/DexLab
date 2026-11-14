package com.dexlab.app.lab

object Checker {
    init {
        System.loadLibrary("sample")
    }

    external fun nativeSecret(): String
    external fun nativeCheck(input: String): Boolean
    external fun xor(data: ByteArray, key: Int): ByteArray
}
