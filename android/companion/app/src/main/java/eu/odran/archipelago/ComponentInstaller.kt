package eu.odran.archipelago

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class InstalledApkState(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val alternateBuild: Boolean = false,
)

internal object ComponentDownloadStore {
    fun verifiedFile(context: Context, asset: ComponentAsset): File? {
        val file = File(directory(context), asset.fileName)
        if (!file.isFile || file.length() != asset.byteCount) return null
        return file.takeIf { it.sha256Hex() == asset.sha256 }
    }

    fun download(
        context: Context,
        asset: ComponentAsset,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        verifiedFile(context, asset)?.let { return it }
        val limit = if (asset.component.kind == ComponentKind.APK) MAX_APK_BYTES else MAX_CORE_BYTES
        require(asset.byteCount in 1..limit) { "${asset.fileName} is larger than the updater permits." }
        val directory = directory(context)
        val destination = File(directory, asset.fileName)
        val temporary = File(directory, ".${asset.fileName}.part")
        check(!temporary.exists() || temporary.delete()) { "Could not clear the interrupted download." }

        val request = Request.Builder()
            .url(asset.downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Archipelago-Companion-Android")
            .build()
        try {
            HTTP_CLIENT.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download returned HTTP ${response.code}." }
                check(response.request.url.isHttps) { "The release download was redirected away from HTTPS." }
                val body = checkNotNull(response.body) { "The release download was empty." }
                val responseLength = body.contentLength()
                if (responseLength >= 0) {
                    check(responseLength == asset.byteCount) { "The release asset size changed unexpectedly." }
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                body.byteStream().buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            check(written <= asset.byteCount) { "The release asset exceeded its declared size." }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            onProgress(written, asset.byteCount)
                        }
                    }
                }
                check(written == asset.byteCount) { "The release download ended early." }
                val actualDigest = digest.digest().toHex()
                check(actualDigest == asset.sha256) { "The release download failed SHA-256 verification." }
            }
            if (destination.exists()) check(destination.delete()) { "Could not replace the cached update." }
            check(temporary.renameTo(destination)) { "Could not finish the cached update." }
            return destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun directory(context: Context) = File(context.cacheDir, "component_updates").apply {
        check(isDirectory || mkdirs()) { "Could not create the update cache." }
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val MAX_APK_BYTES = 300L * 1024L * 1024L
    private const val MAX_CORE_BYTES = 32L * 1024L * 1024L
    private val HTTP_CLIENT = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}

internal object ApkComponentInstaller {
    fun installedState(context: Context, component: ManagedComponent): InstalledApkState? {
        require(component.kind == ComponentKind.APK)
        packageInfo(context.packageManager, checkNotNull(component.packageName))?.let { info ->
            return info.toInstalledState(alternate = false)
        }
        component.alternatePackageName?.let { alternate ->
            packageInfo(context.packageManager, alternate)?.let { info ->
                return info.toInstalledState(alternate = true)
            }
        }
        return null
    }

    fun verify(context: Context, asset: ComponentAsset, file: File) {
        require(asset.component.kind == ComponentKind.APK)
        val expectedPackage = checkNotNull(asset.component.packageName)
        val archive = archiveInfo(context.packageManager, file)
            ?: error("Android could not read the downloaded APK.")
        require(archive.packageName == expectedPackage) {
            "The downloaded APK belongs to ${archive.packageName}, not $expectedPackage."
        }
        require(archive.versionName == asset.version) {
            "The downloaded APK version ${archive.versionName} does not match release ${asset.version}."
        }
        packageInfo(context.packageManager, expectedPackage)?.let { installed ->
            val installedSigners = signerDigests(installed)
            val archiveSigners = signerDigests(archive)
            require(installedSigners.isNotEmpty() && archiveSigners.isNotEmpty() &&
                installedSigners.intersect(archiveSigners).isNotEmpty()
            ) { "The downloaded APK is not signed by the installed app's signing identity." }
        }
    }

    fun requestInstallPermission(activity: Activity) {
        activity.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"),
            ),
        )
    }

    fun canRequestInstalls(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun launchInstaller(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 28) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            packageManager.getPackageInfo(packageName, flags)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun archiveInfo(packageManager: PackageManager, file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 28) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures: List<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptySet()
            buildList {
                signing.apkContentsSigners?.let(::addAll)
                signing.signingCertificateHistory?.let(::addAll)
            }.distinct()
        } else {
            info.signatures?.toList().orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.toInstalledState(alternate: Boolean): InstalledApkState {
        val code = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
        return InstalledApkState(packageName, versionName.orEmpty(), code, alternate)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
