package com.dexlab.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.dexlab.app.core.AppSettings
import com.dexlab.app.dex.DexStrings
import com.dexlab.app.lab.Checker
import com.dexlab.app.native.CapstoneBridge
import com.dexlab.app.native.ElfParser
import com.dexlab.app.shizuku.ShizukuManager
import java.io.File

class DexLabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.load(this)
        ShizukuManager.init(this)
        if (BuildConfig.DEBUG) SelfCheck.run(this)
    }
}

object SelfCheck {
    fun run(context: Context) {
        Thread {
            val tag = "DexLabSelfCheck"
            try {
                Log.i(tag, "capstone=${CapstoneBridge.version()} available=${CapstoneBridge.available}")
                val so = File(context.applicationInfo.nativeLibraryDir, "libsample.so")
                Log.i(tag, "sample.so exists=${so.exists()} size=${so.length()}")
                if (so.exists()) {
                    val elf = ElfParser().parse(so)
                    Log.i(tag, "elf arch=${elf.arch} funcs=${elf.functions.size} imports=${elf.imports.size}")
                    val secret = runCatching { Checker.nativeSecret() }.getOrElse { "error: ${it.message}" }
                    Log.i(tag, "jniSecret=${secret.take(48)}")
                    val insn = CapstoneBridge.disassemble(
                        CapstoneBridge.ARCH_ARM64,
                        CapstoneBridge.MODE_ARM,
                        byteArrayOf(0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte()),
                        0x1000
                    )
                    Log.i(tag, "capstoneRet=${insn.firstOrNull()?.mnemonic}")
                }
                val dex = context.assets.open("lab/sample.dex").use { it.readBytes() }
                val strings = DexStrings.readBytes(dex)
                Log.i(tag, "dex strings=${strings.size} hasPassword=${strings.any { it.contains("s3cr3t") }}")
            } catch (t: Throwable) {
                Log.e(tag, "self-check failed", t)
            }
        }.start()
    }
}
