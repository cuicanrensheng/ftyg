package com.tv.live;

import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.upstream.ParsingLoadable;

import com.tv.live.util.LogBridge;
import com.tv.live.util.Variant;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * HLS 播放列表解析器工厂（带加载链路日志）
 *
 * <p>实现 Media3 1.7.1 的 HlsPlaylistParserFactory 接口，在创建解析器时记录日志，
 * 配合 MediaSourceEventListener 完成加载链路监控。</p>
 *
 * <p>⚠️ 本类<b>不含任何广告感知逻辑</b>（广告剥离由 M3u8AdStripper / 本地代理完成）：
 * 2026-10-22 由 AdAwareHlsPlaylistParserFactory 改名——原名名不副实，见审计 P2-09。</p>
 *
 * <p>【2026-10-25 普通源主清单预取已删除】原实现由 {@code TVPlayerManager} 用独立
 * HttpURLConnection 再拉一次主清单（多一次并发连接、与首帧抢同一台慢服务器）。
 * 现改为<b>复用播放器自己已经下载并解析的主清单</b>：本工厂在解析出
 * {@link HlsMultivariantPlaylist}（多码率主清单）时，把 variants 转成
 * {@link Variant} 回填给普通源清晰度轴 —— 零额外 HTTP 请求、零额外 prepare，
 * 普通源「多码率清晰度切换」功能完整保留。</p>
 *
 * <p>⚠️ 该工厂被普通源与虎牙两条链路共用，回填<b>仅对普通源生效</b>：
 * 普通源起播时传入 {@code variantListener}，虎牙起播不传（默认 {@code null}），
 * 避免污染虎牙「线路 × 清晰度」矩阵（{@code variantList} 被两条链路共用）。</p>
 */
@OptIn(markerClass = UnstableApi.class)
public final class LoggingHlsPlaylistParserFactory implements HlsPlaylistParserFactory {

    private static final String TAG = "HlsParserLog";

    /** 主清单（多码率）解析结果回填回调；仅普通源链路设置 */
    public interface OnNormalMasterVariantsListener {
        /**
         * @param playlistUri 被解析的主清单地址（ExoPlayer 实际请求的 URI）
         * @param variants    按主清单顺序的码率档（已按 RESOLUTION/BANDWIDTH 映射）
         */
        void onNormalMasterVariants(String playlistUri, List<Variant> variants);
    }

    private final OnNormalMasterVariantsListener variantListener;

    /** 普通源链路使用：解析出多码率主清单时回填清晰度轴 */
    public LoggingHlsPlaylistParserFactory(OnNormalMasterVariantsListener variantListener) {
        this.variantListener = variantListener;
    }

    /** 虎牙等其它链路使用：只打日志，不回填 */
    public LoggingHlsPlaylistParserFactory() {
        this(null);
    }

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser() {
        // [TEMP 2026-10-26 调试去噪] 暂时注销：创建 HLS 播放列表解析器（Multivariant）日志，避免每秒一条淹没切台事件
        // LogBridge.d(TAG, "创建 HLS 播放列表解析器（Multivariant）");
        return wrap(new HlsPlaylistParser());
    }

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser(
            HlsMultivariantPlaylist multivariantPlaylist,
            HlsMediaPlaylist previousMediaPlaylist) {
        int variantCount = multivariantPlaylist != null && multivariantPlaylist.variants != null
                ? multivariantPlaylist.variants.size() : 0;
        // [TEMP 2026-10-26 调试去噪] 暂时注销：创建 HLS 播放列表解析器（Media）日志，避免随快轮询每秒一条淹没切台事件
        // LogBridge.d(TAG, "创建 HLS 播放列表解析器（Media，子流数=" + variantCount + "）");
        return wrap(new HlsPlaylistParser());
    }

    /**
     * 包装解析器：
     * ① 解析出多码率主清单（{@link HlsMultivariantPlaylist}）→ 回填普通源清晰度轴；
     * ② 解析出媒体清单（{@link HlsMediaPlaylist}）→ 送入 {@link DeadStreamMonitor} 做「死流」检测
     * （跟踪直播清单 MEDIA-SEQUENCE 是否前进）。
     * 只观察、不修改内容，任何异常均被吞掉，绝不阻断播放链路。
     */
    private ParsingLoadable.Parser<HlsPlaylist> wrap(
            final ParsingLoadable.Parser<HlsPlaylist> delegate) {
        return new ParsingLoadable.Parser<HlsPlaylist>() {
            @Override
            public HlsPlaylist parse(Uri uri, InputStream inputStream) throws IOException {
                HlsPlaylist playlist = delegate.parse(uri, inputStream);
                if (playlist instanceof HlsMultivariantPlaylist) {
                    reportNormalVariants(uri, (HlsMultivariantPlaylist) playlist);
                }
                if (playlist instanceof HlsMediaPlaylist) {
                    try {
                        DeadStreamMonitor.onMediaPlaylistParsed(
                                uri == null ? "" : uri.toString(),
                                (HlsMediaPlaylist) playlist);
                    } catch (Throwable ignored) {
                        // 检测器只观察，异常绝不阻断播放链路
                    }
                }
                return playlist;
            }
        };
    }

    /** 把播放器解析出的主清单档位转成 {@link Variant} 交给普通源清晰度轴 */
    private void reportNormalVariants(Uri uri, HlsMultivariantPlaylist multivariant) {
        final OnNormalMasterVariantsListener listener = variantListener;
        if (listener == null) return; // 虎牙等链路：不回填
        try {
            List<Variant> out = toVariants(multivariant);
            if (out.isEmpty()) return;
            listener.onNormalMasterVariants(uri == null ? "" : uri.toString(), out);
        } catch (Throwable t) {
            LogBridge.w(TAG, "主清单档位回填失败（不影响播放）: " + t);
        }
    }

    /**
     * Media3 主清单 variant → 应用内 {@link Variant}（口径与已删除的正则解析一致）：
     * {@code url} = 该档的子清单地址（media3 已相对主清单解析为绝对地址），
     * {@code bandwidth} 取峰值码率，{@code width/height} 取 RESOLUTION。
     */
    private static List<Variant> toVariants(HlsMultivariantPlaylist multivariant) {
        List<Variant> out = new ArrayList<>();
        if (multivariant == null || multivariant.variants == null) return out;
        for (HlsMultivariantPlaylist.Variant v : multivariant.variants) {
            if (v == null || v.url == null) continue;
            int width = 0, height = 0, bandwidth = 0;
            Format f = v.format;
            if (f != null) {
                width = f.width == Format.NO_VALUE ? 0 : f.width;
                height = f.height == Format.NO_VALUE ? 0 : f.height;
                if (f.peakBitrate > 0) bandwidth = f.peakBitrate;
                else if (f.averageBitrate > 0) bandwidth = f.averageBitrate;
                else if (f.bitrate > 0) bandwidth = (int) f.bitrate;
            }
            out.add(new Variant(v.url.toString(), bandwidth, width, height));
        }
        return out;
    }
}
