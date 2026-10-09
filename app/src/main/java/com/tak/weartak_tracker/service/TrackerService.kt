package com.tak.weartak_tracker.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.TrackerApp
import com.tak.weartak_tracker.cot.AlertState
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.CotTime
import com.tak.weartak_tracker.cot.Fix
import com.tak.weartak_tracker.cot.Physio
import com.tak.weartak_tracker.data.Activity
import com.tak.weartak_tracker.data.Endpoint
import com.tak.weartak_tracker.data.LocationAccess
import com.tak.weartak_tracker.data.ManualAlert
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.TrackerState
import com.tak.weartak_tracker.transport.MulticastPublisher
import com.tak.weartak_tracker.transport.NetworkMonitor
import com.tak.weartak_tracker.transport.SitxClient
import com.tak.weartak_tracker.transport.TakChannelManager
import com.tak.weartak_tracker.transport.TakServerManager
import com.tak.weartak_tracker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The tracker's only long-running component: owns transports, GPS scheduling,
 * PLI reporting and manual-alert store-and-forward.
 */
class TrackerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: SettingsRepository
    private lateinit var network: NetworkMonitor
    private lateinit var tak: TakServerManager
    private lateinit var channels: TakChannelManager
    private lateinit var sitx: SitxClient
    private lateinit var multicast: MulticastPublisher
    private lateinit var location: LocationEngine
    private lateinit var forwarder: AlertForwarder

    private val alertMutex = Mutex()
    private var config = TrackerConfig()
    private var lastFix: Fix? = null
    private var lastLocationElapsed = 0L
    private var lastPliElapsed = 0L
    private var appliedInterval = 0
    private val movement = MovementClassifier()
    private lateinit var motion: MotionTrigger
    private lateinit var physio: PhysioMonitor
    private var physioActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    /** Persists a discovered TLS name unless one is already saved (saved names are never replaced). */
    private fun saveTlsName(discoveredOn: TakServerConfig, api: Boolean, name: String) {
        scope.launch {
            repo.update { c ->
                c.copy(
                    servers = c.servers.map { s ->
                        when {
                            s.id != discoveredOn.id || s.endpointKey != discoveredOn.endpointKey -> s
                            api && s.apiTlsName.isBlank() -> s.copy(apiTlsName = name)
                            !api && s.tlsName.isBlank() -> s.copy(tlsName = name)
                            else -> s
                        }
                    },
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) {
            stopSelf()
            return
        }
        TrackerState.serviceRunning.value = true
        TrackerState.locationAccess.value = LocationAccess.current(this)
        repo = (application as TrackerApp).settings
        network = NetworkMonitor(this)
        tak = TakServerManager(
            this, scope, repo.deviceUid,
            onConnected = { scope.launch { onEndpointConnected() } },
            onTlsNameDiscovered = ::saveTlsName,
            onServerConnected = { id -> channels.onConnected(id) },
            onChannelsChanged = { id -> channels.onServerNotice(id) },
        )
        channels = TakChannelManager(scope, tak, repo.deviceUid)
        sitx = SitxClient(repo, scope, repo.deviceUid) { scope.launch { onEndpointConnected() } }
        multicast = MulticastPublisher { scope.launch { onEndpointConnected() } }
        location = LocationEngine(this, ::onLocation) { lastFix?.let { scope.launch { sendPli(it) } } }
        forwarder = AlertForwarder(
            send = ::sendAlert,
            onChanged = { alerts, _ -> TrackerState.alerts.value = alerts },
        )
        TrackerState.alerts.value = emptyList()
        TrackerState.activity.value = movement.current
        motion = MotionTrigger(this) { TrackerState.activity.value = movement.onMotion() }
        physio = PhysioMonitor(this)

        network.start()
        location.lastKnown { it?.let { loc -> if (lastFix == null) lastFix = loc.toFix() } }

        scope.launch {
            var previous: TrackerConfig? = null
            repo.config.collect { c ->
                val identityChanged = c.identityChangedFrom(previous)
                previous = c
                config = c
                applyPhysio()
                tak.update(c.servers)
                channels.retain(c.servers.filter { it.enabled }.map { it.id }.toSet())
                sitx.update(c)
                configureMulticast()
                if (identityChanged) {
                    location.requestSingleFix()
                    lastFix?.let { sendPli(it) }
                }
            }
        }
        scope.launch {
            // Only watch for motion while dynamic reporting has dropped to the stationary interval.
            combine(repo.config, TrackerState.activity) { c, a -> c.dynamicReporting && a == Activity.STILL }
                .distinctUntilChanged()
                .collect { still -> if (still) motion.arm() else motion.disarm() }
        }
        scope.launch { network.wifiNetwork.collect { configureMulticast() } }
        scope.launch {
            network.defaultNetwork.drop(1).collect { net ->
                if (net != null) {
                    tak.reconnectAll()
                    sitx.onNetworkChanged()
                    rebroadcastPli()
                }
            }
        }
        scope.launch {
            combine(repo.config, TrackerState.alerts, TrackerState.activity) { c, alerts, activity ->
                ReportingStrategy.intervalSecs(c, alerts.any { it.state == AlertState.ALERT }, activity)
            }.distinctUntilChanged().collectLatest { interval ->
                TrackerState.reportingIntervalSecs.value = interval
                if (interval != appliedInterval) {
                    appliedInterval = interval
                    location.start(interval)
                }
                // Fallback scheduler: report the last fix when GPS has gone quiet for a whole interval.
                while (true) {
                    delay(interval * 1000L)
                    val fix = lastFix ?: continue
                    if (SystemClock.elapsedRealtime() - lastLocationElapsed > interval * 1000L &&
                        SystemClock.elapsedRealtime() - lastPliElapsed >= interval * 1000L - 500
                    ) sendPli(fix)
                }
            }
        }
        scope.launch {
            combine(TrackerState.endpoints, TrackerState.reportingIntervalSecs, TrackerState.locationAccess) { e, i, a ->
                Triple(e, i, a)
            }.collect { (endpoints, interval, access) -> updateNotification(endpoints, interval, access) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Permissions may have changed since the service started (e.g. granted from the app UI).
        if (::location.isInitialized) {
            TrackerState.locationAccess.value = LocationAccess.current(this)
            location.refreshAccess()
            // Heart-rate access may have just been granted from the physio toggle.
            applyPhysio()
        }
        when (intent?.action) {
            ACTION_REBROADCAST_PLI -> scope.launch { rebroadcastPli() }
            ACTION_LOCATION_ALARM -> if (::location.isInitialized) location.onAlarm()
            ACTION_RAISE_ALERT -> {
                val uid = intent.getStringExtra(EXTRA_UID)
                val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
                scope.launch {
                    val alert = forwarder.newAlert(description).let { if (uid != null) it.copy(uid = uid) else it }
                    submit(alert)
                }
            }
            ACTION_CANCEL_ALERT -> scope.launch {
                val uid = intent.getStringExtra(EXTRA_UID)
                alertMutex.withLock {
                    val active = forwarder.alerts.filter { it.state == AlertState.ALERT }
                    val target = active.firstOrNull { it.uid == uid } ?: active.maxByOrNull { it.timeMillis }
                    target?.let { submitLocked(it.copy(state = AlertState.CANCEL, enqueued = false)) }
                }
            }
            ACTION_SITX_REAUTHORIZE -> if (::sitx.isInitialized) sitx.reauthorize()
            ACTION_SITX_REFRESH_GROUPS -> if (::sitx.isInitialized) sitx.refreshGroups()
            ACTION_TAK_SERVER_RETRY -> intent.getStringExtra(EXTRA_SERVER_ID)?.let { id ->
                scope.launch {
                    val saved = repo.config.first()
                    tak.update(saved.servers)
                    if (!tak.retry(id)) {
                        Log.w(TAG, "Cannot retry TAK Server: missing, disabled, invalid, or duplicate configuration")
                    }
                }
            }
            ACTION_TAK_CHANNELS_REFRESH -> intent.getStringExtra(EXTRA_SERVER_ID)?.let { id ->
                if (::channels.isInitialized) channels.refresh(id)
            }
            ACTION_TAK_CHANNEL_TOGGLE -> intent.getStringExtra(EXTRA_SERVER_ID)?.let { id ->
                if (::channels.isInitialized && intent.hasExtra(EXTRA_BIT_POSITION)) {
                    channels.toggle(id, intent.getIntExtra(EXTRA_BIT_POSITION, -1), intent.getBooleanExtra(EXTRA_ACTIVE, false))
                }
            }
        }
        // Never restarted by the system: reporting only runs after the user opens the app.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::location.isInitialized) {
            location.stop()
            motion.disarm()
            physio.setEnabled(false)
            tak.stop()
            channels.clear()
            sitx.stop()
            multicast.close()
            network.stop()
        }
        scope.cancel()
        TrackerState.serviceRunning.value = false
        TrackerState.activity.value = Activity.UNKNOWN
        TrackerState.sitx.value = com.tak.weartak_tracker.data.SitxState.Disabled
        super.onDestroy()
    }

    // ---- Location / PLI ----

    private fun onLocation(loc: Location) {
        val fix = loc.toFix()
        lastFix = fix
        lastLocationElapsed = SystemClock.elapsedRealtime()
        TrackerState.lastFix.value = fix
        TrackerState.activity.value = movement.onSpeed(loc.usableSpeed())
        scope.launch {
            sendPli(fix)
            if (isReady()) alertMutex.withLock { forwarder.flush() }
        }
    }

    private suspend fun sendPli(fix: Fix) {
        val xml = CotBuilder.pli(
            uid = repo.deviceUid,
            callsign = config.callsign,
            team = config.team,
            role = config.role,
            battery = battery(),
            fix = fix,
            time = CotTime.now(CotBuilder.pliStaleSeconds(
                ReportingStrategy.intervalSecs(config, TrackerState.isAlerting, TrackerState.activity.value),
            )),
            physio = if (physioActive) {
                val bpm = physio.bpm
                Physio(
                    bpm, physio.skinTempF,
                    com.tak.weartak_tracker.cot.calculateExertion(
                        bpm, config.medicalProfile.ageYears(), config.medicalProfile.restingHeartRateBpm,
                    ),
                )
            } else null,
            includeBatdok = config.batdokEnabled,
            ageYears = config.medicalProfile.ageYears(),
        )
        lastPliElapsed = SystemClock.elapsedRealtime()
        // Same routing as CIV: TAK when connected, SITX and multicast whenever available.
        var routes = 0
        if (tak.anyConnected) routes += tak.send(xml)
        routes += withContext(Dispatchers.IO) { sitx.send(xml) }
        routes += multicast.send(xml)
        if (routes > 0) TrackerState.lastPliMillis.value = System.currentTimeMillis()
    }

    // ---- Alerts ----

    private fun isReady(): Boolean =
        tak.anyConnected || multicast.running || (config.sitxEnabled && sitx.connected)

    private suspend fun submit(item: ManualAlert) = alertMutex.withLock {
        submitLocked(item)
    }

    private suspend fun submitLocked(item: ManualAlert) {
        val ready = isReady()
        location.requestSingleFix()
        forwarder.submit(item, ready)
    }

    private suspend fun onEndpointConnected() {
        alertMutex.withLock { if (isReady()) forwarder.flush() }
        rebroadcastPli()
    }

    private suspend fun rebroadcastPli() {
        val fix = lastFix
        if (fix != null) sendPli(fix) else location.requestSingleFix()
    }

    private suspend fun sendAlert(alert: ManualAlert): Boolean {
        val xml = CotBuilder.emergency(
            alertUid = alert.uid,
            state = alert.state,
            category = alert.category,
            description = alert.description,
            priority = alert.priority,
            deviceUid = repo.deviceUid,
            callsign = config.callsign,
            fix = lastFix,
            time = CotTime.now(CotBuilder.ALERT_STALE_SECONDS),
        )
        var routes = 0
        if (multicast.running) routes += multicast.send(xml)
        if (tak.anyConnected) routes += tak.send(xml)
        if (config.sitxEnabled && sitx.connected) routes += withContext(Dispatchers.IO) { sitx.send(xml) }
        Log.i(TAG, "${alert.state} ${alert.uid} sent via $routes route(s)")
        return routes > 0
    }

    // ---- Helpers ----

    private fun configureMulticast() {
        val c = config
        val wifi = network.wifiNetwork.value
        scope.launch(Dispatchers.IO) { multicast.configure(c.multicastEnabled, c.multicastAddress, c.multicastPort, wifi) }
    }

    /**
     * Speed for the movement classifier, or null when it can't be trusted: approximate location is coarsened
     * by the system, and fixes without speed (or with very poor speed accuracy) say nothing about movement.
     */
    private fun Location.usableSpeed(): Float? {
        if (TrackerState.locationAccess.value != LocationAccess.PRECISE || !hasSpeed()) return null
        if (hasSpeedAccuracy() && speedAccuracyMetersPerSecond > MAX_SPEED_ACCURACY_MPS) return null
        return speed
    }

    private fun battery(): Int? =
        getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    private fun goForeground(): Boolean {
        if (!LocationAccess.current(this).granted) return false
        return try {
            startForeground(NOTIFICATION_ID, notification(getString(R.string.notification_starting)), foregroundTypes())
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start foreground service", e)
            false
        }
    }

    /** Health is added only while physio is on and heart-rate access is granted. */
    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= 34 && config.physioMonitoring && ::physio.isInitialized && physio.permissionGranted()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        return types
    }

    /** Applies the physio setting; the foreground type must include health before the sensor is read. */
    private fun applyPhysio() {
        val wanted = config.physioMonitoring && physio.available && physio.permissionGranted()
        if (wanted == physioActive) return
        if (wanted && !goForeground()) return
        physioActive = physio.setEnabled(wanted)
        if (!wanted) goForeground()
    }

    private fun updateNotification(endpoints: Set<Endpoint>, interval: Int, access: LocationAccess) {
        var text = getString(R.string.notification_status, interval, endpoints.size)
        if (access == LocationAccess.APPROXIMATE) text += getString(R.string.notification_approximate_suffix)
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, TrackerApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.location_on)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .build()

    private fun Location.toFix() = Fix(
        lat = latitude,
        lon = longitude,
        hae = if (hasAltitude()) altitude else 0.0,
        ce = if (hasAccuracy()) accuracy else Float.NaN,
        le = if (hasVerticalAccuracy()) verticalAccuracyMeters else Float.NaN,
        course = if (hasBearing()) bearing else 0f,
        speed = if (hasSpeed()) speed else 0f,
        timeMillis = time,
    )

    companion object {
        private const val TAG = "TrackerService"
        private const val NOTIFICATION_ID = 1
        private const val MAX_SPEED_ACCURACY_MPS = 2f

        const val ACTION_LOCATION_ALARM = "com.tak.weartak_tracker.LOCATION_ALARM"
        const val ACTION_REBROADCAST_PLI = "com.tak.weartak_tracker.REBROADCAST_PLI"
        const val ACTION_RAISE_ALERT = "com.tak.weartak_tracker.RAISE_ALERT"
        const val ACTION_CANCEL_ALERT = "com.tak.weartak_tracker.CANCEL_ALERT"
        const val ACTION_SITX_REAUTHORIZE = "com.tak.weartak_tracker.SITX_REAUTHORIZE"
        const val ACTION_SITX_REFRESH_GROUPS = "com.tak.weartak_tracker.SITX_REFRESH_GROUPS"
        const val ACTION_TAK_SERVER_RETRY = "com.tak.weartak_tracker.TAK_SERVER_RETRY"
        const val ACTION_TAK_CHANNELS_REFRESH = "com.tak.weartak_tracker.TAK_CHANNELS_REFRESH"
        const val ACTION_TAK_CHANNEL_TOGGLE = "com.tak.weartak_tracker.TAK_CHANNEL_TOGGLE"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_BIT_POSITION = "bit_position"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_UID = "uid"
        const val EXTRA_DESCRIPTION = "description"

        fun start(context: Context, action: String? = null, extras: Intent.() -> Unit = {}) {
            if (!LocationAccess.current(context).granted) return
            val intent = Intent(context, TrackerService::class.java).setAction(action).apply(extras)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackerService::class.java))
        }
    }
}
