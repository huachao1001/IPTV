package com.iptv.live.play;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import java.util.List;

/**
 * 系统没有 MP2 解码器时，改用 MP3 解码器顶上。
 *
 * <p>这台 RK3566 电视的解码器清单里没有 {@code audio/mpeg-L2}（MP2），
 * 只有 {@code audio/mpeg}（MP3）。二者同属 MPEG-1 音频族，实测 mp3 解码器
 * 可以配置并接受 L2 码流。命中兜底后音轨才会被判为"支持"，
 * 否则 ExoPlayer 会直接把这条音轨丢掉，表现为有画面没声音。
 */
@UnstableApi
public final class Mp2FallbackSelector implements MediaCodecSelector {

    /** MP2 的 MIME，系统解码器清单里通常查不到。 */
    private static final String MIME_MP2 = "audio/mpeg-L2";
    /** MP3 解码器声明的 MIME，实际能解 L2。 */
    private static final String MIME_MP3 = "audio/mpeg";

    @Override
    public List<MediaCodecInfo> getDecoderInfos(
            String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
            throws MediaCodecUtil.DecoderQueryException {

        List<MediaCodecInfo> infos = MediaCodecSelector.DEFAULT
                .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
        if (!infos.isEmpty() || !MIME_MP2.equals(mimeType) || requiresSecureDecoder) {
            return infos;
        }
        // 没有专用 MP2 解码器，退回 MP3 解码器。
        // 返回对象的 codecMimeType 是 audio/mpeg，正是 MediaCodec 需要的配置值。
        return MediaCodecSelector.DEFAULT
                .getDecoderInfos(MIME_MP3, false, requiresTunnelingDecoder);
    }

    /** 本机是否真的缺少 MP2 解码器，缺了才需要这套兜底。 */
    public static boolean systemLacksMp2() {
        try {
            return MediaCodecSelector.DEFAULT
                    .getDecoderInfos(MIME_MP2, false, false).isEmpty();
        } catch (MediaCodecUtil.DecoderQueryException e) {
            return false;
        }
    }
}
