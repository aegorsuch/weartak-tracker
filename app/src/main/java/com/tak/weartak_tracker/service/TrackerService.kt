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
import com.tak.weartak_tracker.diagnostics.AppLog as Log
import com.tak.weartak_tracker.diagnostics.Diagnostics
import com.tak.weartak_tracker.diagnostics.DiagnosticEvent
import com.tak.weartak_tracker.diagnostics.DiagnosticDetail
import androidx.core.content.ContextCompat
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.TrackerApp
import com.tak.weartak_tracker.command.ChatCommandProcessor
import com.tak.weartak_tracker.cot.AlertState
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.CotTime
import com.tak.weartak_tracker.cot.Fix
import com.tak.weartak_tracker.cot.InboundChat
import com.tak.weartak_tracker.cot.DittoChatDocument
import com.tak.weartak_tracker.cot.Physio
import com.tak.weartak_tracker.data.Activity
import com.tak.weartak_tracker.data.Endpoint
import com.tak.weartak_tracker.data.LocationAccess
import com.tak.weartak_tracker.data.ManualAlert
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.TrackerState
import com.tak.weartak_tracker.data.runtimeTakServers
import com.tak.weartak_tracker.transport.MulticastChatReceiver
import com.tak.weartak_tracker.transport.MulticastPublisher
import com.tak.weartak_tracker.transport.NetworkMonitor
import com.tak.weartak_tracker.transport.SitxClient
import com.tak.weartak_tracker.transport.TakChannelManager
import com.tak.weartak_tracker.transport.TakServerManager
import com.tak.weartak_tracker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The tracker's only long-running component: owns transports, GPS scheduling,
 * PLI reporting and manual-alert store-and-forward.
 */
class TrackerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: SettingsRepository
    private lateinit var network: NetworkMonitor
    private lateinit var tak: TakServerManager
    private lateinit var chatCommands: ChatCommandProcessor
    private lateinit var channels: TakChannelManager
    private lateinit var sitx: SitxClient
    private lateinit var multicast: MulticastPublisher
    private lateinit var multicastChat: MulticastChatReceiver
    private lateinit var ditto: DittoClient
    private lateinit var location: LocationEngine
    private lateinit var forwarder: AlertForwarder

    private val alertMutex = Mutex()
    @Volatile private var config = TrackerConfig()
    private val multicastKick = Channel<Unit>(Channel.CONFLATED)
    private var multicastWorker: Job? = null
    private var lastFix: Fix? = null
    private var lastFixAccess = LocationAccess.NONE
    /** Elapsed time of the newest fix, so an alert press can briefly wait for the fix it requested. */
    private val fixArrivals = MutableStateFlow(0L)
    private val resendPolicy = AlertResendPolicy()
    private val reportingCadence = ReportingCadence()
    private var reportingSpeed: ReportingSpeed? = null
    private var currentSpeedProvider: String? = null
    private lateinit var observation: ObservationCadence
    private val movement = MovementClassifier()
    private lateinit var motion: MotionTrigger
    private lateinit var physio: PhysioMonitor
    private var physioActive = false
    private val pliRefresh = PliRefreshCoordinator(scope, { rebroadcastPli() })
    private val outboundRefresh = OutboundPliRefresh()
    private var identityRefreshPending = false

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
        if (!(application as TrackerApp).uiResumed) {
            TrackingResume.show(this)
            stopSelf()
            return
        }
        if (!goForeground()) {
            TrackingResume.show(this)
            stopSelf()
            return
        }
        TrackerState.serviceRunning.value = true
        TrackingResume.clear(this)
        TrackerState.locationAccess.value = LocationAccess.current(this)
        repo = (application as TrackerApp).settings
        network = NetworkMonitor(this)
        val chatRooms = DittoChatRooms(this)
        ditto = DittoClient(this, onReady = { pliRefresh.request() }) { raw, peer ->
            val text = raw["msg"] as? String
            if (text != null && com.tak.weartak_tracker.command.ChatCommandParser.hasCommandPrefix(text.trim())) {
                // Main (not Main.immediate) always queues execution outside the native observer callback.
                scope.launch(Dispatchers.Main) {
                    if (!config.dittoEnabled || !config.chatCommandsEnabled || !::chatCommands.isInitialized) return@launch
                    val request = try {
                        DittoChatDocument.request(raw, repo.deviceUid, peer, System.currentTimeMillis(),
                            knownGroupMembers = chatRooms.members(raw["roomId"] as? String ?: ""))
                    } catch (error: Exception) {
                        Log.w(TAG, "Ditto chat rejected (${error.javaClass.simpleName})")
                        null
                    }
                    if (request != null) chatCommands.handle(request)
                }
            }
        }
        chatCommands = ChatCommandRuntime.create(this, repo, { tak }, ::configureDitto, ditto::replyChat) { xml ->
            multicast.sendTo(xml, MulticastChatReceiver.CHAT_ADDRESS, MulticastChatReceiver.CHAT_PORT)
        }
        tak = TakServerManager(
            this, scope, repo.deviceUid,
            onConnected = { scope.launch { onEndpointConnected() } },
            onTlsNameDiscovered = ::saveTlsName,
            onServerConnected = { id -> channels.onConnected(id) },
            onChannelsChanged = { id -> channels.onServerNotice(id) },
            inboundEnabled = { config.chatCommandsEnabled },
            onInboundEvent = { id, xml -> handleInboundChat(id, xml) },
        )
        channels = TakChannelManager(scope, tak, repo.deviceUid)
        sitx = SitxClient(repo, scope, repo.deviceUid) { scope.launch { onEndpointConnected() } }
        multicast = MulticastPublisher { scope.launch { onEndpointConnected() } }
        multicastChat = MulticastChatReceiver(this, ::handleInboundChat)
        location = LocationEngine(this, ::onLocation) { scope.launch { reportIfDue() } }
        forwarder = AlertForwarder(
            send = ::sendAlert,
            onChanged = { alerts, _ -> TrackerState.alerts.value = alerts },
        )
        TrackerState.alerts.value = emptyList()
        TrackerState.activity.value = movement.current
        motion = MotionTrigger(this) {
            TrackerState.activity.value = movement.onMotion()
            reportingSpeed = null
            configureObservationInterval(freshFix = true)
        }
        observation = ObservationCadence(
            armMotion = motion::arm,
            disarmMotion = motion::disarm,
            schedule = location::start,
            requestFix = { location.requestSingleFix(forcePli = false) },
            stopLocation = location::stop,
        )
        physio = PhysioMonitor(this)

        network.start()
        location.lastKnown { it?.let { loc ->
            if (lastFix == null) {
                lastFix = loc.toFix()
                lastFixAccess = LocationAccess.current(this)
            }
        } }
        // Starting tracking publishes the wearer's position, so get a fresh one rather than waiting for the schedule.
        location.requestSingleFix()

        scope.launch {
            var previous: TrackerConfig? = null
            repo.config.collect { c ->
                val identityChanged = c.identityChangedFrom(previous)
                val reportingChanged = previous?.let {
                    it.dynamicReporting != c.dynamicReporting || it.constantInterval != c.constantInterval ||
                        it.stationaryInterval != c.stationaryInterval || it.onFootInterval != c.onFootInterval ||
                        it.vehicleInterval != c.vehicleInterval || it.alertingInterval != c.alertingInterval
                } ?: true
                val dittoConfigChanged = previous?.let {
                    it.dittoEnabled != c.dittoEnabled ||
                        it.dittoPdlSource != c.dittoPdlSource ||
                        it.dittoCustomPdl != c.dittoCustomPdl
                } ?: true
                previous = c
                config = c
                applyPhysio()
                applyTakConfig(c)
                sitx.update(c)
                if (c.dittoEnabled && dittoConfigChanged) {
                    TrackerState.ditto.value = com.tak.weartak_tracker.data.DittoState.Starting
                }
                configureDitto(c)
                configureMulticast()
                outboundRefresh.update(readyPliRoutes())
                if (reportingChanged) configureObservationInterval()
                if (identityChanged) {
                    identityRefreshPending = true
                    location.requestSingleFix()
                    pliRefresh.request()
                }
            }
        }
        scope.launch { network.wifiNetwork.collect { configureMulticast() } }
        multicastWorker = scope.launch(Dispatchers.IO) { runMulticastWorker() }
        scope.launch {
            network.defaultNetwork.drop(1).collect { net ->
                if (net != null) {
                    tak.reconnectAll()
                    sitx.onNetworkChanged()
                }
            }
        }
        scope.launch {
            TrackerState.alerts.map { alerts -> alerts.any { it.state == AlertState.ALERT } }
                .distinctUntilChanged().collect { configureObservationInterval() }
        }
        scope.launch {
            while (true) {
                delay(1000)
                reportIfDue()
            }
        }
        scope.launch {
            combine(TrackerState.endpoints, TrackerState.reportingIntervalSecs, TrackerState.locationAccess) { e, i, a ->
                Triple(e, i, a)
            }.collect { (endpoints, interval, access) -> updateNotification(endpoints, interval, access) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::location.isInitialized) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            TrackingResume.show(this)
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
            null, ACTION_LOCATION_INTERACTION -> configureObservationInterval(freshFix = true)
            ACTION_REBROADCAST_PLI -> scope.launch { manualPli() }
            ACTION_LOCATION_ALARM -> if (::location.isInitialized) location.onAlarm()
            ACTION_RAISE_ALERT -> {
                val uid = intent.getStringExtra(EXTRA_UID)
                val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
                scope.launch {
                    val alert = forwarder.newAlert(description).let { if (uid != null) it.copy(uid = uid) else it }
                    awaitFreshFix()
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
            ACTION_SITX_RENEW_REAUTH_PIN -> if (::sitx.isInitialized) {
                val pin = intent.getStringExtra(EXTRA_SITX_REAUTH_PIN).orEmpty()
                sitx.renewWithReauthPin(pin) { result ->
                    TrackerState.sitxRenewalMessage.value = if (result == SitxClient.REAUTH_PIN_REJECTED) {
                        getString(R.string.reauth_pin_rejected)
                    } else if (result == SitxClient.REAUTH_PIN_NOT_AUTHORIZED) {
                        getString(R.string.not_authorized)
                    } else {
                        val expiration = runCatching { java.time.Instant.parse(result).toEpochMilli() }.getOrNull()
                        if (expiration == null) result
                        else getString(
                            R.string.renewed_until,
                            android.text.format.DateFormat.getDateFormat(this).format(java.util.Date(expiration)),
                        )
                    }
                }
            }
            ACTION_TAK_SERVER_RETRY -> intent.getStringExtra(EXTRA_SERVER_ID)?.let { id ->
                scope.launch {
                    val saved = repo.config.first()
                    applyTakConfig(saved)
                    if (saved.networkPreferencesLocked || !tak.retry(id)) {
                        Log.w(TAG, "Cannot retry TAK Server: locked, missing, disabled, invalid, or duplicate configuration")
                    }
                }
            }
            ACTION_TAK_CHANNELS_REFRESH -> intent.getStringExtra(EXTRA_SERVER_ID)?.let { id ->
                scope.launch {
                    val saved = repo.current()
                    applyTakConfig(saved)
                    if (!saved.networkPreferencesLocked) channels.refresh(id)
                }
            }
            ACTION_TAK_CHANNEL_TOGGLE -> intent.getStringExtra(EXTRA_SERVER_ID)?.let { id ->
                if (::channels.isInitialized && intent.hasExtra(EXTRA_BIT_POSITION)) {
                    scope.launch {
                        val saved = repo.current()
                        applyTakConfig(saved)
                        if (!saved.networkPreferencesLocked) {
                            channels.toggle(id, intent.getIntExtra(EXTRA_BIT_POSITION, -1), intent.getBooleanExtra(EXTRA_ACTIVE, false))
                        }
                    }
                }
            }
        }
        // Never restarted by the system: reporting only runs after the user opens the app.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::location.isInitialized) {
            observation.stop()
            physio.setEnabled(false)
            tak.stop()
            channels.clear()
            sitx.stop()
            multicastWorker?.cancel()
            multicast.close()
            multicastChat.close()
            ditto.stop()
            network.stop()
        }
        TrackerState.setEndpoint(Endpoint.DITTO, false)
        TrackerState.ditto.value = com.tak.weartak_tracker.data.DittoState.Disabled
        scope.cancel()
        TrackerState.serviceRunning.value = false
        TrackerState.activity.value = Activity.UNKNOWN
        TrackerState.sitx.value = com.tak.weartak_tracker.data.SitxState.Disabled
        super.onDestroy()
    }

    // ---- Location / PLI ----

    private fun onLocation(loc: Location, forcePli: Boolean) {
        val fix = loc.toFix()
        lastFix = fix
        lastFixAccess = LocationAccess.current(this)
        fixArrivals.value = SystemClock.elapsedRealtime()
        TrackerState.lastFix.value = fix
        currentSpeedProvider = loc.provider
        reportingSpeed = ReportingSpeed(
            loc.usableSpeed()?.toDouble(),
            fix.elapsedRealtimeMillis?.takeIf { it >= location.inputStartedElapsed },
            loc.provider,
            fix.elapsedRealtimeMillis?.let(location::speedValidUntil),
        )
        val validSpeed = currentReportingSpeed()
        if (validSpeed == null) movement.reset() else movement.onSpeed(validSpeed.toFloat())
        TrackerState.activity.value = movement.current
        configureObservationInterval()
        reportingSpeed = reportingSpeed?.copy(validUntilElapsedMillis = fix.elapsedRealtimeMillis?.let(location::speedValidUntil))
        val speed = reportingSpeed?.current(SystemClock.elapsedRealtime(), currentSpeedProvider, location.inputStartedElapsed)
        Diagnostics.record(DiagnosticEvent.SPEED, when {
            lastFixAccess != LocationAccess.PRECISE || speed == null -> DiagnosticDetail.UNKNOWN
            speed < ReportingStrategy.MOTION_BOUNDARY_MPS -> DiagnosticDetail.STATIONARY
            else -> DiagnosticDetail.MOVING
        })
        scope.launch {
            outboundRefresh.update(readyPliRoutes())
            if (forcePli || identityRefreshPending || outboundRefresh.needed) sendPli(fix) else reportIfDue()
            alertMutex.withLock {
                if (isReady()) forwarder.flush()
                updateActiveAlerts()
            }
        }
    }

    private fun currentReportingSpeed(): Double? =
        if (LocationAccess.current(this) == LocationAccess.PRECISE && lastFixAccess == LocationAccess.PRECISE) {
            reportingSpeed?.current(SystemClock.elapsedRealtime(), currentSpeedProvider, location.inputStartedElapsed)
        } else null

    private fun currentReportingInterval(): Int =
        ReportingStrategy.intervalForSpeedSecs(config, TrackerState.isAlerting, currentReportingSpeed(), movement.current)

    private fun configureObservationInterval(freshFix: Boolean = false) {
        val observed = reportingSpeed
        val speed = currentReportingSpeed()
        // A stale/missing speed or a new moving fix cannot justify an hour-long stationary wait.
        val activity = ReportingStrategy.observationActivity(movement.current, speed)
        observation.update(config, activity, TrackerState.isAlerting, freshFix)
        if (speed != null) {
            reportingSpeed = observed?.copy(validUntilElapsedMillis = observed.acquiredElapsedMillis?.let(location::speedValidUntil))
        }
    }

    private suspend fun reportIfDue() {
        configureObservationInterval()
        val interval = currentReportingInterval()
        TrackerState.reportingIntervalSecs.value = interval
        if (reportingCadence.isDue(SystemClock.elapsedRealtime(), interval)) lastFix?.let { sendPli(it) }
    }

    private suspend fun sendPli(fix: Fix) {
        val access = LocationAccess.current(this)
        if (!access.granted) return
        outboundRefresh.update(readyPliRoutes())
        if (lastFixAccess != access ||
            (outboundRefresh.needed && !fix.isCurrentPliFix(SystemClock.elapsedRealtime(), FRESH_FIX_MS))
        ) {
            requestRefreshFix()
            return
        }
        val pending = outboundRefresh.snapshot()
        pliRefresh.onPliSent()
        identityRefreshPending = false
        val xml = CotBuilder.pli(
            uid = repo.deviceUid,
            callsign = config.callsign,
            team = config.team,
            role = config.role,
            battery = battery(),
            fix = fix,
            time = CotTime.now(CotBuilder.pliStaleSeconds(
                currentReportingInterval(),
            ), fix.timeMillis),
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
            moppStatus = config.moppStatus,
        )
        reportingCadence.onSent(SystemClock.elapsedRealtime())
        TrackerState.reportingIntervalSecs.value = currentReportingInterval()
        // Same routing as CIV: TAK when connected, SITX and multicast whenever available.
        var routes = 0
        val sent = mutableMapOf<String, Long>()
        if (tak.anyConnected) {
            val delivered = tak.sendWithGenerations(xml)
            routes += delivered.size
            delivered.forEach { (id, generation) -> sent["tak:$id"] = generation }
        }
        val sitxGeneration = sitx.readyGeneration
        val sitxRoutes = withContext(Dispatchers.IO) { sitx.send(xml) }
        routes += sitxRoutes
        if (sitxRoutes > 0 && sitxGeneration != null) sent["sitx"] = sitxGeneration
        val multicastGeneration = multicast.readyGeneration
        val multicastRoutes = multicast.send(CotBuilder.meshContact(xml))
        routes += multicastRoutes
        if (multicastRoutes > 0 && multicastGeneration != null) sent["multicast"] = multicastGeneration
        if (routes > 0) TrackerState.lastPliMillis.value = System.currentTimeMillis()
        val dittoGeneration = ditto.readyGeneration
        if (ditto.publishTrack(xml, fix, config, repo.deviceUid) > 0 && dittoGeneration != null) {
            sent["ditto"] = dittoGeneration
        }
        outboundRefresh.onPublished(sent.filter { (source, generation) -> pending[source] == generation })
    }

    // ---- Alerts ----

    private fun applyTakConfig(saved: TrackerConfig) {
        val runtime = saved.runtimeTakServers
        channels.retain(runtime.filter { it.enabled }.map { it.id }.toSet())
        tak.update(runtime)
        outboundRefresh.update(readyPliRoutes())
    }

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
        requestOutboundRefresh()
    }

    private fun readyPliRoutes(): Map<String, Long> = buildMap {
        tak.readyGenerations.forEach { (id, generation) -> put("tak:$id", generation) }
        if (config.sitxEnabled) sitx.readyGeneration?.let { put("sitx", it) }
        if (config.multicastEnabled) multicast.readyGeneration?.let { put("multicast", it) }
        if (config.dittoEnabled) ditto.readyGeneration?.let { put("ditto", it) }
    }

    private fun requestOutboundRefresh() {
        outboundRefresh.update(readyPliRoutes())
        if (outboundRefresh.needed) pliRefresh.request()
    }

    private suspend fun rebroadcastPli() {
        outboundRefresh.update(readyPliRoutes())
        if (!outboundRefresh.needed && !identityRefreshPending) return
        refreshCurrentPli(lastFix, lastFixAccess, LocationAccess.current(this), ::requestRefreshFix, ::sendPli)
    }

    private fun requestRefreshFix() {
        if (outboundRefresh.takeFixRequest()) location.requestSingleFix()
    }

    /** User-requested PLI: send the best fix now, and the fresh fix requested here sends another when it lands. */
    private suspend fun manualPli() {
        location.requestSingleFix()
        lastFix?.let { sendPli(it) }
    }

    /**
     * Alert press: actively request a fix and wait briefly for it, so the alert usually carries a current
     * position. Never waits when the last fix is already fresh, nor longer than [ALERT_FIX_WAIT_MS].
     */
    private suspend fun awaitFreshFix() {
        val pressed = SystemClock.elapsedRealtime()
        location.requestSingleFix()
        if (lastFix?.isCurrentPliFix(pressed, FRESH_FIX_MS) == true) return
        withTimeoutOrNull(ALERT_FIX_WAIT_MS) {
            fixArrivals.first {
                it > pressed && lastFix?.isCurrentPliFix(SystemClock.elapsedRealtime(), FRESH_FIX_MS) == true
            }
        }
    }

    /**
     * Each alert is delivered once. The only resend is for an alert that went out before any fix existed:
     * the first fix sends it once more with a real position. Newer fixes never resend; the wearer's PLI
     * keeps reporting position while alerting. Caller holds [alertMutex].
     */
    private suspend fun updateActiveAlerts() {
        val active = forwarder.alerts.filter { it.state == AlertState.ALERT && !it.enqueued }
        val due = resendPolicy.dueOnFix(active.map { it.uid })
        active.filter { it.uid in due }.forEach { sendAlert(it) }
    }

    private suspend fun sendAlert(alert: ManualAlert): Boolean {
        val fix = lastFix
        val xml = CotBuilder.emergency(
            alertUid = alert.uid,
            state = alert.state,
            category = alert.category,
            description = alert.description,
            priority = alert.priority,
            deviceUid = repo.deviceUid,
            callsign = config.callsign,
            fix = fix,
            time = CotTime.now(CotBuilder.ALERT_STALE_SECONDS),
        )
        var routes = 0
        if (multicast.running) routes += multicast.send(xml)
        if (tak.anyConnected) routes += tak.send(xml)
        if (config.sitxEnabled && sitx.connected) routes += withContext(Dispatchers.IO) { sitx.send(xml) }
        Log.i(TAG, "${alert.state} ${alert.uid} sent via $routes route(s)")
        if (routes > 0) resendPolicy.onSent(alert.uid, alert.state, hadFix = fix != null)
        return routes > 0
    }

    // ---- Helpers ----

    private fun configureDitto(c: TrackerConfig): com.tak.weartak_tracker.data.DittoState {
        val state = ditto.configure(c)
        TrackerState.ditto.value = state
        TrackerState.setEndpoint(Endpoint.DITTO, state == com.tak.weartak_tracker.data.DittoState.Active)
        return state
    }

    private fun handleInboundChat(source: String, xml: String) {
        val request = InboundChat.commandRequest(
            xml, repo.deviceUid, source, System.currentTimeMillis(), config.callsign,
        ) { reason -> Log.i(TAG, "Chat command ignored: $reason") } ?: return
        Log.i(TAG, "Chat command from ${request.senderCallsign} via $source")
        scope.launch { chatCommands.handle(request) }
    }

    private fun configureMulticast() {
        network.setMulticastEnabled(config.multicastEnabled)
        multicastKick.trySend(Unit)
    }

    /**
     * One worker applies multicast state, always reading the latest config and Wi-Fi network, so a stale
     * call can't land after a newer one. It also re-applies periodically to retry sockets that failed.
     */
    private suspend fun runMulticastWorker() {
        while (currentCoroutineContext().isActive) {
            withTimeoutOrNull(MULTICAST_RETRY_MS) { multicastKick.receive() }
            currentCoroutineContext().ensureActive()
            val c = config
            val wifi = network.wifiNetwork.value
            multicast.configure(c.multicastEnabled, c.multicastAddress, c.multicastPort, wifi)
            multicastChat.configure(
                c.chatCommandsEnabled && c.multicastEnabled,
                setOf(c.multicastAddress.trim() to c.multicastPort, MulticastChatReceiver.CHAT_ADDRESS to MulticastChatReceiver.CHAT_PORT),
                wifi,
            )
        }
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

    private fun battery(): Int? {
        val percent = getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        Diagnostics.record(DiagnosticEvent.POWER, when {
            percent == null -> DiagnosticDetail.UNKNOWN
            percent <= 15 -> DiagnosticDetail.LOW_BATTERY
            else -> DiagnosticDetail.NORMAL
        })
        return percent
    }

    private fun goForeground(): Boolean {
        if (!LocationAccess.current(this).granted) return false
        return try {
            startForeground(NOTIFICATION_ID, notification(getString(R.string.notification_starting)), foregroundTypes())
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Unable to start foreground service", e)
            false
        } catch (e: SecurityException) {
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
        elapsedRealtimeMillis = elapsedRealtimeNanos.takeIf { it > 0 }?.div(1_000_000),
    )

    companion object {
        private const val TAG = "TrackerService"
        private const val NOTIFICATION_ID = 1
        private const val MAX_SPEED_ACCURACY_MPS = 2f
        /** A fix newer than this is current enough to send an alert without waiting. */
        private const val FRESH_FIX_MS = 3_000L
        /** Longest an alert press waits for the fix it requested before sending with the best known one. */
        private const val ALERT_FIX_WAIT_MS = 3_000L
        private const val MULTICAST_RETRY_MS = 15_000L

        const val ACTION_LOCATION_ALARM = "com.tak.weartak_tracker.LOCATION_ALARM"
        const val ACTION_LOCATION_INTERACTION = "com.tak.weartak_tracker.LOCATION_INTERACTION"
        const val ACTION_REBROADCAST_PLI = "com.tak.weartak_tracker.REBROADCAST_PLI"
        const val ACTION_RAISE_ALERT = "com.tak.weartak_tracker.RAISE_ALERT"
        const val ACTION_CANCEL_ALERT = "com.tak.weartak_tracker.CANCEL_ALERT"
        const val ACTION_SITX_REAUTHORIZE = "com.tak.weartak_tracker.SITX_REAUTHORIZE"
        const val ACTION_SITX_REFRESH_GROUPS = "com.tak.weartak_tracker.SITX_REFRESH_GROUPS"
        const val ACTION_SITX_RENEW_REAUTH_PIN = "com.tak.weartak_tracker.SITX_RENEW_REAUTH_PIN"
        const val EXTRA_SITX_REAUTH_PIN = "sitx_reauth_pin"
        const val ACTION_TAK_SERVER_RETRY = "com.tak.weartak_tracker.TAK_SERVER_RETRY"
        const val ACTION_TAK_CHANNELS_REFRESH = "com.tak.weartak_tracker.TAK_CHANNELS_REFRESH"
        const val ACTION_TAK_CHANNEL_TOGGLE = "com.tak.weartak_tracker.TAK_CHANNEL_TOGGLE"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_BIT_POSITION = "bit_position"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_UID = "uid"
        const val EXTRA_DESCRIPTION = "description"

        fun start(context: Context, action: String? = null, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, TrackerService::class.java).setAction(action).apply(extras)
            requestTrackingStart(
                locationGranted = LocationAccess.current(context).granted,
                serviceRunning = TrackerState.serviceRunning.value,
                uiResumed = (context.applicationContext as TrackerApp).uiResumed,
                start = { ContextCompat.startForegroundService(context, intent) },
                deferred = { TrackingResume.show(context) },
                failed = { error ->
                    Log.w(TAG, "Tracking start rejected; waiting for user to resume", error)
                    TrackingResume.show(context)
                },
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackerService::class.java))
        }
    }
}
