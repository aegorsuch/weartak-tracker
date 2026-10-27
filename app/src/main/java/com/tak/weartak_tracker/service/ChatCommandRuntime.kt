package com.tak.weartak_tracker.service

import android.content.Context
import com.tak.weartak_tracker.diagnostics.AppLog as Log
import com.tak.weartak_tracker.command.ChatCommand
import com.tak.weartak_tracker.command.ChatCommandProcessor
import com.tak.weartak_tracker.command.ChatCommandRequest
import com.tak.weartak_tracker.command.ChatCommandResult
import com.tak.weartak_tracker.command.ChatCommandAuthorizer
import com.tak.weartak_tracker.command.UseP12Handler
import com.tak.weartak_tracker.command.P12Installer
import com.tak.weartak_tracker.command.ToggleDittoHandler
import com.tak.weartak_tracker.command.DittoToggleStore
import com.tak.weartak_tracker.transport.P12CredentialStore
import com.tak.weartak_tracker.command.CustomAlertSink
import com.tak.weartak_tracker.command.DittoPdlApplyResult
import com.tak.weartak_tracker.command.DittoPdlStore
import com.tak.weartak_tracker.command.SetPdlHandler
import com.tak.weartak_tracker.command.EnrollServerHandler
import com.tak.weartak_tracker.command.EnrollServerOutcome
import com.tak.weartak_tracker.command.LoadProfileHandler
import com.tak.weartak_tracker.command.PresetServer
import com.tak.weartak_tracker.command.PresetServerAddResult
import com.tak.weartak_tracker.command.PresetServerSink
import com.tak.weartak_tracker.command.PresetSource
import com.tak.weartak_tracker.command.ProfileHandler
import com.tak.weartak_tracker.command.ProfileStore
import com.tak.weartak_tracker.command.ProfileUpdate
import com.tak.weartak_tracker.command.NetworkCommandStore
import com.tak.weartak_tracker.command.NetworkCommandTransport
import com.tak.weartak_tracker.command.NetworkCommandApplyResult
import com.tak.weartak_tracker.command.NetworkTransportCommandHandler
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.data.CustomAlerts
import com.tak.weartak_tracker.data.DittoState
import com.tak.weartak_tracker.BuildConfig
import com.tak.weartak_tracker.data.ProfilePresets
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.runtimeTakServers
import com.tak.weartak_tracker.transport.TakServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Builds the chat command processor over the tracker's settings, TAK server connections and mesh chat. */
object ChatCommandRuntime {
    private const val TAG = "ChatCommands"
    const val MULTICAST_SOURCE_PREFIX = "multicast:"

    /**
     * Replies go back the way the command came: over the TAK Server connections, or to the mesh chat
     * group via [multicastChat] when the command arrived over multicast.
     */
    fun create(
        context: Context,
        repo: SettingsRepository,
        tak: () -> TakServerManager,
        applyDitto: (TrackerConfig) -> DittoState,
        dittoChat: (String) -> Int,
        multicastChat: suspend (String) -> Int,
    ): ChatCommandProcessor {
        val sendReplyResult: suspend (ChatCommandRequest, String) -> Boolean = { request, message ->
            val c = repo.config.first()
            val xml = replyXml(repo.deviceUid, c.callsign, request, message)
            val routes = routeReply(request.sourceServerId, xml, dittoChat, multicastChat, tak()::sendTo)
            if (routes == 0) Log.w(TAG, "Command ack could not be published on ${request.sourceServerId}")
            else Log.i(TAG, "Command ack published locally via ${request.sourceServerId}; remote delivery unconfirmed")
            routes > 0
        }
        val sendReply: suspend (ChatCommandRequest, String) -> Unit = { request, message ->
            sendReplyResult(request, message)
            Unit
        }
        val store = ProfileStore { update -> repo.update { c -> c.withProfile(update) } }
        val networkLocked: suspend () -> Boolean = { repo.config.first().networkPreferencesLocked }
        val networkStore = NetworkCommandStore { transport, enabled ->
            var locked = false
            repo.update { c ->
                locked = c.networkPreferencesLocked
                if (locked) c else when (transport) {
                    NetworkCommandTransport.TAK_SERVERS -> c.copy(takServersEnabled = enabled)
                    NetworkCommandTransport.MULTICAST -> c.copy(multicastEnabled = enabled)
                }
            }
            if (locked) NetworkCommandApplyResult(applied = false, locked = true)
            else NetworkCommandApplyResult(applied = true)
        }
        fun networkHandler(transport: NetworkCommandTransport, enabled: Boolean) =
            NetworkTransportCommandHandler(
                transport = transport,
                enabled = enabled,
                networkLocked = networkLocked,
                store = networkStore,
                beforeDisableReply = sendReplyResult,
                ackFailure = { Log.w(TAG, "${transport.label} disable ack publication failed") },
            )
        val handlers = ProfileHandler.COMMAND_KEYS.mapValues { (_, keys) -> ProfileHandler(keys, store) } +
            mapOf(
                "enabletakservers" to networkHandler(NetworkCommandTransport.TAK_SERVERS, true),
                "disabletakservers" to networkHandler(NetworkCommandTransport.TAK_SERVERS, false),
                "enablemulticast" to networkHandler(NetworkCommandTransport.MULTICAST, true),
                "disablemulticast" to networkHandler(NetworkCommandTransport.MULTICAST, false),
            ) +
            ("loadprofile" to loadProfileHandler(context, repo)) +
            ("enrollserver" to EnrollServerHandler({ server -> upsertEnrollServer(repo, server) }, networkLocked)) +
            ("toggleditto" to ToggleDittoHandler(
                networkLocked = networkLocked,
                store = DittoToggleStore { enabled ->
                    var locked = false
                    repo.update { c ->
                        locked = c.networkPreferencesLocked
                        if (locked) c else c.copy(dittoEnabled = enabled)
                    }
                    if (locked) DittoPdlApplyResult(applied = false, locked = true)
                    else {
                        val state = applyDitto(repo.current())
                        DittoPdlApplyResult(state == DittoState.Active || state == DittoState.Disabled)
                    }
                },
                beforeDisableReply = sendReply,
                ackFailure = { Log.w(TAG, "Ditto off ack publication failed; remote delivery unconfirmed") },
            )) +
            ("usep12" to UseP12Handler(
                servers = { repo.current().runtimeTakServers },
                networkLocked = networkLocked,
                installer = P12Installer { server, identity ->
                    val staged = withContext(Dispatchers.IO) { P12CredentialStore(context).stage(server, identity) }
                    tak().installIdentity(server, identity.credentials, commit = {
                        var applied = false
                        repo.update { c ->
                            if (!c.networkPreferencesLocked && c.runtimeTakServers.any {
                                    it.enabled && it.id == server.id && it.connectionKey == server.connectionKey
                                }) {
                                staged.commit()
                                applied = true
                            }
                            c
                        }
                        applied
                    }, rollback = staged::rollback)
                },
            )) +
            ("setpdl" to SetPdlHandler(
                communityIdentity = { BuildConfig.DITTO_COMMUNITY_IDENTITY },
                networkLocked = networkLocked,
                store = DittoPdlStore { update ->
                    var locked = false
                    repo.update { c ->
                        locked = c.networkPreferencesLocked
                        if (locked) c else c.copy(
                            dittoPdlSource = update.source,
                            dittoCustomPdl = update.customPdl ?: c.dittoCustomPdl,
                        )
                    }
                    val c = repo.current()
                    if (locked) DittoPdlApplyResult(applied = false, locked = true)
                    else {
                        val state = applyDitto(c)
                        DittoPdlApplyResult(state == DittoState.Active || state == DittoState.Disabled)
                    }
                },
            ))
        return ChatCommandProcessor(
            isEnabled = { repo.config.first().chatCommandsEnabled },
            isRouteEnabled = { request ->
                request.sourceServerId != "ditto:chat" || repo.current().dittoEnabled
            },
            allowedCallsigns = { repo.config.first().chatCommandCallsigns },
            handlers = handlers,
            reply = sendReply,
            audit = { Log.i(TAG, it) },
            positional = ProfileHandler.POSITIONAL_KEYS,
            authorizer = ChatCommandAuthorizer(claim = CommandReplayJournal(context)::claim),
            onAuthorized = { request ->
                request.replyRoomId?.takeUnless { it == "All Chat Rooms" }?.let { room ->
                    DittoChatRooms(context).remember(room, request.replyMemberUids + repo.deviceUid)
                }
            },
        )
    }

    internal fun replyXml(uid: String, callsign: String, request: ChatCommandRequest, message: String): String =
        CotBuilder.chat(
            uid, callsign, request.replyRoomId ?: request.senderUid,
            request.replyRoomName ?: request.senderCallsign, message,
            groupMembers = request.replyRoomId?.let { request.replyMemberUids },
        )

    internal suspend fun routeReply(
        source: String,
        xml: String,
        ditto: (String) -> Int,
        multicast: suspend (String) -> Int,
        server: suspend (String, String) -> Int,
    ): Int = when {
        source == "ditto:chat" -> ditto(xml)
        source.startsWith(MULTICAST_SOURCE_PREFIX) -> multicast(xml)
        else -> server(source, xml)
    }

    /** loadprofile over this device's presets; chat and the Profiles screen share it. */
    fun loadProfileHandler(context: Context, repo: SettingsRepository): LoadProfileHandler = LoadProfileHandler(
        source = PresetSource { name -> withContext(Dispatchers.IO) { ProfilePresets.read(context.applicationContext, name) } },
        profile = ProfileHandler(ProfileHandler.PROFILE_KEYS, ProfileStore { update -> repo.update { c -> c.withProfile(update) } }),
        serverSink = PresetServerSink { servers -> addServers(repo, servers) },
        networkLocked = { repo.config.first().networkPreferencesLocked },
        customAlertSink = CustomAlertSink { alerts -> addCustomAlerts(repo, alerts) },
    )

    /**
     * Loads a preset from the on-watch Profiles screen through the same path, validation and network-lock
     * rule as the chat command. The running service sees the identity change and sends a fresh-fix PLI.
     */
    suspend fun loadPreset(context: Context, repo: SettingsRepository, name: String): ChatCommandResult =
        loadProfileHandler(context, repo).execute(
            ChatCommand("loadprofile", mapOf("name" to name)),
            ChatCommandRequest(repo.deviceUid, "local", "", null, 0, 0, "local"),
        )

    internal fun TrackerConfig.withProfile(u: ProfileUpdate): TrackerConfig = copy(
        callsign = u.callsign ?: callsign,
        team = u.team ?: team,
        role = u.role ?: role,
        medicalProfile = medicalProfile.let { m ->
            m.copy(
                heightFeet = u.heightFeet ?: m.heightFeet,
                heightInches = u.heightInches ?: m.heightInches,
                weightLbs = u.weightLbs ?: m.weightLbs,
                birthYear = u.birthYear ?: m.birthYear,
                sex = u.sex ?: m.sex,
                userType = u.userType ?: m.userType,
                pantsLengthInches = u.pantsLengthInches ?: m.pantsLengthInches,
                waistInches = u.waistInches ?: m.waistInches,
            )
        },
    )

    private suspend fun addCustomAlerts(repo: SettingsRepository, alerts: List<String>): Int {
        var added = 0
        repo.update { c ->
            val (merged, count) = CustomAlerts.merge(c.customAlerts, alerts)
            added = count
            c.copy(customAlerts = merged)
        }
        return added
    }

    private suspend fun addServers(repo: SettingsRepository, servers: List<PresetServer>): PresetServerAddResult {
        var added = 0
        var locked = false
        repo.update { c ->
            if (c.networkPreferencesLocked) {
                locked = true
                return@update c
            }
            val known = c.servers.map { it.endpointKey }.toMutableSet()
            val fresh = servers.map {
                TakServerConfig(name = it.name, address = it.address, port = it.port, username = it.username, password = it.password)
            }.filter { known.add(it.endpointKey) }
            added = fresh.size
            c.copy(servers = c.servers + fresh)
        }
        return PresetServerAddResult(added, locked)
    }

    private suspend fun upsertEnrollServer(repo: SettingsRepository, server: PresetServer): EnrollServerOutcome {
        var outcome = EnrollServerOutcome.LOCKED
        repo.update { c ->
            if (c.networkPreferencesLocked) return@update c
            val (servers, result) = upsertServer(c.servers, server)
            outcome = result
            c.copy(servers = servers)
        }
        return outcome
    }

    /**
     * Adds an enabled server, or updates the saved server with the same endpoint or trimmed
     * case-insensitive name in place. Refuses when name and endpoint match different servers.
     */
    internal fun upsertServer(
        servers: List<TakServerConfig>,
        server: PresetServer,
    ): Pair<List<TakServerConfig>, EnrollServerOutcome> {
        val incoming = TakServerConfig(
            name = server.name, address = server.address, port = server.port,
            username = server.username, password = server.password, enabled = true,
        )
        val name = server.name.trim().lowercase()
        val byEndpoint = servers.firstOrNull { it.endpointKey == incoming.endpointKey }
        val byName = servers.firstOrNull { it.name.trim().lowercase() == name }
        if (byEndpoint != null && byName != null && byEndpoint.id != byName.id) {
            return servers to EnrollServerOutcome.CONFLICT
        }
        val match = byEndpoint ?: byName ?: return (servers + incoming) to EnrollServerOutcome.ADDED
        val moved = match.endpointKey != incoming.endpointKey
        val updated = match.copy(
            name = incoming.name, address = incoming.address, port = incoming.port,
            username = incoming.username, password = incoming.password, enabled = true,
        ).let { if (moved) it.copy(tlsName = "", apiTlsName = "") else it }
        return servers.map { if (it.id == match.id) updated else it } to EnrollServerOutcome.UPDATED
    }
}