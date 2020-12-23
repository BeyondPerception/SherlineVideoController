package ml.dent.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import ml.dent.net.NetworkUtils;
import ml.dent.util.Markers;
import ml.dent.video.VideoServer;
import org.freedesktop.gstreamer.Bus;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

public class ControlServer {

    private int port;

    private StringBuffer config;

    private VideoServer videoServer;

    public ControlServer(int port) {
        this.port = port;
        config = new StringBuffer();
    }

    /**
     * @return A ChannelFuture that is notified when this server is closed
     * @throws InterruptedException If this thread is interrupted while the server
     *                              is trying to bind
     */
    public ChannelFuture start() throws InterruptedException {
        EventLoopGroup group = new NioEventLoopGroup();

        ServerBootstrap boot = new ServerBootstrap();
        boot.group(group).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ServerHandler());
                    }
                });

        ChannelFuture future = boot.bind(port).sync();

        return future.channel().closeFuture();
    }

    public void parseConfig(Channel notify) {
        if (videoServer != null && videoServer.isConnectionActive()) {
            NetworkUtils.sendMessage(notify, "Error: connection still running. Please stop the video before reconfiguring the server");
            return;
        }

        System.out.println("Parsing JSON config");
        JSONParser configParser = new JSONParser();
        boolean parseSuccess = false;
        VideoServer tmpServer = new VideoServer(null, -1);
        try {
            JSONObject configOptions;
            try {
                System.out.println(config);
                configOptions = (JSONObject) configParser.parse(config.toString());
            } catch (Exception e) {
                NetworkUtils.sendMessage(notify, "Error: Invalid JSON");
                e.printStackTrace();
                return;
            }

            String host = (String) configOptions.get("host");
            Long port = (Long) configOptions.get("port");
            String videoType = (String) configOptions.get("videoType");
            String videoSource = (String) configOptions.get("videoSource");
            Long tcpSourcePort = (Long) configOptions.get("tcpSourcePort");
            Long internalPort = (Long) configOptions.get("internalPort");
            Boolean enableSSL = (Boolean) configOptions.get("enableSSL");
            Boolean h264Encoded = (Boolean) configOptions.get("h264Encoded");

            StringBuilder returnMessage = new StringBuilder();
            if (host != null) {
                tmpServer.setHost(host);
            } else {
                returnMessage.append("Error: expected value for \"host\", got null\n");
            }
            if (port != null) {
                if (port >= 1 && port <= 65535) {
                    tmpServer.setPort(port.intValue());
                } else {
                    returnMessage.append("Error: expected value for \"port\" must be between 1 and 65535, got ").append(port);
                }
            } else {
                returnMessage.append("Error: expected value for \"port\", got null\n");
            }
            if (internalPort != null) {
                if (internalPort >= 1 && internalPort <= 65535) {
                    tmpServer.enableProxy(true);
                    tmpServer.setInternalPort(internalPort.intValue());
                } else {
                    returnMessage.append("Error: expected value for \"internalPort\" must be between 1 and 65535, got ").append(internalPort);
                }
            }
            if (enableSSL != null) {
                tmpServer.enableSSL(enableSSL);
            }
            if (h264Encoded != null) {
                tmpServer.setH264Encoded(h264Encoded);
            }
            String res = returnMessage.toString();
            if (!res.isEmpty()) {
                NetworkUtils.sendMessage(notify, res);
                return;
            }
            if (tcpSourcePort != null) {
                if (tcpSourcePort >= 1 && tcpSourcePort <= 65535) {
                    tmpServer.setSource(videoSource, tcpSourcePort.intValue());
                    parseSuccess = true;
                } else {
                    NetworkUtils.sendMessage(notify, "Error: value for \"tcpSourcePort\" must be between 1 and 65535");
                }
                return;
            }
            switch (videoType) {
                case "ip_camera":
                    try {
                        if (videoSource == null) {
                            NetworkUtils.sendMessage(notify, "Error: expected value for \"videoType\", got null");
                            return;
                        }
                        URI uri = new URI(videoSource);
                        tmpServer.setSource(uri);
                        parseSuccess = true;
                    } catch (URISyntaxException e) {
                        NetworkUtils.sendMessage(notify, "Error: invalid uri for value \"videoSource\"");
                    }
                    return;
                case "webcam":
                    if (videoSource == null) {
                        NetworkUtils.sendMessage(notify, "Error: expected value for \"videoType\", got null");
                        return;
                    }
                    File deviceFile = new File(videoSource);
                    if (!deviceFile.exists()) {
                        NetworkUtils.sendMessage(notify, "Error: invalid file path for value \"videoSource\"");
                        return;
                    }
                    tmpServer.setSource(videoSource);
                    parseSuccess = true;
                    return;
                case "default":
                    tmpServer.setSource();
                    parseSuccess = true;
                    return;
                default:
                    NetworkUtils.sendMessage(notify,
                            "Error: exptected value options \"ip_camera\", \"webcam\", or \"default\" for  value \"videoType\", got "
                                    + videoType);
            }
        } finally {
            if (parseSuccess) {
                videoServer = tmpServer;
                NetworkUtils.sendMessage(notify, Markers.CONFIG);
                NetworkUtils.sendMessage(notify, "Success: video server configured");
            } else {
                videoServer = null;
            }
        }
    }

    public void startVideo(Channel notify) {
        System.out.println("Start video method");
        if (videoServer == null) {
            NetworkUtils.sendMessage(notify, "Error: request to start video before configuration received");
            return;
        }
        if (videoServer.isConnectionActive()) {
            NetworkUtils.sendMessage(notify, "Error: connection to server already active");
        }
        try {
            ChannelFuture cf = videoServer.connect();
            cf.awaitUninterruptibly();
            if (cf.isSuccess()) {
                NetworkUtils.sendMessage(notify, "Info: successfully connected to server");
            } else {
                NetworkUtils.sendMessage(notify, "Error: failed to connect to server");
                return;
            }
            GenericFutureListener<? extends Future<? super Void>> sendDisconnectMessage = future -> NetworkUtils.sendMessage(notify, "Info: disconnected from server");
            videoServer.closeFuture().addListener(sendDisconnectMessage);

            Bus bus = videoServer.startStream();
            Bus.ERROR errorListener = (source, code, message) -> {
                NetworkUtils.sendMessage(notify, source.getName() + ": " + message);
                NetworkUtils.sendMessage(notify, "Info: video stream stopped");
            };
            bus.connect(errorListener);
            notify.closeFuture().addListener(future -> {
                bus.disconnect(errorListener);
                videoServer.closeFuture().removeListener(sendDisconnectMessage);
            });
            if (videoServer.streamStarted()) {
                NetworkUtils.sendMessage(notify, "Success: video server started");
            }
        } finally {
            NetworkUtils.sendMessage(notify, "Info: starting video attempted");
        }
    }

    public void stopVideo(Channel notify) {
        if (videoServer == null) {
            NetworkUtils.sendMessage(notify, "Error: request to stop video before configuration received");
            return;
        }
        if (videoServer.streamStarted()) {
            videoServer.stopStream();
        }
        if (videoServer.isConnectionActive()) {
            videoServer.disconnect().awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
        NetworkUtils.sendMessage(notify, "Info: video stream stopped");
        NetworkUtils.sendMessage(notify, Markers.STOP_VIDEO);
    }

    private boolean readingConfig;

    class ServerHandler extends ChannelInboundHandlerAdapter {
        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            ByteBuf buf = (ByteBuf) msg;
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);

            for (byte b : bytes) {
                if (readingConfig) {
                    if (b == Markers.CONFIG) {
                        readingConfig = false;
                        System.out.println("Recv end config byte");
                        parseConfig(ctx.channel());
                    } else {
                        config.append((char) b);
                    }
                } else {
                    switch (b) {
                        case Markers.CONFIG:
                            readingConfig = true;
                            config = new StringBuffer();
                            System.out.println("Recv start config byte");
                            break;
                        case Markers.START_VIDEO:
                            System.out.println("Starting video");
                            startVideo(ctx.channel());
                            break;
                        case Markers.STOP_VIDEO:
                            System.out.println("Stopping video");
                            stopVideo(ctx.channel());
                            break;
                        case Markers.PING_REQUEST:
                            JSONObject status = new JSONObject();
                            status.put("isConnected", videoServer != null && videoServer.isConnectionActive());
                            status.put("connectionAttempted", videoServer != null && videoServer.connectionAttempted());
                            status.put("isStreaming", videoServer != null && videoServer.streamStarted());
                            byte[] statusBytes = status.toJSONString().getBytes();
                            ByteBuf response = Unpooled.copiedBuffer(new byte[]{Markers.PING_RESPONSE}, statusBytes, new byte[]{Markers.PING_RESPONSE});
                            ctx.writeAndFlush(response);
                            break;
                    }
                }
            }

            super.channelRead(ctx, msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            stopVideo(ctx.channel());
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            stopVideo(ctx.channel());
            cause.printStackTrace();
            videoServer.closeFuture().addListener(listener -> ctx.close());
        }
    }
}
