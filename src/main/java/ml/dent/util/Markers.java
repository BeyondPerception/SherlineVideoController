package ml.dent.util;

public class Markers {
	public static final byte	PING_REQUEST	= (byte) 0x7f;
	public static final byte	PING_RESPONSE	= (byte) 0x7e;
	public static final byte	STOP			= (byte) 0x65;
	public static final byte	SPEED			= (byte) 0x73;
	public static final byte	JOG				= (byte) 0x6a;
	public static final byte	AXIS			= (byte) 0x61;
	public static final byte	MSG				= (byte) 0x6d;
	public static final byte	ESC_MSG			= (byte) 0x00;
	public static final byte	START_VIDEO		= (byte) 0x4b;
	public static final byte	STOP_VIDEO		= (byte) 0xa7;
	public static final byte	CONFIG			= (byte) 0xfb;
}
