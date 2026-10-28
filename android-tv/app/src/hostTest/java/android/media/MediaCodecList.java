package android.media;
public final class MediaCodecList {
    public static final int REGULAR_CODECS=0;
    public MediaCodecList(int kind) { }
    /** The device's decoders, hardware first (a test may name others). */
    public static volatile String[] names={"OMX.test.hardware","OMX.google.h264"};
    public MediaCodecInfo[] getCodecInfos() {
        String[] n=names; MediaCodecInfo[] out=new MediaCodecInfo[n.length];
        for (int i=0;i<n.length;i++) out[i]=new MediaCodecInfo(n[i]);
        return out;
    }
}
