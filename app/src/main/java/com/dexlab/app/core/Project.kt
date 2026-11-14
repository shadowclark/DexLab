package com.dexlab.app.core

enum class ProjectKind(val label: String) {
    APK("APK"),
    DEX("DEX"),
    ELF("ELF / native"),
    ARCHIVE("Archive"),
    FILE("File")
}

data class Project(
    val id: String,
    val name: String,
    val kind: ProjectKind,
    val files: List<String>,
    val sizeBytes: Long,
    val createdAt: Long,
    val packageName: String? = null,
    val label: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null
) {
    val mainFile: String? get() = files.firstOrNull()
    val subtitle: String get() = packageName ?: kind.label
}
