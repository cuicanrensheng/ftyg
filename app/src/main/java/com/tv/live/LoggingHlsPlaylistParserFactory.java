package com.tv.live;

import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.upstream.ParsingLoadable;

import com.tv.live.util.LogBridge;

import java.io.IOException;
import java.io.InputStream;

/**
 * HLS 播放列表解析器工厂（带加载链路日志）
 *
 * <p>实现 Media3 1.7.1 的 HlsPlaylistParserFactory 接口，在创建解析器时记录日志，
 * 配合 MediaSourceEventListener 完成加载链路监控。
 *
 * <p>⚠️ 本类<b>不含任何广告感知逻辑</b>（广告剥离由 M3u8AdStripper / 本地代理完成）：
 * 2026-10-22 由 AdAwareHlsPlaylistParserFactory 改名——原名名不副实，见审计 P2-09。
 */
@OptIn(markerClass = UnstableApi.class)
public final class LoggingHlsPlaylistParserFactory implements HlsPlaylistParserFactory {

    private static final String TAG = "HlsParserLog";

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser() {
        LogBridge.d(TAG, "创建 HLS 播放列表解析器（Multivariant）");
        return wrap(new HlsPlaylistParser());
    }

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser(
            HlsMultivariantPlaylist multivariantPlaylist,
            HlsMediaPlaylist previousMediaPlaylist) {
        int variantCount = multivariantPlaylist != null ? multivariantPlaylist.variants.size() : 0;
        LogBridge.d(TAG, "创建 HLS 播放列表解析器（Media，子流数=" + variantCount + "）");
        return wrap(new HlsPlaylistParser());
    }

    /**
     * 包装解析器：媒体清单解析成功后送入 {@link DeadStreamMonitor} 做「死流」检测
     * （跟踪直播清单 MEDIA-SEQUENCE 是否前进）。只观察、不修改内容，
     * 检测器任何异常均被吞掉，绝不阻断播放链路。
     */
    private static ParsingLoadable.Parser<HlsPlaylist> wrap(
            final ParsingLoadable.Parser<HlsPlaylist> delegate) {
        return new ParsingLoadable.Parser<HlsPlaylist>() {
            @Override
            public HlsPlaylist parse(Uri uri, InputStream inputStream) throws IOException {
                HlsPlaylist playlist = delegate.parse(uri, inputStream);
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
}
