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

class FakeChannelConnection : ChannelConnection {

    @Volatile
    var capturedHandler: PacketHandler? = null
        private set

    override fun flush() {}
    override fun write(packet: ToClientPacket) {}
    override fun writeAndFlush(packet: ToClientPacket) {}
    override fun write(packets: Array<out ToClientPacket>) {}
    override fun writeAndFlush(packets: Array<out ToClientPacket>) {}
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
    override fun closeApplicationConnection(errorCode: QuicApplicationErrorCode, reason: String) {}
    override fun updateStreamPriority(urgency: Int, incremental: Boolean) {}
}
