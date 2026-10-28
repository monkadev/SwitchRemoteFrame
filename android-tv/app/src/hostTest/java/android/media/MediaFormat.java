package android.media;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
public final class MediaFormat {
    public static final String KEY_MAX_INPUT_SIZE="max-input-size", KEY_PRIORITY="priority", KEY_OPERATING_RATE="operating-rate",
            KEY_LOW_LATENCY="low-latency", KEY_COLOR_STANDARD="color-standard", KEY_COLOR_RANGE="color-range";
    public static final int COLOR_STANDARD_BT709=1, COLOR_RANGE_LIMITED=2;
    /** What was set, for the tests. */
    public final Map<String,Integer> ints=new HashMap<>();
    public static MediaFormat createVideoFormat(String type,int width,int height) { return new MediaFormat(); }
    public void setByteBuffer(String key,ByteBuffer value) { }
    public void setInteger(String key,int value) { ints.put(key,value); }
}
