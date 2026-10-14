package com.tv.live.util;

import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.amr.AmrExtractor;
import androidx.media3.extractor.flv.FlvExtractor;
import androidx.media3.extractor.mp3.Mp3Extractor;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.ts.Ac3Extractor;
import androidx.media3.extractor.ts.Ac4Extractor;
import androidx.media3.extractor.ts.AdtsExtractor;
import androidx.media3.extractor.ts.TsExtractor;

import java.util.List;
import java.util.Map;

/**
 * 最小化 ExtractorsFactory（2026-09-14 体积优化）。
 *
 * <p>背景：{@code ProgressiveMediaSource.Factory(DataSource.Factory)} 单参构造器内部会
 * {@code new DefaultExtractorsFactory()}，后者把 media3-extractor 的 226 个类（约 1.05MB 未压缩）
 * 全部拉进 R8 可达闭包，导致 MKV/Ogg/AVI/WAV/FLAC/图片等根本用不到的 extractor 无法被裁掉。
 *
 * <p>本类只注册 TV 直播实际需要的 9 个 extractor，使 {@code DefaultExtractorsFactory} 不可达，
 * 从而让 R8 释放约 39 类 / 201KB（未压缩 .class）。
 *
 * <p>⚠️ 支持的容器格式：TS / 裸AAC(ADTS) / AC-3 / AC-4 / FLV / MP4 / fMP4 / MP3 / AMR。
 * 不再支持：MKV、AVI、Ogg、WAV、FLAC、MPEG-PS、JPEG/PNG/WebP/AVIF/BMP/HEIF 图片。
 * 若后续需要播放 U 盘中的本地 MKV/AVI 等，请把对应 Extractor 加回 {@link #buildExtractors()}。
 *
 * <p>⚠️ TS 的 reader（H264Reader/H265Reader/AdtsReader/Ac3Reader/Ac4Reader/Id3Reader/
 * MpegAudioReader 等 32 类）无需在此手工注册：{@code new TsExtractor()} 的默认构造器自带
 * {@code DefaultTsPayloadReaderFactory}，R8 会沿着它对 reader 的引用自动保留。
 */
// 【2026-10-10 Lint】本类刻意使用 media3 的 @UnstableApi 内部 API（自定义 ExtractorsFactory）。
@OptIn(markerClass = UnstableApi.class)
public final class TvExtractorsFactory implements ExtractorsFactory {

    private static final TvExtractorsFactory INSTANCE = new TvExtractorsFactory();

    /** 全局共享实例（Extractor 本身是有状态的，每次创建都返回新实例，实例本身无状态可复用）。 */
    public static TvExtractorsFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public Extractor[] createExtractors() {
        return buildExtractors();
    }

    @Override
    public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
        return buildExtractors();
    }

    /**
     * 必须每次返回新实例（沿用 ExoPlayer 对 ExtractorsFactory 的约定：
     * ProgressiveMediaPeriod 会持有并 reset 这些 Extractor，复用同一实例会导致状态错乱）。
     *
     * <p>顺序：先容器类（FLV/MP4/fMP4/TS，sniff 更严格），再裸流类（ADTS/AC-3/AC-4/MP3/AMR）。
     */
    private static Extractor[] buildExtractors() {
        return new Extractor[] {
                new FlvExtractor(),
                new FragmentedMp4Extractor(),
                new Mp4Extractor(),
                new TsExtractor(),
                new AdtsExtractor(),
                new Ac3Extractor(),
                new Ac4Extractor(),
                new Mp3Extractor(),
                new AmrExtractor(),
        };
    }
}
