package com.tak.weartak_tracker.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.TrackerApp
import com.tak.weartak_tracker.cot.AlertState
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.CotTime
import com.tak.weartak_tracker.cot.Fix
import com.tak.weartak_tracker.data.Endpoint
import com.tak.weartak_tracker.data.ManualAlert
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.TrackerState
import com.tak.weartak_tracker.transport.MulticastPublisher
import com.tak.weartak_tracker.transport.NetworkMonitor
import com.tak.weartak_tracker.transport.SitxClient
import com.tak.weartak_tracker.transport.TakServerManager
import com.tak.weartak_tracker.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private lateinit var sitx: SitxClient
    private lateinit var multicast: MulticastPublisher
    private lateinit var location: LocationEngine
    private lateinit var forwarder: AlertForwarder

    private val restored = CompletableDeferred<Unit>()
    private val alertMutex = Mutex()
    private var forcedFlushJob: Job? = null
    private var config = TrackerConfig()
    private var lastFix: Fix? = null
    private var lastLocationElapsed = 0L
    private var lastPliElapsed = 0L
    private var appliedInterval = 0
    private var activityUpdatesOn = false

    private val anyEndpoint: Boolean get() = TrackerState.endpoints.value.isNotEmpty()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) {
            stopSelf()
            return
        }
        TrackerState.serviceRunning.value = true
        repo = (application as TrackerApp).settings
        network = NetworkMonitor(this)
        tak = TakServerManager(this, scope, repo.deviceUid) { scope.launch { onEndpointConnected() } }
        sitx = SitxClient(repo, scope, repo.deviceUid) { scope.launch { onEndpointConnected() } }
        multicast = MulticastPublisher { scope.launch { onEndpointConnected() } }
        location = LocationEngine(this, ::onLocation) { lastFix?.let { scope.launch { sendPli(it) } } }
        forwarder = AlertForwarder(
            send = ::sendAlert,
            onChanged = { alerts, queue ->
                TrackerState.alerts.value = alerts
                scope.launch(Dispatchers.IO) { repo.saveAlertState(AlertForwarder.encode(alerts, queue)) }
            },
        )

        scope.launch {
            val (alerts, queue) = AlertForwarder.decode(repo.loadAlertState())
            forwarder.restore(alerts, queue)
            restored.complete(Unit)
        }

        network.start()
        location.lastKnown { it?.let { loc -> if (lastFix == null) lastFix = loc.toFix() } }

        scope.launch {
            repo.config.collect { c ->
                config = c
                if (!c.reportingEnabled) {
                    stopSelf()
                    return@collect
                }
                tak.update(c.servers)
                sitx.update(c)
                configureMulticast()
                updateActivityUpdates()
            }
        }
        scope.launch { network.wifiNetwork.collect { configureMulticast() } }
        scope.launch {
            network.defaultNetwork.drop(1).collect { net ->
                if (net != null) {
                    tak.reconnectAll()
                    sitx.onNetworkChanged()
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
            combine(TrackerState.endpoints, TrackerState.reportingIntervalSecs) { e, i -> e to i }
                .collect { (endpoints, interval) -> updateNotification(endpoints, interval) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_LOCATION_ALARM -> if (::location.isInitialized) location.onAlarm()
            ACTION_RAISE_ALERT -> {
                val uid = intent.getStringExtra(EXTRA_UID)
                val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
                scope.launch {
                    restored.await()
                    val alert = forwarder.newAlert(description).let { if (uid != null) it.copy(uid = uid) else it }
                    submit(alert)
                }
            }
            ACTION_CANCEL_ALERT -> scope.launch {
                restored.await()
                val uid = intent.getStringExtra(EXTRA_UID)
                val active = forwarder.alerts.filter { it.state == AlertState.ALERT }
                val target = active.firstOrNull { it.uid == uid } ?: active.maxByOrNull { it.timeMillis }
                target?.let { submit(it.copy(state = AlertState.CANCEL, enqueued = false)) }
            }
            ACTION_SITX_REAUTHORIZE -> if (::sitx.isInitialized) sitx.reauthorize()
            ACTION_SITX_REFRESH_GROUPS -> if (::sitx.isInitialized) sitx.refreshGroups()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::location.isInitialized) {
            location.stop()
            stopActivityUpdates()
            tak.stop()
            sitx.stop()
            multicast.close()
            network.stop()
        }
        scope.cancel()
        TrackerState.serviceRunning.value = false
        TrackerState.sitx.value = com.tak.weartak_tracker.data.SitxState.Disabled
        super.onDestroy()
    }

    // ---- Location / PLI ----

    private fun onLocation(loc: Location) {
        val fix = loc.toFix()
        lastFix = fix
        lastLocationElapsed = SystemClock.elapsedRealtime()
        TrackerState.lastFix.value = fix
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
            time = CotTime.now(appliedInterval.coerceAtLeast(1) * CotBuilder.PLI_STALE_MULTIPLIER),
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
        anyEndpoint && (lastLocationElapsed == 0L || SystemClock.elapsedRealtime() - lastLocationElapsed < FRESH_FIX_MS)

    private suspend fun submit(item: ManualAlert) = alertMutex.withLock {
        val ready = isReady()
        if (!ready && anyEndpoint) requestFixThenFlush()
        forwarder.submit(item, ready)
    }

    private suspend fun onEndpointConnected() {
        restored.await()
        if (forwarder.pending.isEmpty()) return
        if (isReady()) alertMutex.withLock { forwarder.flush() } else requestFixThenFlush()
    }

    /** Ask for a fresh fix; the location callback flushes. If GPS cannot deliver, flush anyway after a timeout. */
    private fun requestFixThenFlush() {
        location.requestSingleFix()
        forcedFlushJob?.cancel()
        forcedFlushJob = scope.launch {
            delay(FORCED_FLUSH_MS)
            if (anyEndpoint) alertMutex.withLock { forwarder.flush() }
        }
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

    private fun updateActivityUpdates() {
        val want = config.dynamicReporting && granted(Manifest.permission.ACTIVITY_RECOGNITION)
        if (want == activityUpdatesOn) return
        if (want) {
            runCatching {
                ActivityRecognition.getClient(this).requestActivityUpdates(ACTIVITY_INTERVAL_MS, activityIntent())
                activityUpdatesOn = true
            }.onFailure { Log.w(TAG, "Activity recognition unavailable", it) }
        } else {
            stopActivityUpdates()
        }
    }

    private fun stopActivityUpdates() {
        if (!activityUpdatesOn) return
        runCatching { ActivityRecognition.getClient(this).removeActivityUpdates(activityIntent()) }
        activityUpdatesOn = false
    }

    private fun activityIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 2, Intent(this, ActivityReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun battery(): Int? =
        getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun goForeground(): Boolean {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return false
        }
        return try {
            startForeground(NOTIFICATION_ID, notification(getString(R.string.notification_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start foreground service", e)
            false
        }
    }

    private fun updateNotification(endpoints: Set<Endpoint>, interval: Int) {
        val text = getString(R.string.notification_status, interval, endpoints.size)
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
        private const val FRESH_FIX_MS = 3_000L
        private const val FORCED_FLUSH_MS = 10_000L
        private const val ACTIVITY_INTERVAL_MS = 5_000L

        const val ACTION_LOCATION_ALARM = "com.tak.weartak_tracker.LOCATION_ALARM"
        const val ACTION_RAISE_ALERT = "com.tak.weartak_tracker.RAISE_ALERT"
        const val ACTION_CANCEL_ALERT = "com.tak.weartak_tracker.CANCEL_ALERT"
        const val ACTION_SITX_REAUTHORIZE = "com.tak.weartak_tracker.SITX_REAUTHORIZE"
        const val ACTION_SITX_REFRESH_GROUPS = "com.tak.weartak_tracker.SITX_REFRESH_GROUPS"
        const val EXTRA_UID = "uid"
        const val EXTRA_DESCRIPTION = "description"

        private fun hasLocation(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context, action: String? = null, extras: Intent.() -> Unit = {}) {
            if (!hasLocation(context)) return
            val intent = Intent(context, TrackerService::class.java).setAction(action).apply(extras)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackerService::class.java))
        }

        /** Boot start: Android only allows a background location FGS with background location granted. */
        fun startIfEnabled(context: Context, fromBoot: Boolean, onDone: () -> Unit = {}) {
            if (fromBoot && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                onDone()
                return
            }
            val app = context.applicationContext as TrackerApp
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    if (app.settings.config.first().reportingEnabled) start(context)
                } finally {
                    onDone()
                }
            }
        }
    }
}
