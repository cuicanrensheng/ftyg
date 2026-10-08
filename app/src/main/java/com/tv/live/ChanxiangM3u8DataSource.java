package com.tv.live;

import android.net.Uri;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 兼容畅享（Chanxiang）下发的非标准 HLS m3u8：
 * 其片段时长写成科学计数法（如 #EXTINF:3E+00,），ExoPlayer 的 HLS 解析器不认，
 * 这里在拉取到 m3u8 文本后把时长归一化为标准十进制（#EXTINF:3.0,）再交给解析器。
 * .ts 分片不受影响，直接透传。
 */
public final class ChanxiangM3u8DataSource implements DataSource {

    private static final String TAG = "ChanxiangM3u8DS";
    private static final Pattern EXTINF = Pattern.compile("#EXTINF:([^,\r\n]+),");

    private final DataSource upstream;
    @Nullable private ByteArrayInputStream rewritten;
    @Nullable private DataSpec dataSpec;

    public ChanxiangM3u8DataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public void addTransferListener(@Nullable TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        String uri = dataSpec.uri.toString();
        // 仅对 m3u8（排除 .ts）做归一化；其他资源透传给上游
        if (uri.contains(".m3u8") && !uri.contains(".ts")) {
            long opened = upstream.open(dataSpec);
            Log.d(TAG, "拦截 m3u8 进行归一化: " + uri);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = upstream.read(buf, 0, buf.length)) != -1) {
                    bos.write(buf, 0, n);
                }
            } finally {
                try {
                    upstream.close();
                } catch (IOException ignored) {
                }
            }
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            String normalized = normalize(text);
            byte[] out = normalized.getBytes(StandardCharsets.UTF_8);
            rewritten = new ByteArrayInputStream(out);
            return C.LENGTH_UNSET;
        }
        return upstream.open(dataSpec);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (rewritten != null) {
            return rewritten.read(buffer, offset, length);
        }
        return upstream.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
        if (rewritten != null) {
            rewritten.close();
            rewritten = null;
        }
        upstream.close();
    }

    @Nullable
    @Override
    public Uri getUri() {
        if (rewritten != null && dataSpec != null) return dataSpec.uri;
        try {
            return upstream.getUri();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        if (rewritten != null) return Collections.emptyMap();
        return upstream.getResponseHeaders();
    }

    private static String normalize(String text) {
        Matcher m = EXTINF.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String raw = m.group(1).trim();
            String replacement;
            if (raw.matches(".*[eE].*")) {
                try {
                    double v = Double.parseDouble(raw);
                    replacement = "#EXTINF:" + v + ",";
                } catch (NumberFormatException e) {
                    replacement = m.group(0);
                }
            } else {
                replacement = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 工厂：包装任意上游 DataSource.Factory，仅对 m3u8 做归一化。 */
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;

        public Factory(DataSource.Factory upstreamFactory) {
            this.upstreamFactory = upstreamFactory;
        }

        @Override
        public DataSource createDataSource() {
            return new ChanxiangM3u8DataSource(upstreamFactory.createDataSource());
        }
    }
}
