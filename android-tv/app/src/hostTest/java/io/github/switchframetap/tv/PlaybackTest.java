package io.github.switchframetap.tv;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.view.Surface;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** Run production worker loops, with platform drivers replaced by timing models. */
public final class PlaybackTest {
    private static byte[] frame;
    private static void require(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static StreamStats video(int frames) throws Exception { return video(frames,-1); }
    /** gapAt >= 0: the sequence numbers jump there (a lost packet or a queue reset) */
    private static StreamStats video(int frames,int gapAt) throws Exception {
        VideoQueue queue=new VideoQueue(); StreamStats stats=new StreamStats();
        VideoDecoder decoder=new VideoDecoder(new Surface(),queue,stats,s -> { },true,60f);
        decoder.start();
        try {
            for (int i=0;i<frames;i++) {
                int seq=gapAt>=0&&i>=gapAt?i+5:i;
                queue.offer(new SftProtocol.Packet(6,1280,720,seq,20000,frame,System.nanoTime()));
                Thread.sleep(33);
            }
            Thread.sleep(250);
        } finally { decoder.close(); }
        return stats;
    }
    /** 120 frames, 3 at a time every 50 ms: 60 fps on average, as bursty Wi-Fi delivers them. */
    private static StreamStats bursts(boolean smooth) throws Exception {
        MediaCodec.reset(); MediaCodec.outputDelayMs=8;   // a hardware decoder: each frame out a few ms after it went in
        VideoQueue queue=new VideoQueue(); StreamStats stats=new StreamStats();
        VideoDecoder decoder=new VideoDecoder(new Surface(),queue,stats,s -> { },smooth,60f);
        decoder.start();
        try {
            for (int i=0;i<120;i++) {
                queue.offer(new SftProtocol.Packet(i==0?6:2,1920,1080,i,20000,frame,System.nanoTime()));
                if (i%3==2) Thread.sleep(50);
            }
            Thread.sleep(300);
        } finally { decoder.close(); }
        return stats;
    }
    /** A steady 60 fps stream (a frame every 16.7 ms), a decoder taking 5-11 ms: the average
     *  time a decoded frame waits before the app collects it, in ms. */
    private static double pickup(boolean smooth) throws Exception {
        MediaCodec.reset(); MediaCodec.outputDelayMs=5; MediaCodec.outputJitterUs=6000;   // 5-11 ms, as real decoders vary
        VideoQueue queue=new VideoQueue(); StreamStats stats=new StreamStats();
        VideoDecoder decoder=new VideoDecoder(new Surface(),queue,stats,s -> { },smooth,60f);
        decoder.start();
        try {
            long t0=System.nanoTime();
            for (int i=0;i<90;i++) {
                queue.offer(new SftProtocol.Packet(i==0?6:2,1280,720,i,20000,frame,System.nanoTime()));
                long next=t0+(i+1)*16_666_667L, wait=next-System.nanoTime();
                if (wait>0) Thread.sleep(wait/1_000_000,(int)(wait%1_000_000));
            }
            Thread.sleep(100);
        } finally { decoder.close(); }
        long sum=0; int n=0;
        synchronized (MediaCodec.pickupNs) { for (int i=5;i<MediaCodec.pickupNs.size();i++) { sum+=MediaCodec.pickupNs.get(i); n++; } }
        require(n>=60,"steady stream: frames decoded, was "+n);
        return sum/(double)n/1e6;
    }
    public static void main(String[] args) throws Exception {
        java.nio.file.Path fixture=Paths.get(args[0],"logs/m83-runI/stream-first10.sft");
        if (Files.exists(fixture)) {
            byte[] wire=Files.readAllBytes(fixture);
            frame=Arrays.copyOfRange(wire,32,32+(int)SftProtocol.u32(wire,20));
        } else {
            // Standalone source ZIP: only framing is needed by the driver doubles.
            frame=new byte[]{0,0,0,1,0x67,0x64,0,0,1,0x68,0x12,0,0,1,0x65,9};
            System.out.println("No capture available; using synthetic framing for driver timing regressions");
        }
        MediaCodec.reset(); MediaCodec.inputDelayMs=60;
        StreamStats delayed=video(6);
        require(delayed.rendered>0,"first input buffer arrives late: decoder must eventually render");
        require(MediaCodec.created.get()==1,"pending first IDR must not repeatedly recreate decoder");
        System.out.println("PASS: delayed first input buffer, one codec configuration");
        MediaCodec.reset(); MediaCodec.outputDelayMs=180; MediaCodec.rewritePts=true;
        StreamStats pts=video(14);
        require(pts.rendered>0&&pts.unmatchedPts>0,"rewritten timestamps must not discard all Surface outputs");
        require(pts.decoderMs>=120,"driver latency >120 ms must not produce a black screen");
        System.out.println("PASS: rewritten PTS and 180 ms decoder output");
        // v0.7.15 (#27, #32): a gap at the same size flushes the decoder - recreating it on a TV
        // took long enough to overrun the queue again, ~1 frame shown per keyframe
        MediaCodec.reset(); MediaCodec.inputDelayMs=60;
        StreamStats gap=video(12,6);
        require(MediaCodec.created.get()==1,"a gap at the same size must not recreate the decoder");
        require(MediaCodec.flushed.get()>=1&&gap.rendered>6,"a gap must flush the decoder and keep rendering");
        System.out.println("PASS: a gap flushes the decoder instead of recreating it");
        VideoQueue asks=new VideoQueue();
        require(asks.takeKeyRequest()&&!asks.takeKeyRequest(),"a keyframe is asked for once at connect");
        asks.offer(new SftProtocol.Packet(6,1280,720,0,20000,frame,System.nanoTime()));
        asks.offer(new SftProtocol.Packet(6,1280,720,7,20000,frame,System.nanoTime()));
        require(asks.takeKeyRequest(),"a reset asks the console for a keyframe");
        System.out.println("PASS: keyframe requests at connect and after a reset");
        // 0.7.16 Smooth video: a 60 fps stream that arrives in bursts of 3 every 50 ms (Wi-Fi at 1080p)
        StreamStats smooth=bursts(true), newest=bursts(false);
        int smoothSkipped=(int)smooth.skipped, newestSkipped=(int)newest.skipped;
        require(smooth.decoded>=110,"every burst frame is decoded");
        require(smoothSkipped*20<=smooth.decoded,"smooth: at most 5% dropped, was "+smoothSkipped+" of "+smooth.decoded);
        require(smooth.worstGapMs<34,"smooth: an even cadence, worst gap "+smooth.worstGapMs+" ms");
        // newest-only: each frame goes to the Surface the moment it is decoded (0.7.19-touch-4); a
        // burst's frames land within one refresh, where the compositor shows only the newest
        int hidden=newestSkipped;
        synchronized (MediaCodec.renderedAt) {
            for (int i=1;i<MediaCodec.renderedAt.size();i++) if (MediaCodec.renderedAt.get(i)-MediaCodec.renderedAt.get(i-1)<8_000_000L) hidden++;
        }
        require(hidden*2>=newest.decoded,"newest-only shows only the newest of each burst, hidden "+hidden+" of "+newest.decoded);
        System.out.println("PASS: bursts - smooth shows "+smooth.rendered+" of "+smooth.decoded+" (worst gap "+Math.round(smooth.worstGapMs)
                +" ms), newest-only "+(newest.decoded-hidden)+" of "+newest.decoded);
        // 0.7.19-touch-4: while a frame is decoding the thread waits on the decoder, not the
        // queue - before, a decoded frame waited ~2 ms (newest-only) / ~1 ms (smooth) on average
        double pickNewest=pickup(false), pickSmooth=pickup(true);
        require(pickNewest<0.8&&pickSmooth<0.8,String.format(java.util.Locale.ROOT,
                "decoded frames collected at once: newest-only %.2f ms, smooth %.2f ms",pickNewest,pickSmooth));
        System.out.println(String.format(java.util.Locale.ROOT,"PASS: decoded frames collected at once - waited %.2f ms newest-only, %.2f ms smooth (was ~2 / ~1)",pickNewest,pickSmooth));
        // 0.7.19-touch-4: a Qualcomm decoder gets Moonlight's vendor low-latency options
        MediaCodec.reset(); MediaCodecList.names=new String[]{"c2.qti.avc.decoder","c2.android.avc.decoder"};
        StreamStats qti=video(8);
        java.util.Map<String,Integer> f=MediaCodec.lastFormat.ints;
        require("c2.qti.avc.decoder".equals(MediaCodec.lastName)&&qti.rendered>0,"the Qualcomm decoder plays");
        require(Integer.valueOf(1).equals(f.get("vendor.qti-ext-dec-low-latency.enable"))&&Integer.valueOf(1).equals(f.get("vendor.qti-ext-dec-picture-order.enable")),
                "Qualcomm: vendor low-latency keys set, were "+f);
        require(Integer.valueOf(Short.MAX_VALUE).equals(f.get("operating-rate"))&&!f.containsKey("priority"),"Qualcomm: the highest operating rate, without priority 0");
        require(qti.decoder.contains("vendor low latency"),"the stats name the mode: "+qti.decoder);
        System.out.println("PASS: Qualcomm decoder configured with the vendor low-latency options");
        // ... and a decoder that stops giving frames with them is set up again with the standard keys, not dropped
        MediaCodec.reset(); MediaCodecList.names=new String[]{"OMX.test.hardware","OMX.google.h264"}; MediaCodec.stallWithKey="vdec-lowlatency";
        StreamStats vendor=video(110);
        require(vendor.decoderErrors>0&&vendor.rendered>0&&"OMX.test.hardware".equals(MediaCodec.lastName)&&vendor.decoder.contains("hardware")
                &&!MediaCodec.lastFormat.ints.containsKey("vdec-lowlatency"),"vendor options that stall fall back to the standard keys: "+vendor.decoder);
        System.out.println("PASS: vendor options that stall the decoder fall back to the standard keys on the same decoder");
        MediaCodec.reset(); MediaCodec.stallHardware=true;
        StreamStats fallback=video(130);   // two tries on the hardware decoder now (vendor options, standard keys)
        require(fallback.decoderErrors>0&&fallback.decoder.contains("software")&&fallback.rendered>0,
                "accepted input with no output must fall back at an IDR");
        System.out.println("PASS: stalled hardware decoder falls back to software");
        StreamStats audioStats=new StreamStats(); AudioPump audio=new AudioPump(audioStats,false); audio.start();
        try {
            for (int i=0;i<22;i++) {
                audio.offer(new SftProtocol.Packet(8,48000,2,i,0,new byte[3840],System.nanoTime()));
                Thread.sleep(20);
            }
            require(audioStats.audioBufferMs==160,"test native sink must require 160 ms buffer");
            require(audioStats.audioPlayedFrames>0,"native buffer must fill beyond 80 ms and start playback");
            require(audioStats.audioResets==1,"startup must not repeatedly flush native audio");
        } finally { audio.close(); }
        System.out.println("PASS: 160 ms native audio startup without perpetual resets");
    }
}
