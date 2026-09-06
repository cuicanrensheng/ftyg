package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.tv.live.Channel;
import com.tv.live.util.HuyaSDKParser;
import com.tv.live.util.LogBridge;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class HuyaTogetherWatchGroupManager {

    private static final String TAG = "TogetherWatchGroup";

    private static volatile HuyaTogetherWatchGroupManager sInstance;

    private static final int SDK_TAG_TIMEOUT_SEC = 8;

    private static final int MAX_TAG_PAGES = 10;

    private static final int CONCURRENCY = 4;

    public static final String GROUP_MOVIE    = "虎牙电影";
    public static final String GROUP_TV       = "虎牙电视剧";
    public static final String GROUP_ANIME    = "虎牙动漫";
    public static final String GROUP_VARIETY  = "虎牙综艺";
    public static final String GROUP_TOGETHER = "虎牙一起看";

    public static final String[] GROUP_NAMES = {
            GROUP_MOVIE, GROUP_TV, GROUP_ANIME, GROUP_VARIETY, GROUP_TOGETHER
    };

    private static final String[][] GROUP_KEYWORDS = new String[][] {
            { GROUP_MOVIE,    "电影,影视,纪录片,经典剧场,喜剧,恐怖,爱情,动作,武侠,科幻,大片" },
            { GROUP_TV,       "电视剧,剧集,连续剧,新剧,热播,高清,国语,古装,悬疑" },
            { GROUP_ANIME,    "动漫,动画,番剧,卡通,漫画" },
            { GROUP_VARIETY,  "综艺,真人秀,脱口秀,相声,演唱会,音乐会,春晚,小品" },
            { GROUP_TOGETHER, "一起看" },
    };

    private static final int CATEGORY_ID_TOGETHER_WATCH = 2135;

    private final ExecutorService mExecutor =
            Executors.newFixedThreadPool(CONCURRENCY);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean mFetching = new AtomicBoolean(false);

    private HuyaTogetherWatchGroupManager() {
    }

    public static HuyaTogetherWatchGroupManager getInstance() {
        if (sInstance == null) {
            synchronized (HuyaTogetherWatchGroupManager.class) {
                if (sInstance == null) {
                    sInstance = new HuyaTogetherWatchGroupManager();
                }
            }
        }
        return sInstance;
    }

    public interface OnGroupsListener {

        void onSuccess(List<Group> groups);

        void onError(String errMsg);
    }

    public interface OnChannelsListener {
        void onSuccess(List<Channel> channels);

        void onError(String errMsg);
    }

    public static class Group {
        public final String name;
        public final List<Channel> channels = new ArrayList<>();

        public Group(String name) {
            this.name = name;
        }

        public boolean isEmpty() {
            return channels.isEmpty();
        }
    }

    public void fetchAllGroups(final OnGroupsListener listener) {
        if (listener == null) return;
        if (!HuyaSDKParser.isSDKAvailable()) {
            postOnError(listener, "SDK 未初始化完成");
            return;
        }
        if (!mFetching.compareAndSet(false, true)) {
            postOnError(listener, "正在获取中，请稍候");
            return;
        }
        mExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    List<Group> result = doFetchAllGroupsBlocking();
                    if (result == null) {
                        postOnError(listener, "获取分组失败（SDK 分类列表为空或超时）");
                    } else {
                        postOnSuccess(listener, result);
                    }
                } catch (Throwable t) {
                    LogBridge.w(TAG, "fetchAllGroups 异常: " + t.getMessage());
                    postOnError(listener, "获取分组异常");
                } finally {
                    mFetching.set(false);
                }
            }
        });
    }

    public void fetchGroup(final String groupName, final OnChannelsListener listener) {
        if (listener == null) return;
        if (!isValidGroupName(groupName)) {
            postOnError(listener, "未知分组: " + groupName);
            return;
        }
        if (!HuyaSDKParser.isSDKAvailable()) {
            postOnError(listener, "SDK 未初始化完成");
            return;
        }
        if (!mFetching.compareAndSet(false, true)) {
            postOnError(listener, "正在获取中，请稍候");
            return;
        }
        mExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    List<Channel> channels = doFetchGroupBlocking(groupName);
                    if (channels == null) {
                        postOnError(listener, "获取分组失败（SDK 分类列表为空或超时）");
                    } else {
                        postOnSuccess(listener, channels);
                    }
                } catch (Throwable t) {
                    LogBridge.w(TAG, "fetchGroup(" + groupName + ") 异常: " + t.getMessage());
                    postOnError(listener, "获取分组异常");
                } finally {
                    mFetching.set(false);
                }
            }
        });
    }

    private List<Group> doFetchAllGroupsBlocking() throws Exception {
        Map<String, List<TagSpec>> byGroup = resolveSDKTagSpecsGrouped();
        if (byGroup == null) return null;

        Map<String, List<Channel>> recommend = fetchRecommendGroupedBlocking();

        ExecutorService groupPool = Executors.newFixedThreadPool(GROUP_NAMES.length);
        try {
            List<Future<Group>> groupFutures = new ArrayList<>();
            for (final String gName : GROUP_NAMES) {
                groupFutures.add(groupPool.submit(new Callable<Group>() {
                    @Override public Group call() {
                        Group g = new Group(gName);
                        try {
                            List<TagSpec> tags = byGroup.get(gName);
                            if (tags != null && !tags.isEmpty()) {
                                fetchChannelsIntoGroup(tags, g);
                            } else if (GROUP_TOGETHER.equals(gName)) {
                                g.channels.addAll(fetchFallbackTogetherBlocking(byGroup));
                            }

                            if (recommend != null) {
                                mergeChannels(g.channels, recommend.get(gName));
                            }
                        } catch (Throwable t) {
                            LogBridge.w(TAG, "分组[" + gName + "]并行拉取异常: " + t.getMessage());
                        }
                        return g;
                    }
                }));
            }
            List<Group> groups = new ArrayList<>();
            for (Future<Group> f : groupFutures) {
                try {
                    groups.add(f.get(SDK_TAG_TIMEOUT_SEC + 5, TimeUnit.SECONDS));
                } catch (Throwable t) {
                    LogBridge.w(TAG, "分组并行拉取超时/中断: " + t.getMessage());
                }
            }
            return groups;
        } finally {
            groupPool.shutdownNow();
        }
    }

    private List<Channel> doFetchGroupBlocking(String groupName) throws Exception {
        Map<String, List<TagSpec>> byGroup = resolveSDKTagSpecsGrouped();
        if (byGroup == null) return null;

        Group g = new Group(groupName);
        List<TagSpec> tags = byGroup.get(groupName);
        if (tags != null && !tags.isEmpty()) {
            fetchChannelsIntoGroup(tags, g);
        } else if (GROUP_TOGETHER.equals(groupName)) {

            g.channels.addAll(fetchFallbackTogetherBlocking(byGroup));
        }

        Map<String, List<Channel>> recommend = fetchRecommendGroupedBlocking();
        if (recommend != null) {
            mergeChannels(g.channels, recommend.get(groupName));
        }
        return g.channels;
    }

    private List<Channel> fetchFallbackTogetherBlocking(Map<String, List<TagSpec>> byGroup) {
        List<Channel> fallback = new ArrayList<>();
        List<TagSpec> allTags = new ArrayList<>();
        for (List<TagSpec> list : byGroup.values()) {
            if (list != null) allTags.addAll(list);
        }
        if (allTags.isEmpty()) {
            LogBridge.w(TAG, "【固定分组】虎牙一起看兜底: 无任何 tag");
            return fallback;
        }

        List<Future<List<Channel>>> futures = new ArrayList<>();
        for (final TagSpec t : allTags) {
            futures.add(mExecutor.submit(new Callable<List<Channel>>() {
                @Override public List<Channel> call() {
                    return fetchTagBlocking(t);
                }
            }));
        }
        Map<TagSpec, List<Channel>> tagChannels = new LinkedHashMap<>();
        for (int i = 0; i < allTags.size(); i++) {
            try {
                List<Channel> chs = futures.get(i).get(SDK_TAG_TIMEOUT_SEC + 2, TimeUnit.SECONDS);
                tagChannels.put(allTags.get(i), chs == null ? new ArrayList<Channel>() : chs);
            } catch (Throwable ignored) {
                tagChannels.put(allTags.get(i), new ArrayList<Channel>());
            }
        }

        LinkedHashSet<String> assignedIds = new LinkedHashSet<>();
        for (TagSpec t : allTags) {
            if (!GROUP_TV.equals(t.groupName) && !GROUP_ANIME.equals(t.groupName) && !GROUP_VARIETY.equals(t.groupName)) continue;
            for (Channel c : tagChannels.get(t)) {
                if (c == null) continue;
                assignedIds.add(c.getChannelId() != null ? c.getChannelId() : c.getName());
            }
        }

        LinkedHashSet<String> seenId = new LinkedHashSet<>();
        for (TagSpec t : allTags) {
            for (Channel c : tagChannels.get(t)) {
                if (c == null) continue;
                String key = c.getChannelId() != null ? c.getChannelId() : c.getName();
                if (assignedIds.contains(key)) continue;
                if (!seenId.add(key)) continue;
                try {
                    c.setGroup(GROUP_TOGETHER);
                    fallback.add(c);
                } catch (Throwable ignored) {  }
            }
        }
        LogBridge.i(TAG, "【固定分组】虎牙一起看兜底 频道数=" + fallback.size());
        if (fallback.isEmpty()) {

            LogBridge.w(TAG, "【固定分组】虎牙一起看兜底为空: 全部 tag 可能拉取失败, 已依赖推荐列表收容");
        }
        return fallback;
    }

    private void fetchChannelsIntoGroup(List<TagSpec> tags, final Group g) {
        List<Future<List<Channel>>> futures = new ArrayList<>();
        for (TagSpec t : tags) {
            futures.add(mExecutor.submit(new Callable<List<Channel>>() {
                @Override public List<Channel> call() {
                    return fetchTagBlocking(t);
                }
            }));
        }
        LinkedHashSet<Integer> seenRoom = new LinkedHashSet<>();
        LinkedHashSet<String> seenId = new LinkedHashSet<>();
        for (Future<List<Channel>> f : futures) {
            try {
                List<Channel> chs = f.get(SDK_TAG_TIMEOUT_SEC + 2, TimeUnit.SECONDS);
                if (chs == null || chs.isEmpty()) continue;
                for (Channel c : chs) {
                    if (c == null) continue;
                    boolean dup;
                    if (c.getHuyaRoomId() > 0) {
                        dup = !seenRoom.add(c.getHuyaRoomId());
                    } else {
                        dup = !seenId.add(c.getChannelId() != null ? c.getChannelId() : c.getName());
                    }
                    if (!dup) g.channels.add(c);
                }
            } catch (Throwable ignored) {

            }
        }
    }

    private static void mergeChannels(List<Channel> target, List<Channel> extra) {
        if (extra == null || extra.isEmpty()) return;
        LinkedHashSet<Integer> seenRoom = new LinkedHashSet<>();
        LinkedHashSet<String> seenId = new LinkedHashSet<>();
        for (Channel c : target) {
            if (c == null) continue;
            if (c.getHuyaRoomId() > 0) seenRoom.add(c.getHuyaRoomId());
            else seenId.add(c.getChannelId() != null ? c.getChannelId() : c.getName());
        }
        for (Channel c : extra) {
            if (c == null) continue;
            boolean dup;
            if (c.getHuyaRoomId() > 0) {
                dup = !seenRoom.add(c.getHuyaRoomId());
            } else {
                dup = !seenId.add(c.getChannelId() != null ? c.getChannelId() : c.getName());
            }
            if (!dup) target.add(c);
        }
    }

    private Map<String, List<Channel>> fetchRecommendGroupedBlocking() {
        List<com.huya.berry.client.customui.model.LiveListInfo> allList = new ArrayList<>();
        LinkedHashSet<Long> seenUids = new LinkedHashSet<>();
        int page = 0;
        while (page < MAX_TAG_PAGES) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<List<com.huya.berry.client.customui.model.LiveListInfo>> refResult = new AtomicReference<>();
            final AtomicReference<String> refErr = new AtomicReference<>();
            final boolean isMore = page > 0;
            try {
                HuyaSDKParser.getLiveList(isMore, new HuyaSDKParser.OnLiveListResultListener() {
                    @Override public void onSuccess(List<com.huya.berry.client.customui.model.LiveListInfo> list) {
                        refResult.set(list);
                        latch.countDown();
                    }
                    @Override public void onError(String err) {
                        refErr.set(err);
                        latch.countDown();
                    }
                });
                if (!latch.await(SDK_TAG_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    LogBridge.w(TAG, "【推荐列表】第" + (page + 1) + "页超时");
                    break;
                }
            } catch (Throwable t) {
                LogBridge.w(TAG, "【推荐列表】第" + (page + 1) + "页异常: " + t.getMessage());
                break;
            }
            if (refErr.get() != null) {
                LogBridge.i(TAG, "【推荐列表】第" + (page + 1) + "页结束(无更多): " + refErr.get());
                break;
            }
            List<com.huya.berry.client.customui.model.LiveListInfo> list = refResult.get();
            if (list == null || list.isEmpty()) break;
            int newCount = 0;
            for (com.huya.berry.client.customui.model.LiveListInfo info : list) {
                if (info == null) continue;
                long key = info.uid > 0 ? info.uid : info.channelId;
                if (key > 0 && seenUids.add(key)) {
                    allList.add(info);
                    newCount++;
                }
            }
            if (newCount == 0) break;
            page++;
        }
        if (allList.isEmpty()) return null;
        LogBridge.i(TAG, "【推荐列表】原始频道数=" + allList.size());

        Map<String, List<Channel>> grouped = new LinkedHashMap<>();
        for (String g : GROUP_NAMES) grouped.put(g, new ArrayList<Channel>());
        for (com.huya.berry.client.customui.model.LiveListInfo info : allList) {
            try {

                if (info.gameId != 0 && info.gameId != CATEGORY_ID_TOGETHER_WATCH) continue;
                String title = safeStr(info.title);
                String nick = safeStr(info.nickName);
                if (TextUtils.isEmpty(title) && TextUtils.isEmpty(nick)) continue;
                String display = TextUtils.isEmpty(title) ? nick : title;
                if ("精彩直播".equals(display) || "精彩节目".equals(display)) continue;
                String gName = assignGroupByKeywords(title, nick);
                if (gName == null) gName = GROUP_TOGETHER;
                Channel ch = buildChannelFromInfo(info, gName);
                if (ch == null) continue;
                grouped.get(gName).add(ch);
            } catch (Throwable ignored) {  }
        }
        int total = 0;
        for (List<Channel> v : grouped.values()) total += v.size();
        LogBridge.i(TAG, "【推荐列表】分类后频道数=" + total);
        if (total == 0) return null;
        return grouped;
    }

    private static String assignGroupByKeywords(String title, String nick) {
        String low = (safeStr(title) + " " + safeStr(nick)).toLowerCase();
        for (String[] row : GROUP_KEYWORDS) {
            String gName = row[0];
            for (String kw : row[1].split(",")) {
                if (!TextUtils.isEmpty(kw) && low.contains(kw.trim().toLowerCase())) {
                    return gName;
                }
            }
        }
        return null;
    }

    private static Channel buildChannelFromInfo(com.huya.berry.client.customui.model.LiveListInfo info, String groupName) {
        try {
            long channelId = info.channelId;
            long subId = info.subId;
            long uid = info.uid;
            if (channelId <= 0) return null;
            String title = safeStr(info.title);
            String nick = safeStr(info.nickName);
            String display = TextUtils.isEmpty(title)
                    ? (TextUtils.isEmpty(nick) ? "精彩节目" : nick) : title;
            if ("精彩直播".equals(display) || "精彩节目".equals(display)
                    || "精彩直播".equals(nick) || "精彩节目".equals(nick)) {
                return null;
            }
            long realProfileRoom = subId > 0 ? subId : channelId;
            int profileRoom = (realProfileRoom > 0 && realProfileRoom <= Integer.MAX_VALUE)
                    ? (int) realProfileRoom : 0;
            String channelIdStr;
            if (uid > 0) channelIdStr = "huya_uid_" + uid;
            else if (profileRoom > 0) channelIdStr = "huya_" + profileRoom;
            else channelIdStr = "huya_long_" + channelId;
            Channel ch;
            if (uid > 0) {
                ch = new Channel(display, "huya://uid/" + uid, groupName, channelIdStr, true, profileRoom);
                ch.setHuyaUid(uid);
            } else if (profileRoom > 0) {
                ch = new Channel(display, "huya://room/" + profileRoom, groupName, channelIdStr, true, profileRoom);
            } else {
                return null;
            }
            ch.setHuyaSdkTogetherWatch(true);
            return ch;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<Channel> fetchTagBlocking(final TagSpec tag) {

        List<com.huya.berry.client.customui.model.LiveListInfo> allList = new ArrayList<>();
        LinkedHashSet<Long> seenUids = new LinkedHashSet<>();
        int page = 0;
        while (page < MAX_TAG_PAGES) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<List<com.huya.berry.client.customui.model.LiveListInfo>> refResult =
                    new AtomicReference<>();
            final AtomicReference<String> refErr = new AtomicReference<>();
            final boolean isMore = page > 0;
            try {

                HuyaSDKParser.getLiveListByTag(tag.tagId, isMore,
                        new HuyaSDKParser.OnLiveListResultListener() {
                            @Override public void onSuccess(List<com.huya.berry.client.customui.model.LiveListInfo> list) {
                                refResult.set(list);
                                latch.countDown();
                            }
                            @Override public void onError(String err) {
                                refErr.set(err);
                                latch.countDown();
                            }
                        });
                if (!latch.await(SDK_TAG_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    LogBridge.w(TAG, "【tag=" + tag.tagName + "】第" + (page + 1) + "页超时");
                    break;
                }
            } catch (Throwable t) {
                LogBridge.w(TAG, "【tag=" + tag.tagName + "】第" + (page + 1) + "页异常: " + t.getMessage());
                break;
            }
            if (refErr.get() != null) {
                LogBridge.i(TAG, "【tag=" + tag.tagName + "】第" + (page + 1) + "页结束(无更多): " + refErr.get());
                break;
            }
            List<com.huya.berry.client.customui.model.LiveListInfo> list = refResult.get();
            if (list == null || list.isEmpty()) break;
            int newCount = 0;
            for (com.huya.berry.client.customui.model.LiveListInfo info : list) {
                if (info == null) continue;
                long uid = info.uid;
                long channelId = info.channelId;
                long key = uid > 0 ? uid : channelId;
                if (seenUids.add(key)) {
                    allList.add(info);
                    newCount++;
                }
            }
            if (newCount == 0) {
                LogBridge.i(TAG, "【tag=" + tag.tagName + "】第" + (page + 1) + "页无新频道，停止翻页");
                break;
            }
            page++;
        }
        if (allList.isEmpty()) return null;
        LogBridge.i(TAG, "【tag=" + tag.tagName + "】原始列表=" + allList.size());

        List<Channel> result = new ArrayList<>();
        for (com.huya.berry.client.customui.model.LiveListInfo info : allList) {
            try {
                long channelId = info.channelId;
                long subId = info.subId;
                long uid = info.uid;
                if (channelId <= 0) continue;
                String title = safeStr(info.title);
                String nick = safeStr(info.nickName);
                String display = (TextUtils.isEmpty(title)
                        ? (TextUtils.isEmpty(nick) ? "精彩节目" : nick) : title);

                int roomId = (channelId > 0 && channelId <= Integer.MAX_VALUE) ? (int) channelId : 0;
                long realProfileRoom = subId > 0 ? subId : channelId;
                int profileRoom = (realProfileRoom > 0 && realProfileRoom <= Integer.MAX_VALUE) ? (int) realProfileRoom : 0;

                if (TextUtils.isEmpty(display)
                        || "精彩直播".equals(display) || "精彩节目".equals(display)
                        || "精彩直播".equals(nick) || "精彩节目".equals(nick)) {
                    continue;
                }

                String channelIdStr;
                if (uid > 0) channelIdStr = "huya_uid_" + uid;
                else if (roomId > 0) channelIdStr = "huya_" + roomId;
                else channelIdStr = "huya_long_" + channelId;
                Channel ch;
                if (uid > 0) {

                    ch = new Channel(display, "huya://uid/" + uid,
                            tag.groupName, channelIdStr, true, profileRoom);
                    ch.setHuyaUid(uid);
                } else if (profileRoom > 0) {

                    ch = new Channel(display, "huya://room/" + profileRoom,
                            tag.groupName, channelIdStr, true, profileRoom);
                } else {
                    continue;
                }

                ch.setHuyaSdkTogetherWatch(true);
                result.add(ch);
            } catch (Throwable ignored) {

            }
        }
        LogBridge.i(TAG, "【tag=" + tag.tagName + "(" + tag.tagId + ")】解析频道数=" + result.size());
        return result;
    }

    private Map<String, List<TagSpec>> resolveSDKTagSpecsGrouped() {
        List<TagSpec> specs = resolveSDKTagSpecsBlocking();
        if (specs == null) return null;

        Map<String, List<TagSpec>> byGroup = new LinkedHashMap<>();
        for (String gName : GROUP_NAMES) {
            byGroup.put(gName, new ArrayList<TagSpec>());
        }
        for (TagSpec t : specs) {
            List<TagSpec> list = byGroup.get(t.groupName);
            if (list != null) list.add(t);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<TagSpec>> e : byGroup.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue().size()).append(' ');
        }
        LogBridge.i(TAG, "【SDK→分组】分类分布: " + sb.toString().trim());
        return byGroup;
    }

    private List<TagSpec> resolveSDKTagSpecsBlocking() {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<List<Object>> refTags = new AtomicReference<>();
        final AtomicReference<String> refErr = new AtomicReference<>();
        try {
            HuyaSDKParser.getTagList(new HuyaSDKParser.OnTagListResultListener() {
                @Override public void onSuccess(List<Object> tagList) {
                    refTags.set(tagList);
                    latch.countDown();
                }
                @Override public void onError(String err) {
                    refErr.set(err);
                    latch.countDown();
                }
            });
            if (!latch.await(SDK_TAG_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                LogBridge.w(TAG, "getTagList 超时");
                return null;
            }
        } catch (Throwable t) {
            LogBridge.w(TAG, "getTagList 异常: " + t.getMessage());
            return null;
        }
        if (refErr.get() != null) {
            LogBridge.w(TAG, "getTagList 失败: " + refErr.get());
            return null;
        }
        List<Object> rawTags = refTags.get();
        if (rawTags == null || rawTags.isEmpty()) {
            LogBridge.w(TAG, "getTagList 返回空列表");
            return null;
        }

        List<TagSpec> result = new ArrayList<>();
        for (Object tagObj : rawTags) {
            if (tagObj == null) continue;
            Pair pair = extractTagIdAndName(tagObj);
            if (pair == null) continue;
            String groupName = matchGroupName(pair.name);
            if (groupName == null) {

                groupName = GROUP_TOGETHER;
            }
            result.add(new TagSpec(pair.id, pair.name, groupName));
        }
        return result;
    }

    private static String matchGroupName(String tagName) {
        if (TextUtils.isEmpty(tagName)) return null;
        String n = tagName.trim();
        String low = n.toLowerCase();

        if ("全部".equals(n)) return GROUP_MOVIE;
        if ("最新".equals(n)) return GROUP_TV;
        if ("up".equals(low)) return GROUP_ANIME;
        if ("综艺".equals(n)) return GROUP_VARIETY;

        for (String[] row : GROUP_KEYWORDS) {
            String groupName = row[0];
            String kws = row[1];
            for (String kw : kws.split(",")) {
                if (!TextUtils.isEmpty(kw) && low.contains(kw.trim().toLowerCase())) {
                    return groupName;
                }
            }
        }
        return null;
    }

    private static Pair extractTagIdAndName(Object tagObj) {
        try {
            Class<?> c = tagObj.getClass();
            Field[] fields = c.getFields();
            String bestId = null;
            String bestName = null;
            for (Field f : fields) {
                try {
                    f.setAccessible(true);
                    String fname = f.getName();
                    Object val = f.get(tagObj);
                    if (val == null) continue;
                    String lower = fname.toLowerCase();
                    if (bestId == null) {
                        if ("id".equals(lower) || "tagid".equals(lower) || "categoryid".equals(lower)) {
                            bestId = String.valueOf(val);
                            continue;
                        }
                        if (val instanceof String) {
                            String sval = (String) val;
                            if (!sval.isEmpty() && isNumericString(sval)) {
                                if (fname.contains("id") || fname.contains("Id") || fname.length() <= 4) {
                                    bestId = sval;
                                    continue;
                                }
                            }
                        }
                    }
                    if (bestName == null && val instanceof String) {
                        String sval = (String) val;
                        if (!sval.isEmpty()) {
                            if ("name".equals(lower) || "tagname".equals(lower) || "cname".equals(lower)
                                    || "title".equals(lower) || "displayname".equals(lower) || "categoryname".equals(lower)) {
                                bestName = sval;
                            }
                        }
                    }
                } catch (Throwable ignored) { }
            }
            if (bestName == null) {
                for (Field f : fields) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(tagObj);
                        if (v instanceof String && !TextUtils.isEmpty((String) v)
                                && !isNumericString((String) v) && ((String) v).length() >= 2) {
                            bestName = (String) v;
                            break;
                        }
                    } catch (Throwable ignored) { }
                }
            }
            if (bestId == null || bestName == null) return null;
            return new Pair(bestId, bestName);
        } catch (Throwable t) {
            LogBridge.w(TAG, "反射Tag失败(" + tagObj.getClass().getSimpleName() + "): " + t.getMessage());
            return null;
        }
    }

    private static boolean isValidGroupName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        for (String g : GROUP_NAMES) {
            if (g.equals(name)) return true;
        }
        return false;
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    private static boolean isNumericString(String s) {
        if (TextUtils.isEmpty(s)) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    private void postOnSuccess(final OnGroupsListener listener, final List<Group> groups) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                listener.onSuccess(groups);
            }
        });
    }

    private void postOnError(final OnGroupsListener listener, final String err) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                listener.onError(err);
            }
        });
    }

    private void postOnSuccess(final OnChannelsListener listener, final List<Channel> channels) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                listener.onSuccess(channels);
            }
        });
    }

    private void postOnError(final OnChannelsListener listener, final String err) {
        mMainHandler.post(new Runnable() {
            @Override public void run() {
                listener.onError(err);
            }
        });
    }

    private static final class TagSpec {
        final String tagId;
        final String tagName;
        final String groupName;

        TagSpec(String tagId, String tagName, String groupName) {
            this.tagId = tagId;
            this.tagName = tagName;
            this.groupName = groupName;
        }
    }

    private static final class Pair {
        final String id;
        final String name;

        Pair(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
