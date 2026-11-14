package com.dexlab.app.apk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.dexlab.app.core.Fmt
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

data class ApkSummary(
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val signatureSha256: String?,
    val signatureSubject: String?,
    val splitNames: List<String>,
    val debuggable: Boolean
)

object ApkSummaryReader {
    @Suppress("DEPRECATION")
    fun read(context: Context, baseApk: File): ApkSummary? {
        return runCatching {
            val pm = context.packageManager
            val flags = PackageManager.GET_META_DATA or
                (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
            val pi = pm.getPackageArchiveInfo(baseApk.absolutePath, flags) ?: return null
            val ai = pi.applicationInfo ?: return null
            ai.sourceDir = baseApk.absolutePath
            ai.publicSourceDir = baseApk.absolutePath

            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                val info = pi.signingInfo
                when {
                    info == null -> null
                    info.hasMultipleSigners() -> info.apkContentsSigners
                    else -> info.signingCertificateHistory
                }
            } else {
                pi.signatures
            }

            var sha256: String? = null
            var subject: String? = null
            val first = signatures?.firstOrNull()
            if (first != null) {
                sha256 = Fmt.sha256(first.toByteArray())
                subject = runCatching {
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(first.toByteArray().inputStream()) as X509Certificate
                    cert.subjectDN.name
                }.getOrNull()
            }

            ApkSummary(
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pi.packageName),
                packageName = pi.packageName,
                versionName = pi.versionName,
                versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong(),
                minSdk = ai.minSdkVersion,
                targetSdk = ai.targetSdkVersion,
                signatureSha256 = sha256,
                signatureSubject = subject,
                splitNames = pi.splitNames?.toList().orEmpty(),
                debuggable = (ai.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            )
        }.getOrNull()
    }
}
