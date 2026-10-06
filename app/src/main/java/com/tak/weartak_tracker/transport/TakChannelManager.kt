package com.tak.weartak_tracker.transport

import android.util.Log
import com.tak.weartak_tracker.data.TakChannelState
import com.tak.weartak_tracker.data.TakChannelStatus
import com.tak.weartak_tracker.data.TakServerChannels
import com.tak.weartak_tracker.data.TrackerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * TAK Server channel (group) list and toggles, following WearTAK-CIV: load after each connect, reload
 * on user refresh or the server's `t-x-g-c` notice, and toggle optimistically then reconcile.
 */
class TakChannelManager(
    private val scope: CoroutineScope,
    private val tak: TakServerManager,
    private val clientUid: String,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lock(serverId: String) = locks.getOrPut(serverId) { Mutex() }

    private fun current(serverId: String): TakServerChannels? = TrackerState.takChannels.value[serverId]

    private fun publish(state: TakServerChannels) =
        TrackerState.takChannels.update { it + (state.serverId to state) }

    /** Drops channel lists for servers that were removed or disabled. */
    fun retain(serverIds: Set<String>) {
        TrackerState.takChannels.update { m -> m.filterKeys { it in serverIds } }
        locks.keys.retainAll(serverIds)
    }

    fun clear() {
        TrackerState.takChannels.value = emptyMap()
    }

    fun onConnected(serverId: String) = launchLoad(serverId, checkSupport = true)

    fun onServerNotice(serverId: String) = launchLoad(serverId, checkSupport = false)

    fun refresh(serverId: String) = launchLoad(serverId, checkSupport = true)

    fun toggle(serverId: String, bitPosition: Int, active: Boolean) {
        scope.launch(Dispatchers.IO) {
            lock(serverId).withLock {
                val previous = current(serverId) ?: return@withLock
                if (previous.channels.none { it.bitPosition == bitPosition }) return@withLock
                publish(TakChannelState.beginUpdate(previous, bitPosition, active))
                try {
                    val api = tak.channelApi(serverId) ?: throw IllegalStateException("TAK Server is not connected")
                    publish(TakChannelState.loaded(serverId, api.setActive(bitPosition, active, clientUid).channels))
                    // Reconcile with what the server actually applied.
                    publish(TakChannelState.loaded(serverId, api.load(checkSupport = false).channels))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Channel update failed for $serverId bitpos=$bitPosition", e)
                    publish(TakChannelState.updateFailed(previous, "Channel update failed: ${e.describe()}"))
                }
            }
        }
    }

    private fun launchLoad(serverId: String, checkSupport: Boolean) {
        scope.launch(Dispatchers.IO) {
            lock(serverId).withLock {
                val previous = current(serverId)
                publish(TakChannelState.loading(serverId, previous))
                try {
                    val api = tak.channelApi(serverId) ?: throw IllegalStateException("TAK Server is not connected")
                    publish(TakChannelState.loaded(serverId, api.load(checkSupport).channels))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: TakChannelsUnsupportedException) {
                    publish(TakServerChannels(serverId, TakChannelStatus.UNSUPPORTED))
                } catch (e: Exception) {
                    Log.w(TAG, "Channel load failed for $serverId", e)
                    publish(TakChannelState.loadFailed(serverId, previous, e.describe()))
                }
            }
        }
    }

    private fun Exception.describe(): String =
        (this as? javax.net.ssl.SSLException)?.hostnameMismatch()?.message?.let { "TLS: $it" }
            ?: message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private companion object {
        const val TAG = "TakChannelManager"
    }
}
