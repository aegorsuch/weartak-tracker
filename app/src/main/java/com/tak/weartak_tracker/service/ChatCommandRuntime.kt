package com.tak.weartak_tracker.service

import android.content.Context
import android.util.Log
import com.tak.weartak_tracker.command.ChatCommandProcessor
import com.tak.weartak_tracker.command.IdentityHandler
import com.tak.weartak_tracker.command.IdentityStore
import com.tak.weartak_tracker.command.LoadProfileHandler
import com.tak.weartak_tracker.command.PresetServer
import com.tak.weartak_tracker.command.PresetServerSink
import com.tak.weartak_tracker.command.PresetSource
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.transport.TakServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Builds the chat command processor over the tracker's settings and TAK server connections. */
object ChatCommandRuntime {
    private const val TAG = "ChatCommands"
    private const val MAX_PRESET_BYTES = 64 * 1024L

    fun create(context: Context, repo: SettingsRepository, tak: () -> TakServerManager): ChatCommandProcessor {
        val store = IdentityStore { callsign, team, role ->
            repo.update { c ->
                c.copy(callsign = callsign ?: c.callsign, team = team ?: c.team, role = role ?: c.role)
            }
        }
        val identity = IdentityHandler(IdentityHandler.COMMAND_KEYS.getValue("setprofile"), store)
        val handlers = IdentityHandler.COMMAND_KEYS.mapValues { (_, keys) -> IdentityHandler(keys, store)
        } + ("loadprofile" to LoadProfileHandler(
            source = PresetSource { name -> readPreset(context, name) },
            identity = identity,
            serverSink = PresetServerSink { servers -> addServers(repo, servers) },
            networkLocked = { repo.config.first().networkPreferencesLocked },
        ))
        return ChatCommandProcessor(
            isEnabled = { repo.config.first().chatCommandsEnabled },
            allowedCallsigns = { repo.config.first().chatCommandCallsigns },
            handlers = handlers,
            reply = { request, message ->
                val c = repo.config.first()
                tak().send(
                    CotBuilder.chat(repo.deviceUid, c.callsign, request.senderUid, request.senderCallsign, message),
                )
            },
            audit = { Log.i(TAG, it) },
        )
    }

    private suspend fun readPreset(context: Context, name: String): String? = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir("presets") ?: return@withContext null
        val file = File(dir, "$name.json")
        if (!file.isFile || file.length() > MAX_PRESET_BYTES) null else file.readText()
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