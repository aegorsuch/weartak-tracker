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

class DittoClient(context: Context) {
    private val appContext = context.applicationContext
    private var ditto: Ditto? = null
    private var trackSubscription: DittoSyncSubscription? = null
    private var activePdl: String? = null

    fun configure(config: TrackerConfig): DittoState {
        if (!config.dittoEnabled) {
            stop()
            return DittoState.Disabled
        }

        val pdl = when (config.dittoPdlSource) {
            DittoPdlSource.COMMUNITY -> BuildConfig.DITTO_COMMUNITY_IDENTITY
            DittoPdlSource.CUSTOM -> config.dittoCustomPdl
        }.trim()
        if (pdl.isBlank()) {
            stop()
            return DittoState.NeedsIdentity
        }

        val license = try {
            DittoCommunityLicenseHelper.parse(pdl)
        } catch (error: IllegalArgumentException) {
            stop()
            return DittoState.Error(error.message ?: "Invalid Ditto identity")
        }
        if (license.expiresAtMillis <= System.currentTimeMillis()) {
            stop()
            return DittoState.Error("Ditto identity has expired")
        }
        if (ditto != null && activePdl == pdl) {
            ditto?.deviceName = config.callsign
            return DittoState.Active
        }

        stop()
        return try {
            val dependencies = DefaultAndroidDittoDependencies(appContext)
            val identity = DittoIdentity.SharedKey(
                dependencies,
                appId = license.appId,
                sharedKey = license.sharedKey,
            )
            val instance = Ditto(dependencies, identity)
            ditto = instance
            instance.setOfflineOnlyLicenseToken(license.licenseToken)
            instance.deviceName = config.callsign
            instance.transportConfig = DittoTransportConfig().apply { enableAllPeerToPeer() }
            val subscription = instance.sync.registerSubscription("SELECT * FROM track")
            trackSubscription = subscription
            instance.startSync()
            activePdl = pdl
            DittoState.Active
        } catch (error: DittoError) {
            Log.e(TAG, "Ditto failed to start", error)
            stop()
            DittoState.Error(error.message ?: "Ditto failed to start")
        } catch (error: SecurityException) {
            Log.e(TAG, "Ditto requires nearby-device permissions", error)
            stop()
            DittoState.Error("Nearby-device permissions are required for Ditto")
        }
    }

    fun publishTrack(xml: String, fix: Fix, config: TrackerConfig, uid: String) {
        val instance = ditto ?: return
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
            "a" to instance.presence.graph.localPeer.peerKeyString,
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
            instance.store.write { transaction ->
                transaction.scoped("track").upsert(document)
            }
        } catch (error: DittoError) {
            Log.e(TAG, "Ditto track update failed", error)
        }
    }

    fun stop() {
        trackSubscription?.close()
        trackSubscription = null
        ditto?.let { instance ->
            try {
                // stopSync leaves the database open; close releases it before the next identity is opened.
                instance.close()
            } catch (error: DittoError) {
                Log.e(TAG, "Ditto close failed", error)
            }
        }
        ditto = null
        activePdl = null
    }

    private companion object {
        const val TAG = "DittoClient"
        val DETAIL_PATTERN = Regex("<detail\\b[^>]*>.*?</detail>", RegexOption.DOT_MATCHES_ALL)
    }
}
