package com.archos.mediacenter.video.player;

import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.view.Display;
import java.util.Arrays;
import java.util.Locale;

public final class HardwareCapabilityProfiler {
    private static final long ULTRA_MIN_BYTES=2_800L*1024*1024*1024/1000;
    public record Profile(long ramBytes,boolean dolbyVision,boolean hdr10Plus,
                          boolean profile7Decoder,String displayModes) {
        public boolean ultraEligible(){return ramBytes>=ULTRA_MIN_BYTES;}
    }

    private HardwareCapabilityProfiler(){}

    public static Profile inspect(Context context,Display display){
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();
        context.getSystemService(ActivityManager.class).getMemoryInfo(memory);
        int[] hdr=display==null?new int[0]:display.getHdrCapabilities().getSupportedHdrTypes();
        boolean dv=Arrays.stream(hdr).anyMatch(type->type==Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION);
        boolean plus=Arrays.stream(hdr).anyMatch(type->type==Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS);
        boolean profile7=false;
        for(MediaCodecInfo codec:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
            if(codec.isEncoder())continue;
            for(String mime:codec.getSupportedTypes()){
                if(!"video/dolby-vision".equalsIgnoreCase(mime))continue;
                for(var profile:codec.getCapabilitiesForType(mime).profileLevels)
                    profile7|=profile.profile==MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb;
            }
        }
        String modes=display==null?"":Arrays.stream(display.getSupportedModes()).map(mode->
                String.format(Locale.US,"%d×%d @ %.3f Hz",mode.getPhysicalWidth(),
                        mode.getPhysicalHeight(),mode.getRefreshRate())).distinct()
                .collect(java.util.stream.Collectors.joining("\n"));
        return new Profile(memory.totalMem,dv,plus,profile7,modes);
    }
}
