package com.dexlab.app.notice

/**
 * Deliberately a plain, retained constant: the notice should stay visible in
 * DEX string tables even to someone who never runs the app.
 */
object StaticNotice {
    const val MESSAGE =
        "Hi, static analyst. You are reading the DexLab APK. That is fair: " +
            "DexLab exists to teach Android reverse engineering. Analyze only apps " +
            "you own or have permission to test, and report bugs instead of " +
            "republishing modified builds."

    const val ASSET = "STATIC_ANALYSIS_NOTICE.txt"
}
