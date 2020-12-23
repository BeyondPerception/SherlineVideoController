package ml.dent.video;

import ml.dent.net.SimpleNetworkClient;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;

import java.net.URI;
import java.nio.ByteBuffer;

public class VideoServer extends SimpleNetworkClient {

    public enum Camera {
        WEBCAM,
        IP_CAMERA,
        TCPSRC,
        DEFAULT
    }

    // Describes whether the stream read from the camera will already be h264
    // encoded
    private boolean h264Encoded;

    private Camera cameraType;
    private String source;

    public VideoServer(String host, int port, String deviceName) {
        this(host, port, Camera.WEBCAM);
        source = deviceName;
    }

    public VideoServer(String host, int port, URI uri) {
        this(host, port, Camera.IP_CAMERA);
        source = uri.toString();
    }

    public VideoServer(String host, int port, String tcpHost, int tcpPort) {
        this(host, port, Camera.TCPSRC);
        source = tcpHost + ":" + tcpPort;
    }

    /**
     * Uses the default webcam as the camera source
     */
    public VideoServer(String host, int port) {
        this(host, port, Camera.DEFAULT);
    }

    private VideoServer(String host, int port, Camera type) {
        super(host, port, '1', true);
        cameraType = type;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String deviceName) {
        source = deviceName;
        cameraType = Camera.WEBCAM;
    }

    public void setSource(URI uri) {
        source = uri.toString();
        cameraType = Camera.IP_CAMERA;
    }

    public void setSource(String tcpHost, int tcpPort) {
        source = tcpHost + ":" + tcpPort;
        cameraType = Camera.TCPSRC;
    }

    /**
     * Uses the default webcam as the camera source
     */
    public void setSource() {
        cameraType = Camera.DEFAULT;
    }

    public void setH264Encoded(boolean enc) {
        h264Encoded = enc;
    }

    public boolean getH264Encoded() {
        return h264Encoded;
    }

    public Camera getCameraType() {
        return cameraType;
    }

    private Pipeline pipeline;

    public static long startTime = -1;

    /**
     * Starts the video stream by initializing Gstreamer and sends stream over the
     * network. Blocks until connection is established
     *
     * @return A message Bus that can be used to track the messages coming out of this video pipeline
     */
    public Bus startStream() {
        if (!isConnectionActive()) {
            throw new IllegalStateException("Cannot start stream, connection not ready!");
        }
        getChannel().closeFuture().addListener((future) -> stopStream());

        if (!Gst.isInitialized()) {
            System.out.println("Initializing Gstreamer...");
            Gst.init();
            while (!Gst.isInitialized())
                ;
            System.out.println("Gstreamer initialized");
        }

        System.out.println("Setting up pipeline");
        String parseString;
        switch (cameraType) {
            case IP_CAMERA:
                System.out.println("IP Camera");
                parseString = "urisourcebin uri=" + source;
                parseString += " ! queue ! rtpjitterbuffer ! queue ! rtph264depay";
                break;
            case WEBCAM:
                System.out.println("Webcam");
                parseString = "v4l2src device=" + source;
                break;
            case TCPSRC:
                System.out.println("Network source");
                int colonIndex = source.indexOf(":");
                String host = source.substring(0, colonIndex);
                String port = source.substring(colonIndex + 1);
                parseString = "tcpclientsrc host=" + host + " port=" + port;
                break;
            default:
                parseString = "v4l2src";
        }

        if (h264Encoded) {
            System.out.println("Stream already h.264 encoded, stripping container");
            parseString += " ! h264parse ! queue ! mpegtsmux";
        } else {
            System.out.println("Encoding stream");
            parseString += " ! queue ! decodebin ! queue ! videoconvert ! queue ! x264enc tune=\"zerolatency\"";
        }
        parseString += " ! queue ! appsink name=sink sync=false";
//		parseString += "! queue ! tcpserversink host=0.0.0.0 port=1111";

        pipeline = (Pipeline) Gst.parseLaunch(parseString);

        pipeline.getBus().connect((Bus.ERROR) (source, code, message) -> {
            System.out.println("Error Source: " + source.getName());
            System.out.println("Error Code: " + code);
            System.out.println("Error Message: " + message);
        });
        pipeline.getBus().connect((Bus.WARNING) (source, code, message) -> {
            System.out.println("Warn Source: " + source.getName());
            System.out.println("Warn Code: " + code);
            System.out.println("Warn Message: " + message);
        });

        AppSink sink = (AppSink) pipeline.getElementByName("sink");
        sink.set("emit-signals", true);
        sink.connect((AppSink.NEW_SAMPLE) elem -> {
            Sample sample = elem.pullSample();
            return getFlowReturn(sample);
        });
        sink.connect((AppSink.NEW_PREROLL) elem -> {
            Sample sample = elem.pullPreroll();
            return getFlowReturn(sample);
        });

        System.out.println("Playing pipeline");
        pipeline.play();

        return pipeline.getBus();
    }

    private static final Object lockObject = new Object();

    private FlowReturn getFlowReturn(Sample sample) {
        synchronized (lockObject) {
            if (isWritable()) {
                Buffer buf = sample.getBuffer();
                ByteBuffer byteBuffer = buf.map(false);

                byte[] bytes = new byte[byteBuffer.remaining()];
                byteBuffer.get(bytes);

                for (byte b : bytes) {
                    write(b);
                }

                buf.unmap();
            }
            sample.dispose();
            return FlowReturn.OK;
        }
    }

    public boolean streamStarted() {
        if (pipeline == null) {
            return false;
        }
        return pipeline.isPlaying();
    }

    public void stopStream() {
        if (pipeline == null) {
            return;
        }
        pipeline.stop();
        pipeline.close();
        pipeline = null;
//        Gst.deinit();
//        while (Gst.isInitialized())
//            ;
    }
}
