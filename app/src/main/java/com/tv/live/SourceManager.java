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

    public static final String BUILTIN_NAME_LIVE_1 = "内置源1 (GitHub)";
    public static final String BUILTIN_NAME_LIVE_2 = "内置源2 (Gitee)";
    public static final String BUILTIN_NAME_LIVE_3 = "内置源3 (本地666)";
    public static final String BUILTIN_NAME_EPG_1  = "内置节目单1 (Catvod)";
    public static final String BUILTIN_NAME_EPG_2  = "内置节目单2 (ERW)";

    public static boolean isBuiltin(SourceItem si, String spKey) {
        if (si == null) return false;
        String n = si.name == null ? "" : si.name;
        if ("live_history".equals(spKey)) {
            if (BUILTIN_NAME_LIVE_1.equals(n) || BUILTIN_NAME_LIVE_2.equals(n) || BUILTIN_NAME_LIVE_3.equals(n)) return true;
            if (si.url != null && (UrlConfig.LIVE_URL.equals(si.url) || UrlConfig.LIVE_URL_2.equals(si.url))) return true;
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

        existing.removeIf(si -> BUILTIN_NAME_LIVE_3.equals(si.name)
                || (si.url != null && si.url.startsWith("asset://live_source_3")));

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
        if (BUILTIN_NAME_LIVE_1.equals(spec.name)) return BUILTIN_NAME_LIVE_1.equals(siName);
        if (BUILTIN_NAME_LIVE_2.equals(spec.name)) return BUILTIN_NAME_LIVE_2.equals(siName);
        if (BUILTIN_NAME_LIVE_3.equals(spec.name)) return BUILTIN_NAME_LIVE_3.equals(siName);
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
