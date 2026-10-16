package com.tv.live;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.upstream.ParsingLoadable;

import com.tv.live.util.LogBridge;

/**
 * 自定义 HLS 播放列表解析器工厂
 *
 * 实现 Media3 1.7.1 的 HlsPlaylistParserFactory 接口，
 * 在创建解析器时记录日志，配合 MediaSourceEventListener 完成加载链路监控。
 */
@OptIn(markerClass = UnstableApi.class)
public final class AdAwareHlsPlaylistParserFactory implements HlsPlaylistParserFactory {

    private static final String TAG = "AdAwareHlsParser";

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser() {
        LogBridge.d(TAG, "创建 HLS 播放列表解析器（Multivariant）");
        return new HlsPlaylistParser();
    }

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser(
            HlsMultivariantPlaylist multivariantPlaylist,
            HlsMediaPlaylist previousMediaPlaylist) {
        int variantCount = multivariantPlaylist != null ? multivariantPlaylist.variants.size() : 0;
        LogBridge.d(TAG, "创建 HLS 播放列表解析器（Media，子流数=" + variantCount + "）");
        return new HlsPlaylistParser();
    }
}
