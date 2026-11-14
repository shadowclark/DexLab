package com.dexlab.app.apk

data class ManifestComponent(
    val type: String,
    val name: String,
    val exported: String,
    val permission: String?,
    val filters: List<String>
)

data class ManifestSummary(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val permissions: List<String>,
    val dangerousPermissions: List<String>,
    val components: List<ManifestComponent>,
    val deepLinks: List<String>,
    val debuggable: Boolean,
    val allowBackup: Boolean?,
    val cleartext: Boolean?,
    val networkSecurityConfig: String?,
    val extractNativeLibs: Boolean?,
    val label: String?,
    val icon: String?
) {
    val exportedCount: Int get() = components.count { it.exported == "true" || it.exported.startsWith("default (true") }
}

object ManifestReader {
    private val DANGEROUS = setOf(
        "android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS",
        "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
        "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.BODY_SENSORS",
        "android.permission.READ_PHONE_STATE", "android.permission.CALL_PHONE",
        "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR",
        "android.permission.POST_NOTIFICATIONS"
    )

    fun summarize(root: AxmlNode): ManifestSummary {
        val usesSdk = root.children.firstOrNull { it.name == "uses-sdk" }
        val app = root.children.firstOrNull { it.name == "application" }
        val permissions = root.children
            .filter { it.name == "uses-permission" || it.name == "uses-permission-sdk-23" }
            .mapNotNull { it.androidAttr("name") }
            .distinct()
            .sorted()

        val components = ArrayList<ManifestComponent>()
        val deepLinks = LinkedHashSet<String>()
        if (app != null) {
            for (child in app.children) {
                val type = child.name
                if (type !in COMPONENT_TYPES) continue
                val name = child.androidAttr("name") ?: continue
                val intentFilters = child.children.filter { it.name == "intent-filter" }
                val filters = ArrayList<String>()
                for (filter in intentFilters) {
                    val action = filter.children.filter { it.name == "action" }
                        .mapNotNull { it.androidAttr("name") }
                    val category = filter.children.filter { it.name == "category" }
                        .mapNotNull { it.androidAttr("name") }
                    val data = filter.children.filter { it.name == "data" }
                    val uris = data.mapNotNull { d ->
                        val scheme = d.androidAttr("scheme") ?: return@mapNotNull null
                        val host = d.androidAttr("host")
                        val path = d.androidAttr("path") ?: d.androidAttr("pathPrefix") ?: d.androidAttr("pathPattern")
                        buildString {
                            append(scheme).append("://")
                            if (host != null) append(host)
                            if (path != null) append(path)
                        }
                    }
                    deepLinks.addAll(uris)
                    filters.add(
                        buildString {
                            if (action.isNotEmpty()) append("action=").append(action.joinToString(","))
                            if (category.isNotEmpty()) {
                                if (isNotEmpty()) append("; ")
                                append("category=").append(category.joinToString(","))
                            }
                            if (uris.isNotEmpty()) {
                                if (isNotEmpty()) append("; ")
                                append("data=").append(uris.joinToString(","))
                            }
                        }.ifEmpty { "intent-filter" }
                    )
                }
                val exported = child.androidAttr("exported")
                    ?: if (intentFilters.isNotEmpty()) "default (true on legacy targets)" else "default (false)"
                components.add(
                    ManifestComponent(
                        type = type,
                        name = name,
                        exported = exported,
                        permission = child.androidAttr("permission"),
                        filters = filters
                    )
                )
            }
        }

        return ManifestSummary(
            packageName = root.attr("package"),
            versionName = root.androidAttr("versionName"),
            versionCode = root.androidAttr("versionCode")?.toLongOrNull(),
            minSdk = usesSdk?.androidAttr("minSdkVersion")?.toIntOrNull(),
            targetSdk = usesSdk?.androidAttr("targetSdkVersion")?.toIntOrNull(),
            permissions = permissions,
            dangerousPermissions = permissions.filter { it in DANGEROUS },
            components = components.sortedWith(compareBy({ it.type }, { it.name })),
            deepLinks = deepLinks.toList().sorted(),
            debuggable = app?.androidAttr("debuggable") == "true",
            allowBackup = app?.androidAttr("allowBackup")?.let { it == "true" },
            cleartext = app?.androidAttr("usesCleartextTraffic")?.let { it == "true" },
            networkSecurityConfig = app?.androidAttr("networkSecurityConfig"),
            extractNativeLibs = app?.androidAttr("extractNativeLibs")?.let { it == "true" },
            label = app?.androidAttr("label"),
            icon = app?.androidAttr("icon")
        )
    }

    private val COMPONENT_TYPES = setOf("activity", "activity-alias", "service", "receiver", "provider")
}
