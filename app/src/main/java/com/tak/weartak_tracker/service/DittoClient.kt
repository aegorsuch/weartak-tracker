package com.tak.weartak_tracker.service

import android.content.Context
import android.util.Log
import com.tak.weartak_tracker.BuildConfig
import com.tak.weartak_tracker.cot.Fix
import com.tak.weartak_tracker.data.DittoPdlSource
import com.tak.weartak_tracker.data.DittoState
import com.tak.weartak_tracker.data.TrackerState
import com.tak.weartak_tracker.data.TrackerConfig
import live.ditto.Ditto
import live.ditto.DittoError
import live.ditto.DittoIdentity
import live.ditto.DittoSyncSubscription
import live.ditto.android.DefaultAndroidDittoDependencies
import live.ditto.transports.DittoTransportConfig

internal interface DittoPeerSession {
    var deviceName: String
    val peerKey: String
    fun startSync()
    fun stopSync()
    fun publish(document: Map<String, Any?>)
    fun close()
}

private class AndroidDittoPeerSession(context: Context, license: DittoCommunityLicense) : DittoPeerSession {
    private val instance: Ditto
    private var subscription: DittoSyncSubscription? = null

    init {
        val dependencies = DefaultAndroidDittoDependencies(context)
        val identity = DittoIdentity.SharedKey(dependencies, appId = license.appId, sharedKey = license.sharedKey)
        instance = Ditto(dependencies, identity)
        var initialized = false
        try {
            instance.setOfflineOnlyLicenseToken(license.licenseToken)
            instance.transportConfig = DittoTransportConfig().apply { enableAllPeerToPeer() }
            subscription = instance.sync.registerSubscription("SELECT * FROM track")
            initialized = true
        } finally {
            if (!initialized) instance.close()
        }
    }

    override var deviceName: String
        get() = instance.deviceName
        set(value) { instance.deviceName = value }
    override val peerKey: String get() = instance.presence.graph.localPeer.peerKeyString
    override fun startSync() = instance.startSync()
    override fun stopSync() = instance.stopSync()
    override fun publish(document: Map<String, Any?>) {
        instance.store.write { it.scoped("track").upsert(document) }
    }
    override fun close() {
        try {
            subscription?.close()
        } finally {
            subscription = null
            instance.close()
        }
    }
}

class DittoClient internal constructor(
    private val createSession: (DittoCommunityLicense) -> DittoPeerSession,
    private val communityIdentity: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val logError: (String, Throwable) -> Unit = { message, error -> Log.e(TAG, message, error); Unit },
) {
    constructor(context: Context) : this(
        createSession = { AndroidDittoPeerSession(context.applicationContext, it) },
        communityIdentity = { BuildConfig.DITTO_COMMUNITY_IDENTITY },
    )

    private var ditto: DittoPeerSession? = null
    private var activePdl: String? = null
    private var syncing = false

    fun configure(config: TrackerConfig): DittoState {
        val pdl = when (config.dittoPdlSource) {
            DittoPdlSource.COMMUNITY -> communityIdentity()
            DittoPdlSource.CUSTOM -> config.dittoCustomPdl
        }.trim()
        if (!config.dittoEnabled) {
            return try {
                if (activePdl != pdl) stop()
                else if (syncing) ditto?.stopSync()
                syncing = false
                DittoState.Disabled
            } catch (error: DittoError) {
                logError("Ditto Edge Sync failed to stop", error)
                stop()
                DittoState.Error(error.message ?: "Ditto Edge Sync failed to stop")
            }
        }

        if (pdl.isBlank()) {
            stop()
            return DittoState.NeedsIdentity
        }

        val license = try {
            DittoCommunityLicenseHelper.parse(pdl)
        } catch (error: IllegalArgumentException) {
            stop()
            return DittoState.Error(error.message ?: "Invalid Ditto Edge Sync identity")
        }
        if (license.expiresAtMillis <= now()) {
            stop()
            return DittoState.Error("Ditto Edge Sync identity has expired")
        }

        return try {
            if (activePdl != pdl) {
                stop()
                ditto = createSession(license)
                activePdl = pdl
            }
            val instance = requireNotNull(ditto)
            instance.deviceName = config.callsign
            // The toggle pauses/resumes the existing SDK session; only identity changes or service
            // shutdown close the database. A paused instance must explicitly start syncing again.
            if (!syncing) instance.startSync()
            syncing = true
            DittoState.Active
        } catch (error: DittoError) {
            logError("Ditto Edge Sync failed to start", error)
            stop()
            DittoState.Error(error.message ?: "Ditto Edge Sync failed to start")
        } catch (error: SecurityException) {
            logError("Ditto Edge Sync requires nearby-device permissions", error)
            stop()
            DittoState.Error("Nearby-device permissions are required for Ditto Edge Sync")
        }
    }

    fun publishTrack(xml: String, fix: Fix, config: TrackerConfig, uid: String) {
        val instance = ditto?.takeIf { syncing } ?: return
        val now = System.currentTimeMillis()
        val stale = now + 3_000L * ReportingStrategy.intervalSecs(config, TrackerState.isAlerting, TrackerState.activity.value)
        val detail = DETAIL_PATTERN.find(xml)?.value ?: "<detail/>"
        val document = mapOf<String, Any?>(
            "_id" to uid,
            "_r" to false,
            "_v" to 2,
            "authorCs" to config.callsign,
            "AuthorId" to uid,
            "authorType" to "a-f-G-U-C",
            "visible" to true,
            "a" to instance.peerKey,
            "b" to now,
            "c" to config.callsign,
            "d" to uid,
            "e" to config.callsign,
            "f" to true,
            "g" to BuildConfig.VERSION_NAME,
            "h" to (fix.ce.takeIf { it.isFinite() && it >= 0f }?.toDouble() ?: 9_999_999.0),
            "i" to fix.hae,
            "j" to fix.lat,
            "k" to (fix.le.takeIf { it.isFinite() && it >= 0f }?.toDouble() ?: 9_999_999.0),
            "l" to fix.lon,
            "n" to now,
            "o" to stale,
            "p" to "m-g",
            "q" to "Undefined",
            "r" to detail,
            "r1" to fix.speed.toString(),
            "r2" to fix.hae.toString(),
            "w" to "a-f-G-U-C",
        )
        try {
            instance.publish(document)
        } catch (error: DittoError) {
            logError("Ditto Edge Sync track update failed", error)
        }
    }

    fun stop() {
        val instance = ditto
        ditto = null
        activePdl = null
        syncing = false
        instance?.let {
            try {
                instance.close()
            } catch (error: DittoError) {
                logError("Ditto Edge Sync close failed", error)
            }
        }
    }

    private companion object {
        const val TAG = "DittoClient"
        val DETAIL_PATTERN = Regex("<detail\\b[^>]*>.*?</detail>", RegexOption.DOT_MATCHES_ALL)
    }
}
