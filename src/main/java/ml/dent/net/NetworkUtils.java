package ml.dent.net;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.util.CharsetUtil;

public class NetworkUtils {

    private NetworkUtils() {
    }

    public static void sendMessage(Channel channel, String message) {
        if (channel != null && channel.isActive() && channel.isWritable()) {
            channel.writeAndFlush(Unpooled.copiedBuffer(message + "\n", CharsetUtil.UTF_8));
        }
    }

    public static void sendMessage(Channel channel, byte b) {
        if (channel != null && channel.isActive() && channel.isWritable()) {
            channel.writeAndFlush(Unpooled.copiedBuffer(new byte[]{b}));
        }
    }
}
