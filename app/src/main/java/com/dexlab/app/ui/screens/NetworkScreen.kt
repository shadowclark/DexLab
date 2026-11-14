package com.dexlab.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.dexlab.app.ui.components.KeyValue
import com.dexlab.app.ui.components.LabScaffold
import com.dexlab.app.ui.components.LoadingPane
import com.dexlab.app.ui.components.Pill
import com.dexlab.app.ui.components.SectionCard
import com.dexlab.app.ui.theme.Amber
import com.dexlab.app.ui.theme.Blue
import com.dexlab.app.ui.theme.CodeFont
import com.dexlab.app.ui.theme.Green
import com.dexlab.app.ui.theme.Red
import com.dexlab.app.ui.theme.Teal
import com.dexlab.app.ui.theme.TextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections

private data class InterfaceInfo(
    val name: String,
    val addresses: List<String>,
    val up: Boolean
)

private data class NearbyAp(
    val ssid: String,
    val bssid: String,
    val security: String,
    val bars: Int,
    val rssi: Int,
    val frequency: Int
)

private data class NetworkSnapshot(
    val transport: String,
    val interfaceName: String?,
    val ipv4: List<String>,
    val ipv6: List<String>,
    val dns: List<String>,
    val mtu: Int?,
    val ssid: String?,
    val bssid: String?,
    val security: String?,
    val linkSpeed: Int?,
    val frequency: Int?,
    val rssi: Int?,
    val vpn: Boolean,
    val captivePortal: Boolean,
    val metered: Boolean,
    val interfaces: List<InterfaceInfo>,
    val nearby: List<NearbyAp>,
    val scanNote: String?,
    val locationEnabled: Boolean,
    val permissionsGranted: Boolean
)

private data class PublicIpInfo(
    val ip: String,
    val city: String,
    val region: String,
    val country: String,
    val org: String
)

@Composable
fun NetworkScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<NetworkSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var publicInfo by remember { mutableStateOf<PublicIpInfo?>(null) }
    var publicError by remember { mutableStateOf<String?>(null) }
    var publicLoading by remember { mutableStateOf(false) }

    fun reload(activeScan: Boolean = false) {
        scope.launch {
            loading = true
            snapshot = withContext(Dispatchers.IO) {
                runCatching { collectNetwork(context, activeScan) }.getOrElse {
                    emptyNetworkSnapshot("Could not read network state: ${it.message ?: it.javaClass.simpleName}")
                }
            }
            loading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { reload(false) }

    LaunchedEffect(Unit) {
        reload(false)
    }

    LabScaffold(
        title = "Network scope",
        subtitle = "Local addresses, Wi-Fi links and nearby radios",
        onBack = { nav.popBackStack() },
        actions = {
            TextButton(onClick = { reload(true) }) { Text("Refresh") }
        }
    ) { padding ->
        if (loading && snapshot == null) {
            LoadingPane("Reading interfaces and links…")
            return@LabScaffold
        }
        val data = snapshot ?: emptyNetworkSnapshot("No network state")
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "This page is a scope, not a scanner for people. Local addresses are read without a network request. Public-IP lookup and an active Wi-Fi scan are explicit actions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                SectionCard(title = "This network", subtitle = "Active link observed by Android") {
                    KeyValue("Transport", data.transport)
                    KeyValue("Interface", data.interfaceName ?: "not exposed", mono = true)
                    KeyValue("Private IPv4", data.ipv4.ifEmpty { listOf("not assigned") }.joinToString("\n"), mono = true)
                    if (data.ipv6.isNotEmpty()) {
                        KeyValue("IPv6", data.ipv6.joinToString("\n"), mono = true)
                    }
                    KeyValue("DNS", data.dns.ifEmpty { listOf("not exposed") }.joinToString("\n"), mono = true)
                    data.mtu?.let { KeyValue("MTU", it.toString()) }
                    data.ssid?.let { KeyValue("SSID", it, mono = true, valueColor = Amber) }
                    data.bssid?.let { KeyValue("BSSID", it, mono = true) }
                    data.security?.let { KeyValue("Wi-Fi security", it, valueColor = securityColor(it)) }
                    data.linkSpeed?.let { KeyValue("Link speed", "$it Mbps") }
                    data.frequency?.let { KeyValue("Frequency", "$it MHz") }
                    data.rssi?.let { KeyValue("RSSI", "$it dBm") }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (data.vpn) Pill("VPN transport", Green) else Pill("no VPN transport", TextDim)
                        if (data.captivePortal) Pill("captive portal", Amber)
                        if (data.metered) Pill("metered", Amber) else Pill("not metered", Teal)
                    }
                }
            }

            item {
                SectionCard(
                    title = "Nearby Wi-Fi",
                    subtitle = "Access points visible to this device",
                    trailing = {
                        TextButton(onClick = {
                            if (!data.permissionsGranted) {
                                permissionLauncher.launch(requiredNetworkPermissions())
                            } else {
                                reload(true)
                            }
                        }) { Text(if (data.permissionsGranted) "Scan" else "Grant") }
                    }
                ) {
                    when {
                        !data.permissionsGranted -> {
                            Text(
                                "Android requires location permission to return Wi-Fi scan results. Granting it lets this page list nearby SSIDs and BSSIDs; it does not read your location history.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                permissionLauncher.launch(requiredNetworkPermissions())
                            }) { Text("Grant Wi-Fi scan permission") }
                        }
                        !data.locationEnabled -> {
                            Text(
                                "Location services are off. Android hides Wi-Fi scan results when location is disabled, even if the permission is granted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Amber
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { openLocationSettings(context) }) {
                                Text("Open location settings")
                            }
                        }
                        data.nearby.isEmpty() -> {
                            Text(
                                data.scanNote ?: "No access points returned. Android may be throttling scans; try again in a moment.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> {
                            Text(
                                "${data.nearby.size} access points, strongest first. Signal bars are relative, not a location.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            data.nearby.take(30).forEachIndexed { index, ap ->
                                if (index > 0) HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(vertical = 5.dp)
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = null,
                                        tint = securityColor(ap.security),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            ap.ssid.ifBlank { "(hidden SSID)" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "${ap.bssid} · ${ap.security} · ${ap.frequency} MHz · ${ap.rssi} dBm",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Pill("${ap.bars}/4", securityColor(ap.security))
                                }
                            }
                            if (data.scanNote != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    data.scanNote,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "Public IP",
                    subtitle = "One explicit HTTPS lookup",
                    trailing = {
                        TextButton(
                            enabled = !publicLoading,
                            onClick = {
                                scope.launch {
                                    publicLoading = true
                                    publicError = null
                                    val result = withContext(Dispatchers.IO) { runCatching { fetchPublicIp() } }
                                    result.onSuccess { publicInfo = it }
                                        .onFailure { publicError = it.message ?: it.javaClass.simpleName }
                                    publicLoading = false
                                }
                            }
                        ) { Text(if (publicLoading) "Checking…" else "Check") }
                    }
                ) {
                    Text(
                        "Nothing is sent until you tap Check. The request goes to ipinfo.io over HTTPS and returns the address, city and network operator that site sees.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        publicLoading -> Text("Looking up the egress address…", style = MaterialTheme.typography.bodySmall)
                        publicError != null -> Text(
                            "Lookup failed: $publicError",
                            style = MaterialTheme.typography.bodySmall,
                            color = Red
                        )
                        publicInfo != null -> {
                            val info = publicInfo!!
                            KeyValue("Public IP", info.ip, mono = true, valueColor = Amber)
                            if (info.city.isNotBlank() || info.region.isNotBlank()) {
                                KeyValue("City / region", listOf(info.city, info.region).filter { it.isNotBlank() }.joinToString(", "))
                            }
                            if (info.country.isNotBlank()) KeyValue("Country", info.country)
                            if (info.org.isNotBlank()) KeyValue("Network operator", info.org)
                        }
                        else -> Text(
                            "Not checked. Your private address above is the address other devices on the LAN can reach; the public IP is what websites see.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Exposure signals", subtitle = "What the link can and cannot reveal") {
                    val risks = buildList {
                        if (data.security == "Open") {
                            add("Open Wi-Fi" to "The link is not protected by WPA. Nearby devices can observe more traffic metadata. Use HTTPS and a trusted VPN.")
                        }
                        val repeated = data.nearby
                            .filter { it.ssid.isNotBlank() }
                            .groupBy { it.ssid }
                            .filterValues { it.size > 1 }
                        if (repeated.isNotEmpty()) {
                            add(
                                "Repeated SSID" to
                                    "More than one access point advertises ${repeated.keys.take(3).joinToString(", ")}. Mesh networks do this normally; an evil-twin setup can look the same. It is a prompt to verify, not proof."
                            )
                        }
                        if (data.captivePortal) {
                            add("Captive portal" to "A portal is intercepting or gating this connection. Do not enter credentials on an untrusted portal.")
                        }
                        if (data.vpn) {
                            add("VPN transport" to "Android reports an active VPN transport. That hides the local link from some observers but does not prove the VPN itself is trustworthy.")
                        }
                    }
                    if (risks.isEmpty()) {
                        Text(
                            "No link-level exposure signal was flagged. That is not a clean bill of health: it only means this quick check found nothing in the observable Wi-Fi and transport data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        risks.forEach { (title, body) ->
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    Modifier
                                        .padding(top = 5.dp)
                                        .size(5.dp)
                                        .background(Amber)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.titleSmall, color = Amber)
                                    Text(
                                        body,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Text(
                        "Surveillance cannot be detected from a Wi-Fi scan.",
                        style = MaterialTheme.typography.titleSmall,
                        color = Red
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A scan sees radio access points, their security flags and signal strength. It cannot see cameras, people, microphones, or who is operating a network. Treat “is there surveillance near me?” as a question Wi-Fi data cannot answer; use physical and legal checks instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SectionCard(title = "All interfaces", subtitle = "Kernel network interfaces readable by this app") {
                    if (data.interfaces.isEmpty()) {
                        Text("No interfaces returned.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        data.interfaces.take(20).forEach { info ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill(if (info.up) "up" else "down", if (info.up) Green else TextDim)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        info.name,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
                                        color = Blue
                                    )
                                    Text(
                                        info.addresses.ifEmpty { listOf("no address") }.joinToString(", "),
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }

            item {
                Text(
                    "Some fields are hidden by Android unless location or nearby-device permission is granted. A private IP is not your public identity; NAT and carrier-grade NAT sit in between.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
    }
}

private fun securityColor(security: String): Color = when {
    security == "Open" -> Amber
    security.startsWith("WEP") -> Red
    security.startsWith("WPA3") -> Green
    security.startsWith("WPA") -> Teal
    else -> TextDim
}

private fun requiredNetworkPermissions(): Array<String> {
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    return permissions.toTypedArray()
}

private fun permissionsGranted(context: Context): Boolean =
    requiredNetworkPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private fun locationEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return runCatching {
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)
}

private fun openLocationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun emptyNetworkSnapshot(note: String) = NetworkSnapshot(
    transport = "unavailable",
    interfaceName = null,
    ipv4 = emptyList(),
    ipv6 = emptyList(),
    dns = emptyList(),
    mtu = null,
    ssid = null,
    bssid = null,
    security = null,
    linkSpeed = null,
    frequency = null,
    rssi = null,
    vpn = false,
    captivePortal = false,
    metered = false,
    interfaces = emptyList(),
    nearby = emptyList(),
    scanNote = note,
    locationEnabled = false,
    permissionsGranted = false
)

@SuppressLint("MissingPermission")
private fun collectNetwork(context: Context, activeScan: Boolean): NetworkSnapshot {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val active = connectivity?.activeNetwork
    val capabilities = active?.let { connectivity.getNetworkCapabilities(it) }
    val link = active?.let { connectivity.getLinkProperties(it) }
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val granted = permissionsGranted(context)
    val locationOn = locationEnabled(context)

    val interfaces = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).map { networkInterface ->
            InterfaceInfo(
                name = networkInterface.name,
                addresses = Collections.list(networkInterface.inetAddresses)
                    .mapNotNull { it.hostAddress?.substringBefore('%') },
                up = networkInterface.isUp
            )
        }
    }.getOrDefault(emptyList())

    val linkAddresses = link?.linkAddresses.orEmpty().mapNotNull { it.address.hostAddress?.substringBefore('%') }
    val ipv4 = linkAddresses.filter { ':' !in it }.distinct()
    val ipv6 = linkAddresses.filter { ':' in it }.distinct()
    val dns = link?.dnsServers.orEmpty().mapNotNull { it.hostAddress?.substringBefore('%') }.distinct()
    val transports = buildList {
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("Cellular")
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("Bluetooth")
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) == true) add("Wi-Fi Aware")
    }

    var ssid: String? = null
    var bssid: String? = null
    var security: String? = null
    var linkSpeed: Int? = null
    var frequency: Int? = null
    var rssi: Int? = null
    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
        runCatching {
            val info = wifi?.connectionInfo
            if (info != null) {
                ssid = info.ssid?.takeUnless { it == "<unknown ssid>" || it.isBlank() }
                bssid = info.bssid?.takeUnless { it == "02:00:00:00:00:00" }
                linkSpeed = info.linkSpeed.takeIf { it > 0 }
                frequency = info.frequency.takeIf { it > 0 }
                rssi = info.rssi
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    security = securityLabel(info.currentSecurityType)
                }
            }
        }
    }

    val nearby = mutableListOf<NearbyAp>()
    var scanNote: String? = null
    if (granted && locationOn && wifi != null) {
        runCatching {
            if (activeScan) wifi.startScan()
            val results: List<ScanResult> = wifi.scanResults.orEmpty()
            results.distinctBy { it.BSSID }.forEach { result ->
                nearby.add(
                    NearbyAp(
                        ssid = result.SSID.orEmpty(),
                        bssid = result.BSSID.orEmpty(),
                        security = securityFromCapabilities(result.capabilities.orEmpty()),
                        bars = WifiManager.calculateSignalLevel(result.level, 5).coerceIn(0, 4),
                        rssi = result.level,
                        frequency = result.frequency
                    )
                )
            }
            if (activeScan) scanNote = "Active scan requested. Android may return cached results while throttling."
        }.onFailure {
            scanNote = "Wi-Fi scan failed: ${it.message ?: it.javaClass.simpleName}"
        }
    } else {
        scanNote = when {
            !granted -> "Wi-Fi scan permission is not granted."
            !locationOn -> "Location services are off, so Android withholds nearby scan results."
            wifi == null -> "This device did not expose a Wi-Fi manager."
            else -> null
        }
    }
    nearby.sortByDescending { it.bars }

    return NetworkSnapshot(
        transport = if (transports.isEmpty()) "offline or unclassified" else transports.joinToString(" + "),
        interfaceName = link?.interfaceName,
        ipv4 = ipv4,
        ipv6 = ipv6,
        dns = dns,
        mtu = link?.mtu?.takeIf { it > 0 },
        ssid = ssid,
        bssid = bssid,
        security = security,
        linkSpeed = linkSpeed,
        frequency = frequency,
        rssi = rssi,
        vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
        captivePortal = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
        interfaces = interfaces,
        nearby = nearby,
        scanNote = scanNote,
        locationEnabled = locationOn,
        permissionsGranted = granted
    )
}

private fun securityFromCapabilities(capabilities: String): String = when {
    capabilities.contains("SAE", ignoreCase = true) -> "WPA3-SAE"
    capabilities.contains("OWE", ignoreCase = true) -> "OWE"
    capabilities.contains("WPA3", ignoreCase = true) -> "WPA3"
    capabilities.contains("RSN", ignoreCase = true) -> "WPA2"
    capabilities.contains("WPA", ignoreCase = true) -> "WPA"
    capabilities.contains("WEP", ignoreCase = true) -> "WEP"
    else -> "Open"
}

private fun securityLabel(type: Int): String = when (type) {
    0 -> "Open"
    1 -> "WEP"
    2 -> "WPA2-PSK"
    3 -> "WPA2-EAP"
    4 -> "WPA3-SAE"
    5 -> "OWE"
    else -> "type $type"
}

private fun fetchPublicIp(): PublicIpInfo {
    val connection = URL("https://ipinfo.io/json").openConnection() as HttpURLConnection
    connection.connectTimeout = 8_000
    connection.readTimeout = 8_000
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("User-Agent", "DexLab/1.0 (Android network scope)")
    try {
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("ipinfo.io returned HTTP $code")
        }
        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(text)
        val ip = json.optString("ip").ifBlank { throw IllegalStateException("response had no ip field") }
        return PublicIpInfo(
            ip = ip,
            city = json.optString("city"),
            region = json.optString("region"),
            country = json.optString("country"),
            org = json.optString("org")
        )
    } finally {
        connection.disconnect()
    }
}
