package com.tak.weartak_tracker.data

/** A TAK Server group ("channel") the device can turn on or off, keyed by its bit position. */
data class TakChannel(
    val name: String,
    val direction: String,
    val active: Boolean,
    val bitPosition: Int,
    val updating: Boolean = false,
)

enum class TakChannelStatus { LOADING, READY, EMPTY, UNSUPPORTED, ERROR }

data class TakServerChannels(
    val serverId: String,
    val status: TakChannelStatus,
    val channels: List<TakChannel> = emptyList(),
    val errorMessage: String? = null,
)

/** Channel list transitions, matching WearTAK-CIV's optimistic toggle behaviour. */
internal object TakChannelState {
    fun loaded(serverId: String, channels: List<TakChannel>) = TakServerChannels(
        serverId = serverId,
        status = if (channels.isEmpty()) TakChannelStatus.EMPTY else TakChannelStatus.READY,
        channels = channels,
    )

    fun loading(serverId: String, previous: TakServerChannels?) =
        TakServerChannels(serverId, TakChannelStatus.LOADING, previous?.channels.orEmpty())

    fun loadFailed(serverId: String, previous: TakServerChannels?, message: String) = TakServerChannels(
        serverId, TakChannelStatus.ERROR, previous?.channels.orEmpty().map { it.copy(updating = false) }, message,
    )

    fun beginUpdate(previous: TakServerChannels, bitPosition: Int, active: Boolean) = previous.copy(
        status = TakChannelStatus.READY,
        channels = previous.channels.map { if (it.bitPosition == bitPosition) it.copy(active = active, updating = true) else it },
        errorMessage = null,
    )

    fun updateFailed(previous: TakServerChannels, message: String) = previous.copy(
        status = TakChannelStatus.ERROR,
        channels = previous.channels.map { it.copy(updating = false) },
        errorMessage = message,
    )
}
