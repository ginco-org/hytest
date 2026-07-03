package gg.ginco.hytest.runtime.player;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.NetworkChannel;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.protocol.io.ConnectionHandler;
import com.hypixel.hytale.protocol.io.PacketStatsRecorder;
import com.hypixel.hytale.protocol.packets.connection.QuicApplicationErrorCode;
import com.hypixel.hytale.server.core.io.PacketHandler;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

/**
 * A no-op {@link ChannelConnection} used to inject a simulated player with no
 * real network client. All outbound packets are discarded; {@link #isActive()}
 * always reports {@code true} so the join pipeline does not abort. The handler
 * passed to {@link #setupAuxiliaryChannels} is captured so the simulated-player
 * factory can complete the client-ready gate.
 */
final class FakeChannelConnection implements ChannelConnection {

    private volatile PacketHandler capturedHandler;

    /** The handler captured from {@link #setupAuxiliaryChannels}, once available. */
    PacketHandler capturedHandler() {
        return capturedHandler;
    }

    @Override
    public void flush() {
        // no-op
    }

    @Override
    public void write(ToClientPacket packet) {
        // discard
    }

    @Override
    public void writeAndFlush(ToClientPacket packet) {
        // discard
    }

    @Override
    public void write(ToClientPacket[] packets) {
        // discard
    }

    @Override
    public void writeAndFlush(ToClientPacket[] packets) {
        // discard
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public boolean isWritable() {
        return true;
    }

    @Override
    public SocketAddress remoteAddress() {
        return new InetSocketAddress(0);
    }

    @Override
    public String formatRemoteAddress() {
        return "simulated";
    }

    @Override
    public void disconnect(FormattedMessage message) {
        // no-op
    }

    @Override
    public PacketStatsRecorder getPacketStatsRecorder() {
        return null;
    }

    @Override
    public String getSniHostname() {
        return null;
    }

    @Override
    public boolean isFromSameOrigin(ChannelConnection other) {
        return false;
    }

    @Override
    public void execute(Runnable runnable) {
        runnable.run();
    }

    @Override
    public X509Certificate getClientCertificate() {
        return null;
    }

    @Override
    public void initTimeoutContext(String stage, String identifier) {
        // no-op
    }

    @Override
    public void updateTimeoutContext(String stage, String identifier) {
        // no-op
    }

    @Override
    public void updateTimeoutContext(String stage) {
        // no-op
    }

    @Override
    public void setPacketTimeout(Duration timeout) {
        // no-op
    }

    @Override
    public void setStageTimeout(String stage, Duration timeout, BooleanSupplier condition, Runnable onTimeout) {
        // never fire — the simulated client never times out
    }

    @Override
    public void clearStageTimeout() {
        // no-op
    }

    @Override
    public void logConnectionTimings(String message, Level level) {
        // no-op
    }

    @Override
    public CompletableFuture<Void> setupAuxiliaryChannels(
            ConnectionHandler handler, BiConsumer<NetworkChannel, ChannelConnection> onChannelReady) {
        if (handler instanceof PacketHandler packetHandler) {
            this.capturedHandler = packetHandler;
        }
        onChannelReady.accept(NetworkChannel.Chunks, this);
        onChannelReady.accept(NetworkChannel.WorldMap, this);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void setChannelHandler(ConnectionHandler handler) {
        // no-op — lifecycle callbacks are unnecessary for a simulated connection
    }

    @Override
    public void closeConnection() {
        // no-op
    }

    @Override
    public void closeApplicationConnection() {
        // no-op
    }

    @Override
    public void closeApplicationConnection(QuicApplicationErrorCode errorCode) {
        // no-op
    }

    @Override
    public void closeApplicationConnection(QuicApplicationErrorCode errorCode, String reason) {
        // no-op
    }

    @Override
    public void updateStreamPriority(int urgency, boolean incremental) {
        // no-op
    }
}
