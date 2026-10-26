/**
 * @brief 爱奇艺播放地址解析
 * @author gxl<gxl@yuntutv.net>
 * @date 2015-9-22
 * @version 1.1.0.0
 *
 * 【2026-10-26 修复】基于原版畅享TV 的 iqiyi_vod.js 移植修复。
 *
 * 原版用页面里的 `data-player-tvid` / `data-player-videoid` 属性取参，
 * 但爱奇艺现已改为：
 *   ① www.iqiyi.com 会 302 跳到 m.iqiyi.com（插件的 getUrlContent 默认跟随 302，OK）；
 *   ② 页面里不再有 data-player-tvid 属性，改成 JSON 片段
 *      `"tvid":2471172165376700,"vid":"ac277247f0d1e94b35729e30be450c37"`。
 * 原版逻辑取不到 → substring 返回空串 → 拼出无效 tmts 地址 → 解析失败。
 *
 * 解析接口 `cache.m.iqiyi.com/tmts/...` 实测**仍然可用**（返回 data.vidl[]，
 * 每项带 m3utx = m3u8 地址），所以只需修好取参即可。
 */

//解析点播地址 http://www.iqiyi.com/v_19rrog0ulo.html
function IqiyiVodParsePlay(origUrl) {
    //1.获取爱奇艺网页内容
    var h5Content = getUrlContent(origUrl);
    if (!h5Content || h5Content.length <= 0) {
        return new Array();
    }

    //2.取 tvid / vid
    //   优先用新版 JSON 片段；取不到再回退旧版 data-player-* 属性
    var tvid = '';
    var vid = '';

    var mTvid = /"tvid"\s*:\s*(\d+)/.exec(h5Content);
    if (mTvid) {
        tvid = mTvid[1];
    } else {
        var iT = h5Content.indexOf('data-player-tvid="');
        if (iT >= 0) {
            tvid = h5Content.substring(iT + 18);
            tvid = tvid.substring(0, tvid.indexOf('"'));
        }
    }

    var mVid = /"vid"\s*:\s*"([0-9a-fA-F]{32})"/.exec(h5Content);
    if (mVid) {
        vid = mVid[1];
    } else {
        var iV = h5Content.indexOf('data-player-videoid="');
        if (iV >= 0) {
            vid = h5Content.substring(iV + 21);
            vid = vid.substring(0, vid.indexOf('"'));
        }
    }

    if (!tvid || !vid) {
        console.log("iqiyi: tvid/vid 取不到 tvid=" + tvid + " vid=" + vid);
        return new Array();
    }
    console.log("iqiyi: tvid=" + tvid + " vid=" + vid);

    //3.算出key
    var tm = new Date().getTime();
    var key = getMD5(tm + "d5fb4bd9d50c4be6948c97edd7254b0e" + tvid);
    var lurl = 'http://cache.m.iqiyi.com/tmts/' + tvid + '/' + vid
        + '/?t=' + tm + '&sc=' + key
        + '&src=76f90cbd92f94a2e925d83e8ccd22cb7'
        + '&uid=20140213141851016xMqipWjr10182';

    var content = getUrlContent(lurl);
    if (!content || content.length <= 0) {
        return new Array();
    }

    var jsonObject = null;
    try {
        jsonObject = eval('(' + content + ')');
    } catch (e) {
        console.log("iqiyi: JSON 解析失败 " + e);
        return new Array();
    }
    if (!jsonObject || !jsonObject.data || !jsonObject.data.vidl) {
        console.log("iqiyi: 返回里没有 data.vidl");
        return new Array();
    }
    var infos = jsonObject.data.vidl;
    if (!infos || infos.length === 0) {
        return new Array();
    }

    var urlArray = new Array();
    var vlen = infos.length;
    if (vlen === 1) {
        urlArray[0] = infos[0].m3utx + "#流畅";
    } else if (vlen === 2) {
        urlArray[0] = infos[0].m3utx + "#流畅";
        urlArray[1] = infos[1].m3utx + "#标清";
    } else {
        urlArray[0] = infos[0].m3utx + "#流畅";
        urlArray[1] = infos[1].m3utx + "#标清";
        urlArray[2] = infos[2].m3utx + "#高清";
    }
    return urlArray;
}
