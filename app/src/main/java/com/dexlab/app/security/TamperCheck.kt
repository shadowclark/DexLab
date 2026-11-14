package com.dexlab.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.dexlab.app.BuildConfig
import com.dexlab.app.core.Fmt
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

data class TamperReport(
    val official: Boolean,
    val actualSha256: String?,
    val expectedSha256: String,
    val signer: String?,
    val detail: String?
)

/**
 * Advisory integrity check. A modified APK normally has to be re-signed, so a
 * signer mismatch catches the common repackage/re-sign case.
 *
 * This is not tamper-proof: a patcher can also patch this check or the expected
 * fingerprint. It is meant to warn a user who installed a copy from elsewhere.
 */
object TamperCheck {
    fun inspect(context: Context): TamperReport {
        val expected = BuildConfig.EXPECTED_SIGNATURE_SHA256.lowercase()
        val signatures = runCatching { readSignatures(context) }.getOrElse { emptyArray() }
        if (signatures.isEmpty()) {
            return TamperReport(
                official = false,
                actualSha256 = null,
                expectedSha256 = expected,
                signer = null,
                detail = "No signing certificate could be read from this APK."
            )
        }

        val digests = signatures.map { sha256(it.toByteArray()) }
        val actual = digests.firstOrNull()
        val official = digests.any { it.equals(expected, ignoreCase = true) }
        val signer = runCatching {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(signatures.first().toByteArray())) as X509Certificate
            cert.subjectDN.name
        }.getOrNull()

        return TamperReport(
            official = official,
            actualSha256 = actual,
            expectedSha256 = expected,
            signer = signer,
            detail = if (official) null else
                "This copy is signed with a different certificate than the reference DexLab build. It may have been repackaged or modified."
        )
    }

    private fun readSignatures(context: Context): Array<Signature> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
    }

    private fun sha256(bytes: ByteArray): String = Fmt.sha256(bytes)
}
