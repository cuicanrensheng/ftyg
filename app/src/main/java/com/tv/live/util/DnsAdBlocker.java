package com.tv.live.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DNS 广告拦截工具（独立工具类）
 *
 * 原理：在应用内部 DNS 解析阶段拦截广告域名，将其解析到 127.0.0.1，
 * 使广告请求无法到达真实服务器。
 *
 * 特点：
 * 1. 无需 VPN 权限，无需 root
 * 2. 支持精确域名匹配和通配符匹配（*.example.com）
 * 3. 支持正则表达式匹配
 * 4. 可动态添加/删除拦截规则
 * 5. 提供 OkHttp Dns 接口实现，可直接接入 ExoPlayer/OkHttp
 *
 * 使用方式：
 * - OkHttp: client.newBuilder().dns(DnsAdBlocker.getDnsInterceptor()).build()
 * - 手动检查: if (DnsAdBlocker.isBlocked(url)) { 拒绝请求 }
 */
public final class DnsAdBlocker {

    private static final String TAG = "DnsAdBlocker";

    /** 被拦截域名返回的假 IP */
    private static final String BLOCKED_IP = "127.0.0.1";

    /** 精确匹配的域名集合 */
    private static final Set<String> sExactBlocklist = new HashSet<>();

    /** 通配符匹配的域名后缀集合（如 .example.com 匹配所有子域名） */
    private static final Set<String> sSuffixBlocklist = new HashSet<>();

    /** 正则表达式匹配列表 */
    private static final List<Pattern> sRegexBlocklist = new ArrayList<>();

    /** 拦截计数器（用于统计） */
    private static volatile long sBlockCount = 0;

    static {
        // 初始化默认广告拦截规则（澳门新葡京及相关赌博广告域名）
        initDefaultBlocklist();
    }

    private DnsAdBlocker() {
        // 工具类，禁止实例化
    }

    /**
     * 初始化默认广告拦截规则
     * 基于已知的澳门新葡京赌博广告域名
     */
    private static void initDefaultBlocklist() {
        // ============================================================
        // 澳门新葡京相关域名（从实际广告 + 公开报道中收集）
        // ============================================================
        // 用户实际遇到的广告域名
        addBlockDomain("801801.com");
        addBlockDomain("www.801801.com");
        addBlockDomain("m.801801.com");
        addBlockDomain("801801.net");
        addBlockDomain("www.801801.net");

        // 公开报道中查获的新葡京赌博网站域名
        addBlockDomain("8744.com");       // 新葡京/太阳城（2019雅安案）
        addBlockDomain("www.8744.com");
        addBlockDomain("5163.com");       // 新葡京娱乐城（2020报道）
        addBlockDomain("www.5163.com");
        addBlockDomain("99199.com");      // 新葡京官方娱乐网（2016百度推广案）
        addBlockDomain("www.99199.com");

        // ============================================================
        // 常见赌博广告域名（通配符匹配，覆盖子域名变体）
        // ============================================================
        addBlockDomain("*.macau888.com");
        addBlockDomain("*.macau888.net");
        addBlockDomain("*.xinpujing.com");
        addBlockDomain("*.xinpujing.net");
        addBlockDomain("*.xinpujing.org");
        addBlockDomain("*.pujing88.com");
        addBlockDomain("*.pujing88.net");
        addBlockDomain("*.aomenxinpujing.com");
        addBlockDomain("*.aomenxinpujing.net");
        addBlockDomain("*.aomenpujing.com");
        addBlockDomain("*.macauxinpujing.com");

        // 常见赌博网站关键词正则匹配（谨慎使用，避免误杀）
        addBlockDomain("regex:xinpujing\\d*\\.(com|net|org|cc|vip)");
        addBlockDomain("regex:pujing\\d*\\.(com|net|org|cc|vip)");
        addBlockDomain("regex:macau\\d*\\.(com|net|org|cc|vip)");

        // ============================================================
        // 常见广告追踪域名（通用，参考 StevenBlack/hosts、hagezi/dns-blocklists）
        // ============================================================
        addBlockDomain("*.doubleclick.net");
        addBlockDomain("*.googlesyndication.com");
        addBlockDomain("*.googleadservices.com");
        addBlockDomain("*.admob.com");
        addBlockDomain("*.adnxs.com");
        addBlockDomain("*.adsrvr.org");
        addBlockDomain("*.moatads.com");
        addBlockDomain("*.adform.net");
        addBlockDomain("*.criteo.com");
        addBlockDomain("*.taboola.com");
        addBlockDomain("*.outbrain.com");

        LogBridge.i(TAG, "DNS 广告拦截器初始化完成，默认规则数: " +
                (sExactBlocklist.size() + sSuffixBlocklist.size() + sRegexBlocklist.size()));
    }

    /**
     * 添加拦截域名
     * @param domain 域名，支持 *.example.com 通配符格式
     */
    public static synchronized void addBlockDomain(String domain) {
        if (domain == null || domain.isEmpty()) {
            return;
        }
        domain = domain.trim().toLowerCase();

        if (domain.startsWith("*.")) {
            // 通配符匹配：保存后缀（去掉 *.）
            String suffix = domain.substring(1); // .example.com
            sSuffixBlocklist.add(suffix);
            LogBridge.d(TAG, "添加通配符拦截: " + domain);
        } else if (domain.startsWith("regex:")) {
            // 正则表达式匹配
            try {
                Pattern pattern = Pattern.compile(domain.substring(6));
                sRegexBlocklist.add(pattern);
                LogBridge.d(TAG, "添加正则拦截: " + domain);
            } catch (Exception e) {
                LogBridge.w(TAG, "正则表达式无效: " + domain + ", " + e.getMessage());
            }
        } else {
            // 精确匹配
            sExactBlocklist.add(domain);
            LogBridge.d(TAG, "添加精确拦截: " + domain);
        }
    }

    /**
     * 移除拦截域名
     */
    public static synchronized void removeBlockDomain(String domain) {
        if (domain == null || domain.isEmpty()) {
            return;
        }
        domain = domain.trim().toLowerCase();
        sExactBlocklist.remove(domain);
        if (domain.startsWith("*.")) {
            sSuffixBlocklist.remove(domain.substring(1));
        }
    }

    /**
     * 检查域名是否被拦截
     * @param host 域名（不含协议和路径）
     * @return true 表示被拦截
     */
    public static boolean isBlocked(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        host = host.toLowerCase();

        // 1. 精确匹配
        if (sExactBlocklist.contains(host)) {
            sBlockCount++;
            return true;
        }

        // 2. 后缀匹配（通配符）
        for (String suffix : sSuffixBlocklist) {
            if (host.endsWith(suffix)) {
                sBlockCount++;
                return true;
            }
        }

        // 3. 正则匹配
        for (Pattern pattern : sRegexBlocklist) {
            if (pattern.matcher(host).find()) {
                sBlockCount++;
                return true;
            }
        }

        return false;
    }

    /**
     * 从 URL 中提取 host 并检查是否被拦截
     * @param url 完整 URL
     * @return true 表示被拦截
     */
    public static boolean isUrlBlocked(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        try {
            // 简单提取 host
            String host = url;
            if (host.contains("://")) {
                host = host.substring(host.indexOf("://") + 3);
            }
            if (host.contains("/")) {
                host = host.substring(0, host.indexOf("/"));
            }
            if (host.contains(":")) {
                host = host.substring(0, host.indexOf(":"));
            }
            return isBlocked(host);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * DNS 解析：被拦截的域名返回 127.0.0.1，否则正常解析
     * @param hostname 域名
     * @return IP 地址列表
     */
    public static List<InetAddress> resolve(String hostname) throws UnknownHostException {
        if (isBlocked(hostname)) {
            LogBridge.w(TAG, "DNS 拦截: " + hostname + " -> " + BLOCKED_IP);
            return Collections.singletonList(InetAddress.getByName(BLOCKED_IP));
        }
        // 正常解析
        return Arrays.asList(InetAddress.getAllByName(hostname));
    }

    /**
     * 获取 OkHttp Dns 接口实现
     * 用法: okHttpClient.newBuilder().dns(DnsAdBlocker.getDnsInterceptor()).build()
     */
    public static okhttp3.Dns getDnsInterceptor() {
        return new okhttp3.Dns() {
            @Override
            public List<InetAddress> lookup(String hostname) throws UnknownHostException {
                return resolve(hostname);
            }
        };
    }

    /**
     * 获取拦截统计
     */
    public static long getBlockCount() {
        return sBlockCount;
    }

    /**
     * 重置拦截统计
     */
    public static void resetBlockCount() {
        sBlockCount = 0;
    }

    /**
     * 获取当前所有拦截规则（用于调试）
     */
    public static synchronized String dumpRules() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DNS 广告拦截规则 ===\n");
        sb.append("精确匹配 (").append(sExactBlocklist.size()).append("):\n");
        for (String d : sExactBlocklist) {
            sb.append("  ").append(d).append("\n");
        }
        sb.append("通配符匹配 (").append(sSuffixBlocklist.size()).append("):\n");
        for (String d : sSuffixBlocklist) {
            sb.append("  *").append(d).append("\n");
        }
        sb.append("正则匹配 (").append(sRegexBlocklist.size()).append("):\n");
        for (Pattern p : sRegexBlocklist) {
            sb.append("  regex:").append(p.pattern()).append("\n");
        }
        sb.append("累计拦截: ").append(sBlockCount).append(" 次\n");
        return sb.toString();
    }
}
