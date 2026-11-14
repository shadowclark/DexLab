package com.dexlab.app.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val system: Boolean
)

object AppScanner {
    fun list(context: Context, includeSystem: Boolean): List<InstalledApp> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(0)
        return packages.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && system) return@mapNotNull null
            InstalledApp(
                packageName = pi.packageName,
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pi.packageName),
                versionName = pi.versionName,
                system = system
            )
        }.sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
    }
}
