package com.tak.weartak_tracker.market

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.tak.weartak_tracker.BuildConfig
import java.io.File
import java.io.FileInputStream

internal data class InstalledMarketPackage(
    val versionCode: Long,
    val versionName: String?,
    val signerSha256: Set<String>,
)

internal sealed interface MarketInstallResult {
    data class Submitted(val sessionId: Int) : MarketInstallResult
    data object PermissionRequired : MarketInstallResult
    data class Blocked(val state: MarketInstallState) : MarketInstallResult
}

internal class MarketInstallCoordinator(
    private val context: Context,
    private val catalogRepository: MarketCatalogRepository,
) {
    private val packageManager = context.packageManager
    private val hostPackage = context.packageName

    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:$hostPackage"))

    @Suppress("DEPRECATION")
    fun installedPackage(packageName: String): InstalledMarketPackage? {
        val info = try {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        } catch (error: SecurityException) {
            throw MarketCatalogException("Cannot inspect installed package.", error)
        }
        return installedPackage(info)
            ?: throw MarketCatalogException("Cannot verify installed package signer.")
    }

    fun installState(plugin: MarketPlugin): MarketInstallState {
        val installed = installedPackage(plugin.packageName)
        val playManaged = plugin.packageName == hostPackage &&
            installed != null && isPlayManagedHostInstall()
        return MarketSecurityPolicy.installState(
            plugin = plugin,
            hostPackage = hostPackage,
            hostVersionCode = BuildConfig.VERSION_CODE.toLong(),
            installedVersionCode = installed?.versionCode,
            installedSignerSha256 = installed?.signerSha256,
            playManagedHost = playManaged,
        )
    }

    suspend fun install(plugin: MarketPlugin): MarketInstallResult {
        if (!canInstallPackages()) return MarketInstallResult.PermissionRequired

        val state = installState(plugin)
        if (state != MarketInstallState.INSTALL && state != MarketInstallState.UPDATE) {
            return MarketInstallResult.Blocked(state)
        }

        val apk = catalogRepository.downloadVerified(plugin, context.cacheDir)
        try {
            verifyArchive(plugin, apk)
            if (!canInstallPackages()) return MarketInstallResult.PermissionRequired
            val currentState = installState(plugin)
            if (currentState != MarketInstallState.INSTALL && currentState != MarketInstallState.UPDATE) {
                return MarketInstallResult.Blocked(currentState)
            }
            return submitInstall(plugin, apk)
        } finally {
            apk.delete()
        }
    }

    private fun isPlayManagedHostInstall(): Boolean =
        try {
            val source = packageManager.getInstallSourceInfo(hostPackage)
            source.installingPackageName == PLAY_STORE_PACKAGE ||
                source.initiatingPackageName == PLAY_STORE_PACKAGE ||
                source.originatingPackageName == PLAY_STORE_PACKAGE
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Cannot verify host install source; blocking sideload update", error)
            true
        }

    @Suppress("DEPRECATION")
    private fun verifyArchive(plugin: MarketPlugin, apk: File) {
        val info = packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: throw MarketCatalogException("Downloaded file is not a valid Android package.")
        val archive = installedPackage(info)
            ?: throw MarketCatalogException("Downloaded package has no verifiable signer.")
        if (!MarketSecurityPolicy.archiveMatches(
                plugin = plugin,
                packageName = info.packageName,
                versionCode = archive.versionCode,
                signerSha256 = archive.signerSha256,
            )
        ) {
            throw MarketCatalogException("Downloaded package identity or version does not match catalog.")
        }
        val installed = installedPackage(plugin.packageName)
        if (installed != null && installed.signerSha256 != archive.signerSha256) {
            throw MarketCatalogException("Installed package signer does not match update.")
        }
    }

    private fun installedPackage(info: PackageInfo): InstalledMarketPackage? {
        val signers = info.signingInfo?.apkContentsSigners
            ?.map { sha256(it.toByteArray()) }
            ?.toSet()
            ?: return null
        if (signers.isEmpty()) return null
        return InstalledMarketPackage(
            versionCode = info.longVersionCode,
            versionName = info.versionName,
            signerSha256 = signers,
        )
    }

    private fun submitInstall(plugin: MarketPlugin, apk: File): MarketInstallResult.Submitted {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(plugin.packageName) }
        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                FileInputStream(apk).use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, MarketInstallStatusReceiver::class.java)
                    .setAction("$STATUS_ACTION.$sessionId")
                    .putExtra(MarketInstallStatusReceiver.EXTRA_PACKAGE_NAME, plugin.packageName)
                    .putExtra(MarketInstallStatusReceiver.EXTRA_SESSION_ID, sessionId)
                val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val statusIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
                )
                session.commit(statusIntent.intentSender)
                committed = true
            }
            return MarketInstallResult.Submitted(sessionId)
        } finally {
            if (!committed) installer.abandonSession(sessionId)
        }
    }

    companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
        private const val TAG = "TakwerxMarket"
        private const val STATUS_ACTION = "com.tak.weartak_tracker.market.INSTALL_STATUS"
    }
}
