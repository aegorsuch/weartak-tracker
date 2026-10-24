package com.tak.weartak_tracker.service

import android.content.Context
import android.util.Log
import com.tak.weartak_tracker.command.ChatCommand
import com.tak.weartak_tracker.command.ChatCommandProcessor
import com.tak.weartak_tracker.command.ChatCommandRequest
import com.tak.weartak_tracker.command.ChatCommandResult
import com.tak.weartak_tracker.command.CustomAlertSink
import com.tak.weartak_tracker.command.EnrollServerHandler
import com.tak.weartak_tracker.command.LoadProfileHandler
import com.tak.weartak_tracker.command.PresetServer
import com.tak.weartak_tracker.command.PresetServerSink
import com.tak.weartak_tracker.command.PresetSource
import com.tak.weartak_tracker.command.ProfileHandler
import com.tak.weartak_tracker.command.ProfileStore
import com.tak.weartak_tracker.command.ProfileUpdate
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.data.CustomAlerts
import com.tak.weartak_tracker.data.ProfilePresets
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.data.TrackerConfig
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
        multicastChat: suspend (String) -> Int,
    ): ChatCommandProcessor {
        val store = ProfileStore { update -> repo.update { c -> c.withProfile(update) } }
        val networkLocked: suspend () -> Boolean = { repo.config.first().networkPreferencesLocked }
        val sink = PresetServerSink { servers -> addServers(repo, servers) }
        val handlers = ProfileHandler.COMMAND_KEYS.mapValues { (_, keys) -> ProfileHandler(keys, store) } +
            ("loadprofile" to loadProfileHandler(context, repo)) +
            ("enrollserver" to EnrollServerHandler(sink, networkLocked))
        return ChatCommandProcessor(
            isEnabled = { repo.config.first().chatCommandsEnabled },
            allowedCallsigns = { repo.config.first().chatCommandCallsigns },
            handlers = handlers,
            reply = { request, message ->
                val c = repo.config.first()
                val xml = CotBuilder.chat(repo.deviceUid, c.callsign, request.senderUid, request.senderCallsign, message)
                val routes = if (request.sourceServerId.startsWith(MULTICAST_SOURCE_PREFIX)) multicastChat(xml) else tak().send(xml)
                Log.i(TAG, "reply to ${request.senderCallsign} via ${request.sourceServerId}: $routes route(s)")
            },
            audit = { Log.i(TAG, it) },
            positional = ProfileHandler.POSITIONAL_KEYS,
        )
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

    private suspend fun addServers(repo: SettingsRepository, servers: List<PresetServer>): Int {
        var added = 0
        repo.update { c ->
            val known = c.servers.map { it.endpointKey }.toMutableSet()
            val fresh = servers.map {
                TakServerConfig(name = it.name, address = it.address, port = it.port, username = it.username, password = it.password)
            }.filter { known.add(it.endpointKey) }
            added = fresh.size
            c.copy(servers = c.servers + fresh)
        }
        return added
    }
}