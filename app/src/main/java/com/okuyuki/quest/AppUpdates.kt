package com.okuyuki.quest

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal class AppUpdates(private val context: Context, private val scope: CoroutineScope) {
    var status by mutableStateOf("idle"); private set
    var release by mutableStateOf<UpdateRelease?>(null); private set
    var showPrompt by mutableStateOf(false)
    private var job: Job? = null
    private var apk: File? = null
    companion object { private val downloadLock = Mutex() }

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            status = "checking"
            try {
                val found = withContext(Dispatchers.IO) { findRelease() }
                release = found
                if (found == null) status = "current" else {
                    status = "downloading"
                    apk = withContext(Dispatchers.IO) { downloadLock.withLock { download(found) } }
                    status = "ready"
                    showPrompt = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "error" }
        }
    }

    private suspend fun findRelease(): UpdateRelease? {
        val repo = "quest"
        val base = "https://github.com/lingmulongtai/Okuyuki-$repo"
        try {
            val json = readText("https://api.github.com/repos/lingmulongtai/Okuyuki-$repo/releases?per_page=30", 8_000_000)
            return ReleaseCatalog.find(json, BuildConfig.VERSION_NAME, repo)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val tag = ReleaseCatalog.latestFeedTag(readText("$base/releases.atom", 2_000_000), BuildConfig.VERSION_NAME, repo) ?: return null
            val sums = readText("$base/releases/download/$tag/SHA256SUMS.txt", 64_000)
            return ReleaseCatalog.fromChecksums(tag, sums, repo) ?: error("Missing APK checksum")
        }
    }

    private suspend fun readText(url: String, limit: Int): String {
        val connection = connect(url)
        try {
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit)
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally { connection.disconnect() }
    }

    private suspend fun download(update: UpdateRelease): File {
        val folder = File(context.cacheDir, "updates")
        check(folder.isDirectory || folder.mkdirs())
        val destination = File(folder, "${update.digest}.apk")
        if (destination.isFile && verify(destination, update)) return destination
        val partial = File.createTempFile("update-", ".apk", folder)
        try {
            val connection = connect(update.url)
            try {
                var total = 0L
                connection.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= if (update.size == 0L) 536_870_912L else update.size)
                        output.write(buffer, 0, count)
                    }
                } }
                require(total > 0 && (update.size == 0L || total == update.size))
            } finally { connection.disconnect() }
            check(verify(partial, update))
            currentCoroutineContext().ensureActive()
            if (destination.exists()) check(destination.delete())
            check(partial.renameTo(destination))
            return destination
        } finally { partial.delete() }
    }

    private suspend fun verify(file: File, update: UpdateRelease): Boolean {
        if (file.length() !in 1..536_870_912L || (update.size != 0L && file.length() != update.size)) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != update.digest) return false
        return try {
            val pm = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            @Suppress("DEPRECATION") val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
            @Suppress("DEPRECATION") val installed = pm.getPackageInfo(context.packageName, flags)
            if (archive.packageName != context.packageName || archive.longVersionCode <= installed.longVersionCode) return false
            val candidateSigners = archive.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet()
            val installedSigners = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet()
            !candidateSigners.isNullOrEmpty() && candidateSigners == installedSigners
        } catch (_: Exception) { false }
    }

    fun install() {
        try {
            val file = apk
            if (file?.isFile != true) { status = "error"; return }
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            showPrompt = false
        } catch (_: Exception) { status = "install_error" }
    }

    private fun connect(address: String): HttpURLConnection {
        var target = URL(address)
        repeat(6) {
            require(target.protocol == "https" && target.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            val connection = target.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 40_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Okuyuki-Updater")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: error("Missing redirect")
                target = URL(target, location)
                connection.disconnect()
            } else {
                if (code != 200) { connection.disconnect(); error("HTTP $code") }
                return connection
            }
        }
        error("Too many redirects")
    }
}

@Composable internal fun rememberAppUpdates(): AppUpdates {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember(context) { AppUpdates(context, scope) }
    LaunchedEffect(updater) {
        while (true) {
            if (updater.status != "ready" && updater.status != "install_error") updater.check()
            while (updater.status == "checking" || updater.status == "downloading") delay(1_000L)
            delay(if (updater.status == "error") 15 * 60_000L else 6 * 60 * 60_000L)
        }
    }
    return updater
}

@Composable internal fun UpdateSettings(updater: AppUpdates) {
    Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(when (updater.status) {
        "checking" -> R.string.update_checking
        "downloading" -> R.string.update_downloading
        "ready" -> R.string.update_ready
        "current" -> R.string.update_current
        "error" -> R.string.update_error
        "install_error" -> R.string.update_install_error
        else -> R.string.update_description
    }))
    if (updater.status == "ready" || updater.status == "install_error") {
        Button(onClick = { updater.showPrompt = true }) { Text(stringResource(R.string.update_install)) }
    } else {
        OutlinedButton(onClick = updater::check, enabled = updater.status != "checking" && updater.status != "downloading") {
            Text(stringResource(R.string.update_check))
        }
    }
}

@Composable internal fun UpdatePrompt(updater: AppUpdates) {
    if (updater.showPrompt) AlertDialog(
        onDismissRequest = { updater.showPrompt = false },
        title = { Text(stringResource(R.string.update_available)) },
        text = { Text(if (updater.status == "install_error") stringResource(R.string.update_install_error)
            else stringResource(R.string.update_prompt, updater.release?.version ?: "")) },
        confirmButton = { TextButton(onClick = updater::install) { Text(stringResource(R.string.update_install)) } },
        dismissButton = { TextButton(onClick = { updater.showPrompt = false }) { Text(stringResource(R.string.update_later)) } },
    )
}
