package ml.dent.app;

import io.netty.channel.ChannelFuture;
import ml.dent.server.ControlServer;

import java.util.Scanner;

public class Main {

    public static final int PORT = 32565;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Starting server on port [" + PORT + "]...");
        ControlServer server = new ControlServer(PORT);
        ChannelFuture cf = server.start();
        cf.addListener(listener -> {
            System.out.println("Video client shutting down");
        });

        if (args.length > 0) {
            new Thread(() -> {
                Scanner file = new Scanner(System.in);
                while (true) {
                    file.nextLine();
                    System.out.println("Current Memory Usage: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576L + "M");
                    System.out.println("Max Memory Usage: " + Runtime.getRuntime().maxMemory() / 1048576L + "M");
                }
            }).start();
        }
    }
}