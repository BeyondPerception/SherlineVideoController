package ml.dent.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A basic working implementation of the network client that can write data and
 * handle the bounce server initialization sequence
 *
 * @author Ronak Malik
 */
public class SimpleNetworkClient extends AbstractNetworkClient {

    private static final int BUFFER_SIZE = 256;

    private boolean proxyEnabled;
    private boolean bounceServerProtocol;
    private boolean buffering;
    private int     channel;
    private int     internalPort;

    private BooleanProperty connectionStatusProperty = new SimpleBooleanProperty(false);
    private BooleanProperty connectionAttempted      = new SimpleBooleanProperty(false);

    /**
     * @param host    The hostname to connect to
     * @param port    The server's port to connect to
     * @param channel provides the channel that this client can send and receive
     *                messages on, this client will only send and receive messages
     *                on this channel. This will only be used if bounceserver protocol is enabled,
     *                and channel does not equal -1
     */
    public SimpleNetworkClient(String host, int port, int channel) {
        this(host, port, channel, false);
    }

    /**
     * @param buffer  Unless your write operations are low-frequency, not enabling buffering can
     *                severely impact performance and put unnecessary load on the CPU.
     * @param channel provides the channel that this client can send and receive
     *                messages on, this client will only send and receive messages
     *                on this channel
     */
    public SimpleNetworkClient(String host, int port, int channel, boolean buffer) {
        super(host, port);
        this.channel = channel;
        this.buffering = buffer;
        internalPort = getPort();
        proxyConnectionEstablished = new AtomicBoolean(false);
        authenticationMessage = "hi";
        bounceServerProtocol = true;
    }

    private DefaultChannelPromise channelPromise;

    @Override
    public ChannelFuture connect() {
        return connect(new ChannelHandler[0]);
    }

    @Override
    public ChannelFuture connect(ChannelHandler... channelHandlers) {
        connectionAttempted.set(false);
        ArrayList<ChannelHandler> handlerList = new ArrayList<>();
        handlerList.add(new ClientOutboundHandler());
        if (proxyEnabled) {
            handlerList.add(new ProxyHandler());
        }
        if (bounceServerProtocol) {
            handlerList.add(new BounceServerHandler());
        }
        handlerList.add(new ActiveHandler());
        ChannelHandler[] newHandlers = new ChannelHandler[channelHandlers.length + handlerList.size()];
        System.arraycopy(handlerList.toArray(newHandlers), 0, newHandlers, 0, handlerList.size());
        System.arraycopy(channelHandlers, 0, newHandlers, handlerList.size(), channelHandlers.length);

        ChannelFuture cf = super.connect(newHandlers);
        generateNewChannelFuture(cf);
        return channelPromise;
    }

    private void generateNewChannelFuture(ChannelFuture cf) {
        channelPromise = new DefaultChannelPromise(cf.channel()) {
            {
                cf.addListener(listener -> {
                    if (!cf.isSuccess()) {
                        setFailure(cf.cause());
                    }
                });
            }

            @Override
            public boolean isDone() {
                return connectionAttempted.get() && cf.isDone();
            }

            @Override
            public boolean isSuccess() {
                return isConnectionActive() && cf.isSuccess();
            }

            @Override
            public ChannelPromise setSuccess() {
                connectionAttempted.set(true);
                connectionStatusProperty.set(true);
                return super.setSuccess();
            }

            @Override
            public ChannelPromise setFailure(Throwable cause) {
                connectionAttempted.set(true);
                return super.setFailure(cause);
            }
        };
    }

    @Override
    public boolean isConnectionActive() {
        return connectionStatusProperty.get();
    }

    @Override
    public ReadOnlyBooleanProperty connectionActiveProperty() {
        return connectionStatusProperty;
    }

    public ChannelFuture write(String s) {
        return write(Unpooled.copiedBuffer(s, CharsetUtil.UTF_8));
    }

    private ByteBufAllocator alloc = UnpooledByteBufAllocator.DEFAULT;
    private ByteBuf          curBuffer;

    /**
     * If buffering is enabled, this method will flush for you if the buffer is filled.
     *
     * @param b The byte to write
     */
    public void write(byte b) {
        if (buffering) {
            if (curBuffer == null) {
                curBuffer = alloc.buffer(BUFFER_SIZE, BUFFER_SIZE);
            }
            if (curBuffer.writableBytes() <= 0) {
                writeAndFlush(curBuffer);
                curBuffer = alloc.buffer(BUFFER_SIZE, BUFFER_SIZE);
            }
            curBuffer.writeByte(b);
        } else {
            ByteBuf buf = alloc.buffer();
            buf.writeByte(b);
            write(buf);
        }
    }

    public ChannelFuture write(Object o) {
        if (!isConnectionActive()) {
            throw new IllegalStateException("Cannot write to non-active Channel!");
        }

        return getChannel().write(o);
    }

    public void flush() {
        getChannel().flush();
    }

    public ChannelFuture writeAndFlush(String s) {
        ChannelFuture cf = write(s);
        flush();
        return cf;
    }

    public void writeAndFlush(byte b) {
        write(b);
        flush();
    }

    public ChannelFuture writeAndFlush(Object o) {
        ChannelFuture cf = write(o);
        flush();
        return cf;
    }

    public int getInternalPort() {
        return internalPort;
    }

    public void setInternalPort(int newPort) {
        internalPort = newPort;
    }

    public void enableProxy(boolean set) {
        proxyEnabled = set;
    }

    public boolean proxyEnabled() {
        return proxyEnabled;
    }

    public void setBounceServerProtocol(boolean bounceServerProtocol) {
        this.bounceServerProtocol = bounceServerProtocol;
    }

    public boolean getBounceServerProtocol() {
        return bounceServerProtocol;
    }

    private String authenticationMessage;

    public String getAuthenticationMessage() {
        return authenticationMessage;
    }

    public void setAuthenticationMessage(String s) {
        authenticationMessage = s;
    }

    private AtomicBoolean proxyConnectionEstablished;

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String newName) {
        name = newName;
    }

    private class ProxyHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            proxyConnectionEstablished.set(false);
            String httpReq = "CONNECT localhost:" + getInternalPort() + " HTTP/1.1\r\n" + "Host: localhost:" + getInternalPort() + "\r\n"
                    + "Proxy-Connection: Keep-Alive\r\n" + "\r\n";

            ctx.writeAndFlush(Unpooled.copiedBuffer(httpReq, CharsetUtil.UTF_8));
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!proxyConnectionEstablished.get()) {
                try {
                    ByteBuf buf = (ByteBuf) msg;
                    StringBuilder httpResponse = new StringBuilder();
                    int endCount = 0;
                    byte lastByte = -1;
                    // HTTP Responses always end in a pair of \r\n's, so we keep reading until we hit that pattern
                    while (endCount < 2 && buf.readableBytes() > 0) {
                        byte b = buf.readByte();
                        if (b == '\n' && lastByte == '\r') {
                            endCount++;
                        }
                        if (b != '\n' && b != '\r') {
                            endCount = 0;
                        }
                        httpResponse.append((char) b);
                        lastByte = b;
                    }
                    if (endCount < 2) {
                        // Did not receive complete HTTP response
                        channelPromise.setFailure(new ProxyConnectException("Did not receive valid HTTP response from proxy"));
                        ctx.close();
                        return;
                    }
                    if (checkEstablished(httpResponse.toString())) {
                        proxyConnectionEstablished.set(true);
                        super.channelActive(ctx);
                        if (buf.readableBytes() > 0) {
                            // Forward the rest of the message down the pipeline
                            ByteBuf nextBuf = Unpooled.buffer(buf.readableBytes());
                            buf.readBytes(nextBuf);
                            super.channelRead(ctx, nextBuf);
                        }
                    } else {
                        channelPromise.setFailure(new ProxyConnectException(httpResponse.toString()));
                        ctx.close();
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            } else {
                super.channelRead(ctx, msg);
            }
        }

        private boolean checkEstablished(String confirm) {
            return confirm.contains("200"); // 200 OK HTTP code
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!channelPromise.isDone()) {
                channelPromise.setFailure(cause);
                ctx.close();
            }
        }
    }

    private class BounceServerHandler extends ChannelInboundHandlerAdapter {

        private AtomicBoolean verStringRecv = new AtomicBoolean();
        private AtomicBoolean statusRecv    = new AtomicBoolean();

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            verStringRecv.set(false);
            statusRecv.set(false);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!verStringRecv.get()) {
                try {
                    String verString = ((ByteBuf) msg).toString(CharsetUtil.UTF_8).replaceAll("[\n\r]", "");
                    if (!verString.matches("\\d+-.*")) {
                        channelPromise.setFailure(new ProtocolException("Received incoherent bounce server version string: " + verString));
                        ctx.close();
                        return;
                    }
                    String channelBytesStr = verString.substring(0, verString.indexOf("-"));
                    int channelBytes = Integer.parseInt(channelBytesStr);
                    // We use ctx.writeAndFlush instead of our own write method because we don't
                    // want the message traveling through the entire pipeline
                    if (authenticationMessage != null) {
                        ctx.writeAndFlush(Unpooled.copiedBuffer(authenticationMessage, CharsetUtil.UTF_8));
                    }
                    if (channel != -1) {
                        ctx.writeAndFlush(Unpooled.copiedBuffer(String.format("%0" + channelBytes + "x", channel), CharsetUtil.UTF_8));
                    }
                    verStringRecv.set(true);
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            } else if (!statusRecv.get()) {
                try {
                    String statusString = ((ByteBuf) msg).toString(CharsetUtil.UTF_8).trim();
                    statusRecv.set(true);
                    if (statusString.equals("READY")) {
                        super.channelActive(ctx);
                    } else {
                        channelPromise.setFailure(new ProtocolException(statusString));
                        ctx.close();
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            } else {
                super.channelRead(ctx, msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!channelPromise.isDone()) {
                channelPromise.setFailure(cause);
                ctx.close();
            }
        }
    }

    // This class simulates a handler that would be after the normal SimpleNetworkClient handler in the pipeline
    // to detect when this channel is ready to be used
    private class ActiveHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            channelPromise.setSuccess();
            connectionAttempted.set(true);
            super.channelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            connectionAttempted.set(true);
            if (channelPromise.isSuccess()) {
                connectionStatusProperty.set(false);
                super.channelInactive(ctx);
            }
        }
    }

    private static class ClientOutboundHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            super.write(ctx, msg, promise);
        }
    }
}
