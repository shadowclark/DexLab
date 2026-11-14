package com.dexlab.app.apk

import com.dexlab.app.core.Fmt

data class PackerFinding(
    val name: String,
    val confidence: String,
    val evidence: String
)

object PackerDetector {
    private data class Rule(val name: String, val confidence: String, val markers: List<String>)

    private val RULES = listOf(
        Rule("360 Jiagu", "high", listOf("libjiagu", "com/qihoo/util/", "com.qihoo", "libprotectclass")),
        Rule("Tencent Legu", "high", listOf("libshella", "libshellx", "com/tencent/stubshell")),
        Rule("Baidu Protect", "high", listOf("libbaiduprotect", "com/baidu/protect")),
        Rule("Alibaba / AliProtect", "high", listOf("libmobisec", "com/ali/mobisec", "aliprotect")),
        Rule("Bangcle", "high", listOf("libsecexe", "libsecmain", "com/bangcle")),
        Rule("Naga", "high", listOf("libnaga", "com/nagain")),
        Rule("ijiami", "high", listOf("libexec.so", "ijiami", "com/ijiami")),
        Rule("Tencent TOS", "medium", listOf("libtosprotection", "com/tencent/tos")),
        Rule("DexProtector", "medium", listOf("libdexprotector", "dexprotector")),
        Rule("Promon SHIELD", "medium", listOf("libshield", "promon")),
        Rule("Arxan", "medium", listOf("libarxan", "arxan")),
        Rule("DexGuard", "medium", listOf("dexguard", "com/guardsquare")),
        Rule("APK Protect", "medium", listOf("apkprotect")),
        Rule("Generic native protector", "low", listOf("libprotect", "libshell"))
    )

    fun detect(
        entries: List<String>,
        strings: List<String>,
        apkSize: Long,
        dexSize: Long,
        classNames: List<String> = emptyList()
    ): List<PackerFinding> {
        val lowerEntries = entries.map { it.lowercase() }
        val lowerStrings = strings.map { it.lowercase() }
        val lowerClasses = classNames.map { it.lowercase() }
        // Class descriptors and APK entry names are structural evidence.
        // Scanning every DEX string is noisy: a security tool that embeds
        // packer signatures would otherwise flag its own APK.
        val markerSources = if (lowerClasses.isNotEmpty()) lowerEntries + lowerClasses else lowerEntries + lowerStrings
        val out = ArrayList<PackerFinding>()

        for (rule in RULES) {
            var hit: String? = null
            for (marker in rule.markers) {
                val entry = lowerEntries.firstOrNull { it.contains(marker) }
                if (entry != null) {
                    hit = "entry $entry"
                    break
                }
                val string = markerSources.firstOrNull { it.contains(marker) }
                if (string != null) {
                    hit = if (lowerClasses.isNotEmpty() && lowerEntries.none { entry -> entry.contains(marker) }) {
                        "class \"${string.take(80)}\""
                    } else {
                        "entry \"${string.take(80)}\""
                    }
                    break
                }
            }
            if (hit != null) out.add(PackerFinding(rule.name, rule.confidence, hit))
        }

        if (out.isEmpty() && dexSize in 1..400_000 && apkSize > 4_000_000) {
            out.add(
                PackerFinding(
                    "Possible packer or stub",
                    "low",
                    "classes.dex is only ${Fmt.bytes(dexSize)} inside a ${Fmt.bytes(apkSize)} APK"
                )
            )
        }
        if (lowerEntries.none { it == "classes.dex" } && out.isEmpty()) {
            out.add(
                PackerFinding(
                    "No classes.dex",
                    "medium",
                    "The base APK has no classes.dex; code may live in a split or be loaded dynamically"
                )
            )
        }
        return out
    }
}
