package com.dexlab.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.dexlab.app.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File

sealed interface ShizukuState {
    data object NotInstalled : ShizukuState
    data object NotRunning : ShizukuState
    data object PermissionRequired : ShizukuState
    data class Ready(val uid: Int, val version: Int) : ShizukuState
    data class Unavailable(val reason: String) : ShizukuState
}

/**
 * App-side half of the Shizuku bridge. The actual logcat process runs in
 * LogcatUserService under the shell identity; this object handles the binder,
 * permission prompt, service connection and batching.
 */
object ShizukuManager {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"

    private const val PERMISSION_REQUEST = 7301
    private const val SERVICE_VERSION = 3
    private const val SERVICE_TIMEOUT_MS = 7000L

    private var context: Context? = null
    private var serviceArgs: Shizuku.UserServiceArgs? = null
    private var binding = false
    private var bound = false
    private var service: ILogcatService? = null
    private var serviceDeferred = CompletableDeferred<ILogcatService>()

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotInstalled)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val _batches = MutableSharedFlow<List<String>>(
        extraBufferCapacity = 96,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val batches: SharedFlow<List<String>> = _batches.asSharedFlow()

    private val _logRunning = MutableStateFlow(false)
    val logRunning: StateFlow<Boolean> = _logRunning.asStateFlow()

    private val _logError = MutableStateFlow<String?>(null)
    val logError: StateFlow<String?> = _logError.asStateFlow()

    private var logcatRequested = false

    private val binderReceived = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() = refresh()
    }

    private val binderDead = object : Shizuku.OnBinderDeadListener {
        override fun onBinderDead() {
            service = null
            bound = false
            binding = false
            _logRunning.value = false
            serviceDeferred = CompletableDeferred()
            refresh()
        }
    }

    private val permissionResult = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode == PERMISSION_REQUEST) refresh()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            val connected = binder?.let { ILogcatService.Stub.asInterface(it) }
            if (connected == null) {
                bound = false
                return
            }
            service = connected
            bound = true
            if (!serviceDeferred.isCompleted) serviceDeferred.complete(connected)
            if (logcatRequested) startOnService(connected)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            bound = false
            service = null
            _logRunning.value = false
            serviceDeferred = CompletableDeferred()
        }
    }

    fun init(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun refresh() {
        val ctx = context ?: return
        _state.value = runCatching {
            when {
                !Shizuku.pingBinder() ->
                    if (isInstalled(ctx)) ShizukuState.NotRunning else ShizukuState.NotInstalled
                Shizuku.isPreV11() -> ShizukuState.Unavailable("Shizuku v11 or newer is required")
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    ShizukuState.PermissionRequired
                else -> ShizukuState.Ready(Shizuku.getUid(), Shizuku.getVersion())
            }
        }.getOrElse {
            ShizukuState.Unavailable(it.message ?: it.javaClass.simpleName)
        }
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            refresh()
            return
        }
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }
            .onFailure { _state.value = ShizukuState.Unavailable(it.message ?: "permission request failed") }
    }

    fun isReady(): Boolean = state.value is ShizukuState.Ready

    fun isInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun openShizuku(ctx: Context): Boolean {
        val launch = runCatching { ctx.packageManager.getLaunchIntentForPackage(PACKAGE) }.getOrNull()
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { ctx.startActivity(launch) }.isSuccess
        }
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(web) }.isSuccess
    }

    private fun args(ctx: Context): Shizuku.UserServiceArgs {
        serviceArgs?.let { return it }
        val created = Shizuku.UserServiceArgs(ComponentName(ctx, LogcatUserService::class.java))
            .daemon(false)
            .processNameSuffix("dexlab")
            .debuggable(BuildConfig.DEBUG)
            .version(SERVICE_VERSION)
        serviceArgs = created
        return created
    }

    private fun bindIfNeeded() {
        val ctx = context ?: return
        if (bound || binding) return
        if (state.value !is ShizukuState.Ready) return
        binding = true
        runCatching { Shizuku.bindUserService(args(ctx), connection) }
            .onFailure {
                binding = false
                _state.value = ShizukuState.Unavailable(it.message ?: "could not bind the shell service")
            }
    }

    private suspend fun awaitService(): ILogcatService? {
        service?.let { return it }
        if (!isReady()) return null
        val ctx = context ?: return null
        return withContext(Dispatchers.Main) {
            bindIfNeeded()
            val connected = withTimeoutOrNull(SERVICE_TIMEOUT_MS) { serviceDeferred.await() }
            // A timed-out bind must not leave the binding latch set forever.
            if (connected == null && !bound) binding = false
            connected
        }
    }

    suspend fun startLogcat(): Boolean {
        val ctx = context ?: return false
        val s = awaitService() ?: run {
            _logError.value = "Shizuku did not connect. Start Shizuku and grant DexLab access."
            return false
        }
        logcatRequested = true
        _logError.value = null
        return runCatching {
            s.start(logCallback, arrayOf("-T", "200"))
            _logRunning.value = true
            true
        }.getOrElse {
            _logError.value = it.message ?: it.javaClass.simpleName
            _logRunning.value = false
            false
        }
    }

    private fun startOnService(s: ILogcatService) {
        runCatching {
            s.start(logCallback, arrayOf("-T", "200"))
            _logRunning.value = true
            _logError.value = null
        }.onFailure {
            _logError.value = it.message ?: it.javaClass.simpleName
            _logRunning.value = false
        }
    }

    fun stopLogcat() {
        logcatRequested = false
        runCatching { service?.stop() }
        _logRunning.value = false
    }

    suspend fun copyInstalledApks(packageName: String, destination: File): List<File> {
        return withContext(Dispatchers.IO) {
            val s = awaitService() ?: error("Shizuku is not ready. Start Shizuku and grant DexLab access.")
            val paths = s.packagePaths(packageName).filter { it.isNotBlank() }
            if (paths.isEmpty()) error("Shizuku could not find APKs for $packageName")
            destination.mkdirs()
            paths.mapIndexed { index, path ->
                val pfd = s.openRead(path) ?: error("Shizuku could not open $path")
                val original = File(path).name.ifBlank { "base.apk" }
                val out = File(destination, if (index == 0) original else "${index}_$original")
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out
            }
        }
    }

    private val logCallback = object : ILogcatCallback.Stub() {
        override fun onLines(lines: Array<out String>?) {
            if (!lines.isNullOrEmpty()) _batches.tryEmit(lines.toList())
        }

        override fun onError(message: String?) {
            _logError.value = message ?: "Shizuku logcat failed"
            _logRunning.value = false
        }

        override fun onStopped() {
            _logRunning.value = false
        }
    }
}
