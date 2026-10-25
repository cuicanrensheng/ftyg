package com.tv.live;

import com.tv.live.util.LogBridge;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SourceManager {

    private static final String SP_NAME = "app_settings";

    public static final String BUILTIN_NAME_LIVE_1 = "内置源1 (Gitee)";
    public static final String BUILTIN_NAME_LIVE_2 = "内置源2 (Gitee)";
    /** 源3 = 畅享TV 源（上游 nowtv-new.xiaoyouzb.cn）；对外标签用缩写 CXTV */
    public static final String BUILTIN_NAME_LIVE_3 = "内置源3 (CXTV)";
    /**
     * 旧版本名称「内置源1 (GitHub)」：源1 的地址早在 2026-08-29 就换成了 Gitee
     * （gitee.com/qf_1111/iptv/raw/master/dfdvfaf.m3u，185 频道），名称当时漏改，
     * 一直误标成 GitHub。仅用于识别并就地改名迁移，不再对外展示。
     */
    public static final String BUILTIN_NAME_LIVE_1_LEGACY = "内置源1 (GitHub)";
    /**
     * 源3 的历史名称（该标签前前后后改过多次）：
     *   「内置源3」          —— 曾一度去掉后缀
     *   「内置源3 (畅享TV)」 —— 曾用来源全名，后按需求缩写为 CXTV
     * 仅用于识别并就地改名迁移，不再对外展示。
     */
    private static final String[] LIVE_3_LEGACY_NAMES = { "内置源3", "内置源3 (畅享TV)" };

    /** 是否命中源3 的历史名称：历史名一律仍按「内置源3」识别，避免被当成自定义源重复添加 */
    public static boolean isLive3LegacyName(String n) {
        if (n == null) return false;
        for (String s : LIVE_3_LEGACY_NAMES) {
            if (s.equals(n)) return true;
        }
        return false;
    }

    public static final String BUILTIN_NAME_LIVE_4 = "内置源4 (影视)";
    public static final String BUILTIN_NAME_LIVE_5 = "内置源5 (aiqiyi)";
    /** 旧版本名称「内置源5 (爱其意)」：仅用于识别并就地改名迁移，不再对外展示 */
    public static final String BUILTIN_NAME_LIVE_5_LEGACY = "内置源5 (爱其意)";
    public static final String BUILTIN_NAME_LIVE_6 = "内置源6 (咪咕)";
    public static final String BUILTIN_NAME_LIVE_7 = "内置源7 (影视)";
    public static final String BUILTIN_NAME_LIVE_8 = "内置源8 (renyibo)";
    public static final String BUILTIN_NAME_LIVE_9 = "内置源9 (橘汁)";
    public static final String BUILTIN_NAME_EPG_1  = "内置节目单1 (Catvod)";
    public static final String BUILTIN_NAME_EPG_2  = "内置节目单2 (ERW)";

    public static boolean isBuiltin(SourceItem si, String spKey) {
        if (si == null) return false;
        String n = si.name == null ? "" : si.name;
        if ("live_history".equals(spKey)) {
            if (BUILTIN_NAME_LIVE_1.equals(n) || BUILTIN_NAME_LIVE_1_LEGACY.equals(n)
                    || BUILTIN_NAME_LIVE_2.equals(n) || BUILTIN_NAME_LIVE_3.equals(n)
                    || isLive3LegacyName(n) || BUILTIN_NAME_LIVE_4.equals(n)
                    || BUILTIN_NAME_LIVE_5.equals(n) || BUILTIN_NAME_LIVE_5_LEGACY.equals(n)
                    || BUILTIN_NAME_LIVE_6.equals(n) || BUILTIN_NAME_LIVE_7.equals(n)
                    || BUILTIN_NAME_LIVE_8.equals(n) || BUILTIN_NAME_LIVE_9.equals(n)) return true;
            if (si.url != null && (UrlConfig.LIVE_URL.equals(si.url) || UrlConfig.LIVE_URL_2.equals(si.url)
                    || UrlConfig.LIVE_URL_CHANXIANG.equals(si.url) || UrlConfig.LIVE_URL_4.equals(si.url)
                    || UrlConfig.LIVE_URL_5.equals(si.url) || UrlConfig.LIVE_URL_6.equals(si.url)
                    || UrlConfig.LIVE_URL_7.equals(si.url) || UrlConfig.LIVE_URL_8.equals(si.url)
                    || UrlConfig.LIVE_URL_9.equals(si.url))) return true;
        } else if ("epg_history".equals(spKey)) {
            if (BUILTIN_NAME_EPG_1.equals(n) || BUILTIN_NAME_EPG_2.equals(n)) return true;
            if (si.url != null && (UrlConfig.EPG_URL.equals(si.url) || UrlConfig.EPG_URL_2.equals(si.url))) return true;
        }
        return false;
    }

    private Context context;

    private SharedPreferences sp;

    private String spKey;

    public SourceManager(Context context, String spKey) {
        this.context = context.getApplicationContext();
        this.sp = this.context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        this.spKey = spKey;
    }

    public static class SourceItem {

        public String name;

        public String url;

        public boolean isDefault;

        public boolean autoUpdate;

        public long addTime;

        public SourceItem(String name, String url) {
            this.name = name;
            this.url = url;
            this.isDefault = false;
            this.autoUpdate = true;
            this.addTime = System.currentTimeMillis();
        }
    }

    public List<SourceItem> getAllSources() {
        return parseSourceList();
    }

    public SourceItem get(int position) {
        List<SourceItem> list = getAllSources();
        if (position >= 0 && position < list.size()) {
            return list.get(position);
        }
        return null;
    }

    public int size() {
        return getAllSources().size();
    }

    public int indexOfUrl(String url) {
        List<SourceItem> list = getAllSources();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).url.equals(url)) {
                return i;
            }
        }
        return -1;
    }

    public boolean addSource(String name, String url) {
        if (TextUtils.isEmpty(url)) return false;

        List<SourceItem> list = getAllSources();

        for (SourceItem si : list) {
            if (si.url.equals(url)) {
                return false;
            }
        }

        if (TextUtils.isEmpty(name)) {
            name = "源" + (list.size() + 1);
        }

        SourceItem newItem = new SourceItem(name, url);

        if (list.isEmpty()) {
            newItem.isDefault = true;
        }
        list.add(0, newItem);
        saveSourceList(list);
        return true;
    }

    public boolean removeSource(int position) {
        List<SourceItem> list = getAllSources();
        if (position < 0 || position >= list.size()) return false;

        SourceItem target = list.get(position);
        if (isBuiltin(target, spKey)) {
            return false;
        }

        list.remove(position);

        boolean hasDefault = false;
        for (SourceItem si : list) {
            if (si.isDefault) {
                hasDefault = true;
                break;
            }
        }
        if (!hasDefault && !list.isEmpty()) {

            for (SourceItem si : list) {
                if (isBuiltin(si, spKey)) {
                    si.isDefault = true;
                    hasDefault = true;
                    break;
                }
            }
            if (!hasDefault) {
                list.get(0).isDefault = true;
            }
        }

        saveSourceList(list);
        return true;
    }

    public boolean updateSource(int position, String newName, String newUrl) {
        List<SourceItem> list = getAllSources();
        if (position < 0 || position >= list.size()) return false;

        if (!TextUtils.isEmpty(newName)) {
            list.get(position).name = newName;
        }
        if (!TextUtils.isEmpty(newUrl)) {
            list.get(position).url = newUrl;
        }

        saveSourceList(list);
        return true;
    }

    public void clearAll() {
        sp.edit().putString(spKey, "").apply();
    }

    public boolean moveToTop(int position) {
        List<SourceItem> list = getAllSources();
        if (position <= 0 || position >= list.size()) return false;

        list.add(0, list.remove(position));
        saveSourceList(list);
        return true;
    }

    public boolean moveToBottom(int position) {
        List<SourceItem> list = getAllSources();
        if (position < 0 || position >= list.size() - 1) return false;

        list.add(list.remove(position));
        saveSourceList(list);
        return true;
    }

    public boolean setDefault(int position) {
        List<SourceItem> list = getAllSources();
        if (position < 0 || position >= list.size()) return false;

        for (SourceItem si : list) {
            si.isDefault = false;
        }

        list.get(position).isDefault = true;
        saveSourceList(list);
        return true;
    }

    public String getDefaultUrl() {
        SourceItem item = getDefaultSource();
        return item != null ? item.url : "";
    }

    public SourceItem getDefaultSource() {
        List<SourceItem> list = getAllSources();
        if (list.isEmpty()) return null;

        for (SourceItem si : list) {
            if (si.isDefault) {
                return si;
            }
        }

        return list.get(0);
    }

    public boolean toggleAutoUpdate(int position) {
        List<SourceItem> list = getAllSources();
        if (position < 0 || position >= list.size()) return false;

        list.get(position).autoUpdate = !list.get(position).autoUpdate;
        saveSourceList(list);
        return list.get(position).autoUpdate;
    }

    public List<SourceItem> getAutoUpdateSources() {
        List<SourceItem> all = getAllSources();
        List<SourceItem> result = new ArrayList<>();
        for (SourceItem si : all) {
            if (si.autoUpdate) {
                result.add(si);
            }
        }
        return result;
    }

    public List<SourceItem> search(String keyword) {
        List<SourceItem> all = getAllSources();
        if (TextUtils.isEmpty(keyword)) {
            return all;
        }

        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        List<SourceItem> result = new ArrayList<>();
        for (SourceItem si : all) {
            if (si.name.toLowerCase(Locale.ROOT).contains(lowerKeyword)
                    || si.url.toLowerCase(Locale.ROOT).contains(lowerKeyword)) {
                result.add(si);
            }
        }
        return result;
    }

    public String exportToText() {
        List<SourceItem> list = getAllSources();
        StringBuilder sb = new StringBuilder();
        for (SourceItem si : list) {
            sb.append(si.name).append(",").append(si.url).append("\n");
        }
        return sb.toString();
    }

    public int importFromText(String text) {
        if (TextUtils.isEmpty(text)) return 0;

        String[] lines = text.split("\n");
        int added = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || !line.contains("http")) continue;

            String name = "";
            String url = line;

            if (line.contains(",") && line.indexOf(",") < line.indexOf("http")) {
                int commaIdx = line.indexOf(",");
                name = line.substring(0, commaIdx).trim();
                url = line.substring(commaIdx + 1).trim();
            }

            if (indexOfUrl(url) >= 0) continue;

            if (TextUtils.isEmpty(name)) {
                name = "导入源" + (size() + added + 1);
            }

            List<SourceItem> list = getAllSources();
            SourceItem newItem = new SourceItem(name, url);
            if (list.isEmpty()) {
                newItem.isDefault = true;
            }
            list.add(newItem);
            saveSourceList(list);
            added++;
        }

        return added;
    }

    public static String formatTime(long timeMs) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "MM-dd HH:mm", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(timeMs));
    }

    private List<SourceItem> parseSourceList() {
        List<SourceItem> list = new ArrayList<>();
        String data = sp.getString(spKey, "");

        if (TextUtils.isEmpty(data)) {

            List<SourceItem> builtin = buildBuiltinSources();
            if (!builtin.isEmpty()) {
                saveSourceList(builtin);
                return builtin;
            }
            return list;
        }

        boolean isOldFormat = !data.contains("##");

        if (isOldFormat) {

            String[] urls = data.split("\\|");
            for (String url : urls) {
                if (!url.trim().isEmpty()) {
                    String shortName = url.length() > 10 ? url.substring(0, 10) + "..." : url;
                    list.add(new SourceItem(shortName, url));
                }
            }
        } else {

            String[] items = data.split("\\|\\|");
            for (String item : items) {
                if (item.trim().isEmpty()) continue;
                String[] fields = item.split("##");
                if (fields.length >= 2) {
                    SourceItem si = new SourceItem(fields[0], fields[1]);
                    if (fields.length >= 3) {
                        si.isDefault = "1".equals(fields[2]);
                    }
                    if (fields.length >= 4) {
                        si.autoUpdate = "1".equals(fields[3]);
                    }
                    if (fields.length >= 5) {
                        try {
                            si.addTime = Long.parseLong(fields[4]);
                        } catch (Exception ignored) {}
                    }
                    list.add(si);
                }
            }
        }

        List<SourceItem> before = deepCopyList(list);
        list = ensureBuiltinSourcesPresent(list);

        boolean dirty = isOldFormat;
        if (!dirty) dirty = !listsEquivalent(before, list);
        if (dirty) saveSourceList(list);

        return list;
    }

    private static List<SourceItem> deepCopyList(List<SourceItem> src) {
        List<SourceItem> r = new ArrayList<>(src.size());
        for (SourceItem s : src) {
            SourceItem c = new SourceItem(s.name, s.url);
            c.isDefault = s.isDefault;
            c.autoUpdate = s.autoUpdate;
            c.addTime = s.addTime;
            r.add(c);
        }
        return r;
    }
    private static boolean listsEquivalent(List<SourceItem> a, List<SourceItem> b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            SourceItem x = a.get(i), y = b.get(i);
            if (x == null && y == null) continue;
            if (x == null || y == null) return false;
            if (!eq(x.name, y.name)) return false;
            if (!eq(x.url, y.url)) return false;
            if (x.isDefault != y.isDefault) return false;
            if (x.autoUpdate != y.autoUpdate) return false;
        }
        return true;
    }
    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private List<SourceItem> buildBuiltinSources() {
        List<SourceItem> result = new ArrayList<>();
        if ("live_history".equals(spKey)) {
            SourceItem src1 = new SourceItem(BUILTIN_NAME_LIVE_1, rawOrFallback(UrlConfig.LIVE_URL_1_RAW, UrlConfig.LIVE_URL));
            src1.isDefault = true;
            src1.autoUpdate = true;
            result.add(src1);

            SourceItem src2 = new SourceItem(BUILTIN_NAME_LIVE_2, rawOrFallback(UrlConfig.LIVE_URL_2_RAW, UrlConfig.LIVE_URL_2));
            src2.isDefault = false;
            src2.autoUpdate = true;
            result.add(src2);

            SourceItem src3 = new SourceItem(BUILTIN_NAME_LIVE_3, rawOrFallback(UrlConfig.LIVE_URL_3_RAW, UrlConfig.LIVE_URL_CHANXIANG));
            src3.isDefault = false;
            src3.autoUpdate = true;
            result.add(src3);

            SourceItem src4 = new SourceItem(BUILTIN_NAME_LIVE_4, rawOrFallback(UrlConfig.LIVE_URL_4_RAW, UrlConfig.LIVE_URL_4));
            src4.isDefault = false;
            src4.autoUpdate = true;
            result.add(src4);

            SourceItem src5 = new SourceItem(BUILTIN_NAME_LIVE_5, rawOrFallback(UrlConfig.LIVE_URL_5_RAW, UrlConfig.LIVE_URL_5));
            src5.isDefault = false;
            src5.autoUpdate = true;
            result.add(src5);

            SourceItem src6 = new SourceItem(BUILTIN_NAME_LIVE_6, rawOrFallback(UrlConfig.LIVE_URL_6_RAW, UrlConfig.LIVE_URL_6));
            src6.isDefault = false;
            src6.autoUpdate = true;
            result.add(src6);

            SourceItem src7 = new SourceItem(BUILTIN_NAME_LIVE_7, rawOrFallback(UrlConfig.LIVE_URL_7_RAW, UrlConfig.LIVE_URL_7));
            src7.isDefault = false;
            src7.autoUpdate = true;
            result.add(src7);

            // 【2026-10-19】源8 换成 renyibo（原 4kvm 已下线，代码不再使用）
            SourceItem src8 = new SourceItem(BUILTIN_NAME_LIVE_8, rawOrFallback(UrlConfig.LIVE_URL_8_RAW, UrlConfig.LIVE_URL_8));
            src8.isDefault = false;
            src8.autoUpdate = true;
            result.add(src8);

            // 【2026-10-23】源9：橘汁点播（juzhi://all，加密 protobuf API）
            SourceItem src9 = new SourceItem(BUILTIN_NAME_LIVE_9, rawOrFallback(UrlConfig.LIVE_URL_9_RAW, UrlConfig.LIVE_URL_9));
            src9.isDefault = false;
            src9.autoUpdate = true;
            result.add(src9);
        } else if ("epg_history".equals(spKey)) {
            SourceItem src1 = new SourceItem(BUILTIN_NAME_EPG_1, rawOrFallback(UrlConfig.EPG_URL_1_RAW, UrlConfig.EPG_URL));
            src1.isDefault = true;
            src1.autoUpdate = true;
            result.add(src1);

            SourceItem src2 = new SourceItem(BUILTIN_NAME_EPG_2, rawOrFallback(UrlConfig.EPG_URL_2_RAW, UrlConfig.EPG_URL_2));
            src2.isDefault = false;
            src2.autoUpdate = true;
            result.add(src2);
        }
        return result;
    }

    private static String rawOrFallback(String raw, String fallback) {
        if (raw != null && !raw.isEmpty()) return raw;
        return fallback == null ? "" : fallback;
    }

    private static final class BuiltinSpec {
        final String name;
        final String url;
        final boolean autoUpdate;
        BuiltinSpec(String n, String u, boolean au) { this.name = n; this.url = u; this.autoUpdate = au; }
    }

    private BuiltinSpec[] getBuiltinSpecs() {
        if ("live_history".equals(spKey)) {
            return new BuiltinSpec[]{
                    new BuiltinSpec(BUILTIN_NAME_LIVE_1, rawOrFallback(UrlConfig.LIVE_URL_1_RAW, UrlConfig.LIVE_URL), true),
                    new BuiltinSpec(BUILTIN_NAME_LIVE_2, rawOrFallback(UrlConfig.LIVE_URL_2_RAW, UrlConfig.LIVE_URL_2), true),
                    new BuiltinSpec(BUILTIN_NAME_LIVE_3, rawOrFallback(UrlConfig.LIVE_URL_3_RAW, UrlConfig.LIVE_URL_CHANXIANG), true),
                    new BuiltinSpec(BUILTIN_NAME_LIVE_4, rawOrFallback(UrlConfig.LIVE_URL_4_RAW, UrlConfig.LIVE_URL_4), true),
                    new BuiltinSpec(BUILTIN_NAME_LIVE_5, rawOrFallback(UrlConfig.LIVE_URL_5_RAW, UrlConfig.LIVE_URL_5), true),
                    new BuiltinSpec(BUILTIN_NAME_LIVE_6, rawOrFallback(UrlConfig.LIVE_URL_6_RAW, UrlConfig.LIVE_URL_6), true),
                    new BuiltinSpec(BUILTIN_NAME_LIVE_7, rawOrFallback(UrlConfig.LIVE_URL_7_RAW, UrlConfig.LIVE_URL_7), true),
                    new BuiltinSpec(BUILTIN_NAME_LIVE_8, rawOrFallback(UrlConfig.LIVE_URL_8_RAW, UrlConfig.LIVE_URL_8), true),
                    new BuiltinSpec(BUILTIN_NAME_LIVE_9, rawOrFallback(UrlConfig.LIVE_URL_9_RAW, UrlConfig.LIVE_URL_9), true),
            };
        } else if ("epg_history".equals(spKey)) {
            return new BuiltinSpec[]{
                    new BuiltinSpec(BUILTIN_NAME_EPG_1, rawOrFallback(UrlConfig.EPG_URL_1_RAW, UrlConfig.EPG_URL), true),
                    new BuiltinSpec(BUILTIN_NAME_EPG_2, rawOrFallback(UrlConfig.EPG_URL_2_RAW, UrlConfig.EPG_URL_2), true),
            };
        }
        return new BuiltinSpec[0];
    }

    private List<SourceItem> ensureBuiltinSourcesPresent(List<SourceItem> existing) {
        BuiltinSpec[] specs = getBuiltinSpecs();
        if (specs.length == 0) return existing;

        existing.removeIf(si -> "内置源3 (本地666)".equals(si.name)
                || (si.url != null && si.url.startsWith("asset://live_source_3"))
                // 【2026-10-19】清掉旧源8（4kvm）残留：按旧名称与旧协议前缀识别，
                //   注意不能按 BUILTIN_NAME_LIVE_8 删 —— 那个常量已改指新站点。
                || "内置源8 (4kvm)".equals(si.name)
                || (si.url != null && si.url.startsWith("kvm4://")));

        // 内置源名称变更一律「就地改名迁移」，
        // 否则旧条目名称不再匹配 → 会被当成自定义源、同时又新增一条同名内置源（重复）。
        //  「内置源1 (GitHub)」   →「内置源1 (Gitee)」（源1 地址早已是 Gitee，旧名是漏改的误标）
        //  「内置源3」/「(畅享TV)」→「内置源3 (CXTV)」（标签统一为来源缩写）
        //  「内置源5 (爱其意)」   →「内置源5 (aiqiyi)」
        for (SourceItem si : existing) {
            if (BUILTIN_NAME_LIVE_1_LEGACY.equals(si.name)) {
                si.name = BUILTIN_NAME_LIVE_1;
            } else if (isLive3LegacyName(si.name)) {
                si.name = BUILTIN_NAME_LIVE_3;
            } else if (BUILTIN_NAME_LIVE_5_LEGACY.equals(si.name)) {
                si.name = BUILTIN_NAME_LIVE_5;
            }
        }

        for (BuiltinSpec spec : specs) {
            SourceItem best = null;
            List<SourceItem> all = new ArrayList<>();
            for (SourceItem si : existing) {
                boolean nameHit = spec.name != null && spec.name.equals(si.name);
                boolean urlMatch = spec.url != null && !spec.url.isEmpty() && spec.url.equals(si.url);
                boolean urlHit = urlMatch && isBuiltinNameForSpec(si.name, spec);
                if (!nameHit && !urlHit) continue;
                all.add(si);
                if (best == null) {
                    best = si;
                } else {
                    best = pickBetterMatch(best, si);
                }
            }
            if (best != null) {

                boolean hadOtherDefault = false;
                for (SourceItem si : all) {
                    if (si == best) continue;
                    if (si.isDefault) hadOtherDefault = true;
                    existing.remove(si);
                }
                if (hadOtherDefault) best.isDefault = true;

                if ((best.url == null || best.url.isEmpty()) && spec.url != null && !spec.url.isEmpty()) {
                    best.url = spec.url;
                }

                best.autoUpdate = spec.autoUpdate;

            }
        }



        for (BuiltinSpec spec : specs) {
            boolean found = false;
            for (SourceItem si : existing) {
                boolean nameHit = spec.name != null && spec.name.equals(si.name);
                boolean urlMatch = spec.url != null && !spec.url.isEmpty() && spec.url.equals(si.url);
                boolean urlHit   = urlMatch && isBuiltinNameForSpec(si.name, spec);
                if (nameHit || urlHit) { found = true; break; }
            }
            if (!found) {
                SourceItem s = new SourceItem(spec.name, spec.url);
                s.autoUpdate = spec.autoUpdate;
                existing.add(s);
            }
        }

        boolean hasDefault = false;
        for (SourceItem si : existing) {
            if (si.isDefault) { hasDefault = true; break; }
        }
        if (!hasDefault && !existing.isEmpty()) {

            SourceItem firstBuiltin1 = null;
            for (SourceItem si : existing) {
                if (BUILTIN_NAME_LIVE_1.equals(si.name) || BUILTIN_NAME_EPG_1.equals(si.name)) {
                    firstBuiltin1 = si; break;
                }
            }
            if (firstBuiltin1 != null) firstBuiltin1.isDefault = true;
            else existing.get(0).isDefault = true;
        }

        return existing;
    }

    private static SourceItem pickBetterMatch(SourceItem a, SourceItem b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.isDefault != b.isDefault) return a.isDefault ? a : b;
        boolean aUrlOk = a.url != null && !a.url.isEmpty();
        boolean bUrlOk = b.url != null && !b.url.isEmpty();
        if (aUrlOk != bUrlOk) return aUrlOk ? a : b;
        return a.addTime >= b.addTime ? a : b;
    }

    private static boolean isBuiltinNameForSpec(String siName, BuiltinSpec spec) {
        if (siName == null) return false;
        if (BUILTIN_NAME_LIVE_1.equals(spec.name)) return BUILTIN_NAME_LIVE_1.equals(siName)
                || BUILTIN_NAME_LIVE_1_LEGACY.equals(siName);
        if (BUILTIN_NAME_LIVE_2.equals(spec.name)) return BUILTIN_NAME_LIVE_2.equals(siName);
        if (BUILTIN_NAME_LIVE_3.equals(spec.name)) return BUILTIN_NAME_LIVE_3.equals(siName)
                || isLive3LegacyName(siName);
        if (BUILTIN_NAME_LIVE_4.equals(spec.name)) return BUILTIN_NAME_LIVE_4.equals(siName);
        if (BUILTIN_NAME_LIVE_5.equals(spec.name)) return BUILTIN_NAME_LIVE_5.equals(siName)
                || BUILTIN_NAME_LIVE_5_LEGACY.equals(siName);
        if (BUILTIN_NAME_LIVE_6.equals(spec.name)) return BUILTIN_NAME_LIVE_6.equals(siName);
        if (BUILTIN_NAME_LIVE_7.equals(spec.name)) return BUILTIN_NAME_LIVE_7.equals(siName);
        if (BUILTIN_NAME_EPG_1.equals(spec.name))  return BUILTIN_NAME_EPG_1.equals(siName);
        if (BUILTIN_NAME_EPG_2.equals(spec.name))  return BUILTIN_NAME_EPG_2.equals(siName);
        return false;
    }

    private void saveSourceList(List<SourceItem> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            SourceItem si = list.get(i);
            if (i > 0) sb.append("||");
            sb.append(si.name).append("##")
              .append(si.url).append("##")
              .append(si.isDefault ? "1" : "0").append("##")
              .append(si.autoUpdate ? "1" : "0").append("##")
              .append(si.addTime);
        }
        sp.edit().putString(spKey, sb.toString()).apply();
    }
}
