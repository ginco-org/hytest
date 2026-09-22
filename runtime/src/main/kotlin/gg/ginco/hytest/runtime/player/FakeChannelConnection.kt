package gg.ginco.hytest.runtime.player

import com.hypixel.hytale.protocol.FormattedMessage
import com.hypixel.hytale.protocol.NetworkChannel
import com.hypixel.hytale.protocol.ToClientPacket
import com.hypixel.hytale.protocol.io.ChannelConnection
import com.hypixel.hytale.protocol.io.ConnectionHandler
import com.hypixel.hytale.protocol.io.PacketStatsRecorder
import com.hypixel.hytale.protocol.packets.connection.QuicApplicationErrorCode
import com.hypixel.hytale.server.core.io.PacketHandler
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.function.BiConsumer
import java.util.function.BooleanSupplier
import java.util.logging.Level

import com.hypixel.hytale.protocol.packets.interface_.ServerMessage
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * A no-op [ChannelConnection] that records what the server "sends" to a
 * simulated player.
 *
 * All packet writes are discarded (nothing goes on a wire), but text packets
 * ([ServerMessage] — chat, command responses, system messages) are captured as
 * their plain [FormattedMessage.rawText] so tests can assert what the server
 * told the player. Capture is bounded to [MAX_CAPTURED_MESSAGES] entries and
 * thread-safe: writes happen on the world thread, tests read from the test
 * body thread.
 */
class FakeChannelConnection : ChannelConnection {

    @Volatile
    var capturedHandler: PacketHandler? = null
        private set

    private val capturedMessages = ConcurrentLinkedDeque<String>()

    private fun record(packet: ToClientPacket) {
        if (packet !is ServerMessage) return
        val text = packet.message?.rawText ?: return
        capturedMessages.addLast(text)
        while (capturedMessages.size > MAX_CAPTURED_MESSAGES) capturedMessages.pollFirst()
    }

    /** Snapshot of the captured message texts, oldest first. Not drained. */
    fun receivedMessages(): List<String> = capturedMessages.toList()

    /** [receivedMessages], clearing the capture. */
    fun drainReceivedMessages(): List<String> {
        val out = receivedMessages()
        capturedMessages.clear()
        return out
    }

    override fun flush() {}
    override fun write(packet: ToClientPacket) { record(packet) }
    override fun writeAndFlush(packet: ToClientPacket) { record(packet) }
    override fun write(packets: Array<out ToClientPacket>) { packets.forEach(::record) }
    override fun writeAndFlush(packets: Array<out ToClientPacket>) { packets.forEach(::record) }
    override fun isActive(): Boolean = true
    override fun isWritable(): Boolean = true
    override fun remoteAddress(): SocketAddress = InetSocketAddress(0)
    override fun formatRemoteAddress(): String = "simulated"
    override fun disconnect(message: FormattedMessage) {}
    override fun getPacketStatsRecorder(): PacketStatsRecorder? = null
    override fun getSniHostname(): String? = null
    override fun isFromSameOrigin(other: ChannelConnection): Boolean = false
    override fun execute(runnable: Runnable) { runnable.run() }
    override fun getClientCertificate(): X509Certificate? = null
    override fun initTimeoutContext(stage: String, identifier: String) {}
    override fun updateTimeoutContext(stage: String, identifier: String) {}
    override fun updateTimeoutContext(stage: String) {}
    override fun setPacketTimeout(timeout: Duration) {}
    override fun clearPacketTimeout() {}
    override fun setStageTimeout(stage: String, timeout: Duration, condition: BooleanSupplier, onTimeout: Runnable) {}
    override fun clearStageTimeout() {}
    override fun logConnectionTimings(message: String, level: Level) {}

    override fun setupAuxiliaryChannels(
        handler: ConnectionHandler,
        onChannelReady: BiConsumer<NetworkChannel, ChannelConnection>
    ): CompletableFuture<Void> {
        if (handler is PacketHandler) capturedHandler = handler
        onChannelReady.accept(NetworkChannel.Chunks, this)
        onChannelReady.accept(NetworkChannel.WorldMap, this)
        return CompletableFuture.completedFuture(null)
    }

    override fun setChannelHandler(handler: ConnectionHandler) {}
    override fun closeConnection() {}
    override fun closeApplicationConnection() {}
    override fun closeApplicationConnection(errorCode: QuicApplicationErrorCode) {}
    override fun closeApplicationConnection(errorCode: QuicApplicationErrorCode, reason: FormattedMessage) {}
    override fun updateStreamPriority(urgency: Int, incremental: Boolean) {}

    private companion object {
        /** Keeps long sessions from growing the capture without bound. */
        const val MAX_CAPTURED_MESSAGES = 256
    }
}
