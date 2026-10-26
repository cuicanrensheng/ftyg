var Seed = "#$#@#*ad";
var rnd = new Date().getTime().toString().substring(0, 10);
var vP; var playData = []; var playIdx = 0;
var Link = "";
function vParse_Error(data) {

}
function vParse_Play(data) {
    if (typeof data.image == 'undefined') { data.image = ''; }
    playData = data;
    //if(!partPlay && data.urls.length > 1){ partPlay = true; vParse_Error({message:'视频为分段播放，几分钟一段<br>播放完一段会自动播放下一段~'}); setTimeout('a1.style.display = "block"; a1.innerHTML = ""; vParse_Play(playData);', 40); }
    if (data.urls.length == 0) { vParse_Error(); return }


    var playLink = playData.urls[0].u;
    Link = playLink;
    log(playLink);
    //  window.Android.PlayVideo(playLink)


}

var navigator = {
    userAgent: "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.113 Safari/537.36"

};

//[Abc]

function vParse_Report(BossId, rptPwd, rptData, callback) { };
var gdjiexi = {
    cookie: {
        get: function (name) {
            var arr = "";
            var reg = new RegExp("(^| )" + name + "=([^;]*)(;|$)");
            if (arr = document.cookie.match(reg)) {
                return unescape(arr[2]);
            } else {
                return null;
            }
        }, set: function (name, value) {
            var Days = 10;
            var exp = new Date();
            exp.setTime(exp.getTime() + Days * 24 * 60 * 60 * 1000);
            document.cookie = name + "=" + escape(value) + ";expires=" + exp.toGMTString();
        }
    }, qq: {
        parse: function (params) {
            params.ucookie = false;
            if (params.ucookie) {
                var guid = gdjiexi.cookie.get("vParse_QQGuid");
                if (guid && guid != "undefined") {
                    params.guid = guid;
                    params.ucookie = false;
                } else {
                    gdjiexi.cookie.set("vParse_QQGuid", params.guid);
                }
                ;
            }
            ; var pageUrl = params.cover ? "https://v.qq.com/x/cover/" + params.cover + "/" + params.vid + ".html" : "https://v.qq.com/x/page/" + params.vid + ".html";
            //  var osname = /Windows/.test(navigator.userAgent) ? "windows" : "mac";
            osname = "mac";
            var osver = osname == "windows" ? "6.1" : "10.12.3";
            var platform = osname == "windows" ? 10201 : 10901;
            params.osname = osname;
            params.osver = osver;
            params.platform = platform;
            params.pversion = params.dltype == 3 ? "safarihls" : "html5hd";
            params.pvid = parseInt(Math.random() * 50000000);
            gdjiexi.data = params;
            gdjiexi.data.pageUrl = pageUrl;
            gdjiexi.data.reportUrls = ["//btrace.video.qq.com/kvcollect?BossId=3098&Pwd=390647416&oper=1&main_type=&uin=0&host=v.qq.com&url=" + encodeURIComponent(pageUrl) + "&_dc=" + Math.random(), "//btrace.video.qq.com/kvcollect?BossId=4501&Pwd=142347456&loginid=&loginex=&logintype=0&guid=" + params.guid + "&longitude=&latitude=&vip=0&online=1&p2p=0&downloadkit=0&resolution=1920*1080*1&testid=&osver=" + params.osname + "+" + params.osver + "&playerver=" + params.appver + "&playertype=1&uip=&confid=&cdnip=&cdnuip=&freetype=&sstrength=&network=&speed=&device=&appver=&p2pver=&url=" + encodeURIComponent(pageUrl) + "&refer=http%3A%2F%2Fv.qq.com%2F&ua=" + encodeURIComponent(navigator.userAgent) + "&ptag=&flowid=" + params.pid + "_" + params.platform + "&platform=" + params.platform + "&dltype=" + params.dltype + "&vid=" + params.vid + "&fmt=&rate=&clip=&status=&type=&duration=&data=%7B%22code%22%3A%22%22%2C%22stime%22%3A" + new Date().getTime() + "%7D&step=0&seq=0", "//btrace.video.qq.com/kvcollect?BossId=4564&Pwd=213967996&loginid=&loginex=&logintype=&guid=" + params.guid + "&online=1&resolution=1920*1080*1&osver=" + params.osname + "+" + params.osver + "&playerver=" + params.appver + "&playertype=1&flowid=" + params.pid + "_" + params.platform + "&platform=" + params.platform + "&vid=" + params.vid + "&url=" + encodeURIComponent(pageUrl) + "&refer=http%3A%2F%2Fv.qq.com%2F&ua=" + encodeURIComponent(navigator.userAgent) + "&ptag=&data=%7B%22name%22%3A%22H5PlayerAd%22%2C%22ptime%22%3A0%2C%22stime%22%3A" + new Date().getTime() + "%2C%22etime%22%3A" + (new Date().getTime() + 33) + "%2C%22url%22%3A%22%2F%2Fca.gtimg.com%2Fadplugin%2Fjs%2Fadplayer.js%3F_ts%3D20170414%22%7D&step=61", "getInfo"];
            gdjiexi.data.reportCount = 0;

            urlRequest("http:" + gdjiexi.data.reportUrls[0]);
            gdjiexi.qq.reportSuccess();
        }, reportSuccess: function () {
            gdjiexi.data.reportCount++;
            var rptUrl = gdjiexi.data.reportUrls[gdjiexi.data.reportCount];
            if (rptUrl == "getInfo") {
                gdjiexi.qq.getInfo();
                return
            } else if (rptUrl == "getKeys") {
                gdjiexi.qq.getKeys();
                return
            }
            ;
            if (gdjiexi.data.reportCount >= gdjiexi.data.reportUrls.length) {
                return
            };

            urlRequest("http:" + rptUrl);
            gdjiexi.qq.reportSuccess();
        }, getInfo: function () {
            params = gdjiexi.data;
            var getinfo = "//h5vv.video.qq.com/getinfo?callback=?&charge=0&vid=" + params.vid + "&defaultfmt=auto&otype=json&guid=" + params.guid + "&platform=" + params.platform + "&defnpayver=1&appVer=" + params.appver + "&sdtfrom=v1010&host=v.qq.com&ehost=" + encodeURIComponent(params.pageUrl) + "&_rnd=" + params.rnd + "&defn=" + params.fmt + "&fhdswitch=0&show1080p=1&" + (params.dltype == 3 ? "isHLS=1&dtype=3&sphls=1" : "isHLS=0") + "&newplatform=" + params.platform + "&defsrc=1&_qv_rmt=" + params.q1 + "&_qv_rmt2=" + params.q2;
            gdjiexi.data.sc = 0;
            gdjiexi.data.urls = [], gdjiexi.data.vurls = [];
            //  ###########################################################################################

            var g = urlRequestWithPara("http:" + getinfo, "tvfe_boss_uuid=9f1f9c85cb9d4770; pgv_info=ssid=s9075651936; pgv_pvid=1871101368", "Cookie");
            g = g.substring(g.indexOf("(") + 1, g.lastIndexOf(")"));
            var g = eval('(' + g + ')');
            if (typeof g.vl == "undefined") {
                return;
            }
            ;
            if (g.dltype == 3) {
                gdjiexi.delayPlayData = {
                    success: true,
                    urls: [{
                        u: g.vl.vi[0].ul.ui[0].url + g.vl.vi[0].ul.ui[0].hls.pt,
                        d: parseInt(params.ds),
                        s: 0
                    }],
                    type: "m3u8",
                    image: params.img
                };
                vParse_Play(gdjiexi.delayPlayData);
                return;
            }
            ; uidx = 0;
            for (var uix in g.vl.vi[0].ul.ui) {
                if (!/\d+\.\d+\.\d+\.\d+/.test(g.vl.vi[0].ul.ui[uix].url) && 1 + 1 == 3) {
                    uidx = uix;
                    break;
                }
                ;
            }
            ; params.cdn = g.vl.vi[0].ul.ui[uidx].url;
            params.vt = g.vl.vi[0].ul.ui[uidx].vt;
            gdjiexi.data = params;
            if (g.vl.vi[0].fn == params.filename.split("|")[0].replace(".1.", ".")) {
                gdjiexi.data.sc++;
                gdjiexi.data.urls.push({ idx: 0, key: g.vl.vi[0].fvkey, fn: params.filename.split("|")[0] });
            }
            ; var firstUrl = params.cdn + params.filename.split("|")[0] + "?sdtfrom=v1010&guid=" + params.guid + "&vkey=" + g.vl.vi[0].fvkey;
            gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=3487&Pwd=745225177&itype=0&idx=1&ptag=&qtag=x&refer=http%3A%2F%2Fv.qq.com%2F&url=" + encodeURIComponent(params.pageUrl) + "&type=2&_=" + Math.ceil(1000 * Math.random()));
            gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=4501&Pwd=142347456&seq=1&loginid=&loginex=&logintype=0&guid=" + params.guid + "&longitude=&latitude=&vip=0&online=1&p2p=0&downloadkit=0&resolution=1920*1080*1&testid=&osver=" + params.osname + "+" + params.osver + "&playerver=" + params.appver + "&playertype=1&uip=&confid=&cdnip=&cdnuip=&freetype=&sstrength=&network=&speed=&device=&appver=&p2pver=&url=" + encodeURIComponent(params.pageUrl) + "&refer=http%3A%2F%2Fv.qq.com%2F&ua=" + encodeURIComponent(navigator.userAgent) + "&ptag=&flowid=" + params.pid + "_" + params.platform + "&platform=" + params.platform + "&dltype=" + params.dltype + "&vid=" + params.vid + "&fmt=&rate=&clip=&status=&type=&duration=&data=%7B%22stime%22%3A" + (new Date().getTime() - 5000 - Math.ceil(25 * Math.random())) + "%2C%22etime%22%3A" + new Date().getTime() + "%2C%22code%22%3A%22%22%7D&step=5");
            gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=3721&Pwd=1636975887&version=" + params.appver + "&uid=" + params.guid + "&pid=" + params.pid + "&vid=" + params.vid + "&player_type=" + params.pversion + "&video_type=1&platform=" + params.platform + "&url=" + encodeURIComponent(params.pageUrl) + "&filename=txplayer.js&sub_version=" + "58.0.300" + (Math.ceil(Math.random() * 10)) + "." + (Math.ceil(Math.random() * 10)) + "&_dc=" + Math.random() + "&browser=chrome&osname=" + params.osname + "&osver=" + params.osver + "&hc_uin=&hc_main_login=&hc_vuserid=&hc_openid=&hc_appid=&hc_ua=" + encodeURIComponent(navigator.userAgent) + "&hh_ref=" + encodeURIComponent(params.pageUrl));
            gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=4564&Pwd=213967996&flowid=" + params.pid + "_" + params.platform + "&data=%7B%22stime%22%3A" + new Date().getTime() + "%2C%22etime%22%3A" + (new Date().getTime() + 800 + Math.ceil(100 * Math.random())) + "%2C%22code%22%3A%22%22%2C%22ip%22%3A%22" + params.uip + "%22%2C%22url%22%3A%22" + encodeURIComponent(getinfo) + "&step=15");
            gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=4501&Pwd=142347456&loginid=&loginex=&logintype=0&guid=" + params.guid + "&longitude=&latitude=&vip=0&online=1&p2p=0&downloadkit=0&resolution=1920*1080*1&testid=&osver=" + params.osname + "+" + params.osver + "&playerver=" + params.appver + "&playertype=1&uip=" + params.uip + "&confid=&cdnip=&cdnuip=&freetype=&sstrength=&network=&speed=&device=&appver=&p2pver=&url=" + encodeURIComponent(params.pageUrl) + "&refer=http%3A%2F%2Fv.qq.com%2F&ua=" + encodeURIComponent(params.pageUrl) + "&ptag=&flowid=" + params.pid + "_" + params.platform + "&platform=" + params.platform + "&dltype=" + params.dltype + "&vid=" + params.vid + "&fmt=" + params.format + "&rate=" + params.br + "&clip=" + params.filename.split("|").length + "&status=2&type=1036&duration=" + params.dur + "&data=%7B%22ip%22%3A%22" + params.uip + "%22%2C%22stime%22%3A" + new Date().getTime() + "%2C%22etime%22%3A" + (new Date().getTime() + 800 + Math.ceil(100 * Math.random())) + "%2C%22code%22%3A%22%22%7D&step=15&seq=2");
            gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=4298&Pwd=686148428&uin=&vid=" + params.vid + "&coverid=" + params.cover + "&pid=" + params.pid + "&guid=" + params.guid + "&vt=&type=&url=" + encodeURIComponent(params.pageUrl) + "&bi=&bt=&version=" + params.appver + "&platform=" + params.platform + "&format=&defn=&ctime=" + encodeURIComponent(params.ctime) + "&ptag=&isvip=0&tpid=2&pversion=" + params.pversion + "&hc_uin=&hc_main_login=&hc_vuserid=&hc_openid=&hc_appid=&hc_pvid=&hc_ssid=&hc_qq=&hh_ua=" + encodeURIComponent(navigator.userAgent) + "&ckey=&iformat=&hh_ref=" + encodeURIComponent(params.pageUrl) + "&v_idx=0&rcd_info=&vurl=&step=3&val=1&idx=0&isfocustab=1&isvisible=1");
            gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=4298&Pwd=686148428&uin=&vid=" + params.vid + "&coverid=" + params.cover + "&pid=" + params.pid + "&guid=" + params.guid + "&vt=0&type=1036&url=" + encodeURIComponent(params.pageUrl) + "&bi=" + parseInt(params.ds.split("|")[0]) + "&bt=" + parseInt(params.dur) + "&version=" + params.appver + "&platform=" + params.platform + "&format=" + params.format + "&defn=" + params.fmt + "&ctime=" + encodeURIComponent(params.ctime) + "&ptag=&isvip=0&tpid=2&pversion=" + params.pversion + "&hc_uin=&hc_main_login=&hc_vuserid=&hc_openid=&hc_appid=&hc_pvid=&hc_ssid=&hc_qq=&hh_ua=" + encodeURIComponent(navigator.userAgent) + "&ckey=" + g.vl.vi[0].fvkey + "&iformat=" + params.format + "&hh_ref=" + encodeURIComponent(params.pageUrl) + "&v_idx=0&rcd_info=&vurl=" + encodeURIComponent(firstUrl) + "&step=1011&val1=1&val2=0&val=5" + Math.ceil(100 * Math.random()));
            gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=4298&Pwd=686148428&uin=&vid=" + params.vid + "&coverid=" + params.cover + "&pid=" + params.pid + "&guid=" + params.guid + "&vt=" + params.vt + "&type=1036&url=" + encodeURIComponent(params.pageUrl) + "&bi=1&bt=" + parseInt(params.dur) + "&version=" + params.appver + "&platform=" + params.platform + "&format=" + params.format + "&defn=" + params.fmt + "&ctime=" + encodeURIComponent(params.ctime) + "&ptag=&isvip=0&tpid=2&pversion=" + params.pversion + "&hc_uin=&hc_main_login=&hc_vuserid=&hc_openid=&hc_appid=&hc_pvid=" + Math.ceil(1e10 * Math.random()) + "&hc_ssid=&hc_qq=&hh_ua=" + encodeURIComponent(navigator.userAgent) + "&ckey=" + g.vl.vi[0].fvkey + "&iformat=" + params.format + "&hh_ref=" + encodeURIComponent(params.pageUrl) + "&v_idx=0&rcd_info=&vurl=" + encodeURIComponent(firstUrl) + "&step=4&val=8&val1=1&idx=0&diagonal=1273&isfocustab=1&isvisible=1");
            gdjiexi.data.reportUrls.push("getKeys");
            gdjiexi.qq.reportSuccess();


        }, getKeys: function () {
            params = gdjiexi.data;
            if (params.filename.split("|").length == 1 && gdjiexi.data.urls.length == 1) {
                gdjiexi.delayPlayData = {
                    success: true,
                    urls: [{
                        u: params.cdn + gdjiexi.data.urls[0].fn + "?sdtfrom=v1010&guid=" + params.guid + "&vkey=" + gdjiexi.data.urls[0].key,
                        d: parseInt(params.ds),
                        s: parseInt(params.ss)
                    }],
                    type: "mp4",
                    image: params.img
                };
                vParse_Play(gdjiexi.delayPlayData);
            }
            ; var qstr = {
                charge: 0,
                vid: params.vid,
                filename: "",
                format: params.format,
                otype: "json",
                guid: params.guid,
                platform: params.platform,
                defnpayver: 0,
                appVer: params.appver,
                vt: params.vt,
                sdtfrom: "v1010",
                _rnd: params.rnd,
                _qv_rmt: params.q1,
                _qv_rmt2: params.q2
            };
            var filenames = params.filename.split("|");
            var fn = "";
            for (var i in filenames) {
                if (gdjiexi.data.sc > 0 && i == 0) {
                    continue
                }
                ; fn = filenames[i];
                qstr.filename = fn;


                var url = "http://h5vv.video.qq.com/getkey"
                var _str = "";
                for (var o in qstr) {
                    if (qstr[o] != -1) {
                        _str += o + "=" + qstr[o] + "&";
                    }
                }
                var url = url + "?" + _str.substring(0, _str.length - 1); //url拼接
                //###############################################################################################################
                var url = "http://h5vv.video.qq.com/getkey";
                var _str = "";
                for (var o in qstr) {
                    if (qstr[o] != -1) {
                        _str += o + "=" + qstr[o] + "&";
                    }
                }
                var url = url + "?" + _str.substring(0, _str.length - 1) + "&callback=jQuery321082896847561137_1505288742156&_=1505288742157";
                log("22222222" + url);
                var e = urlRequestWithPara(url, "tvfe_boss_uuid=9f1f9c85cb9d4770; pgv_info=ssid=s9075651936; pgv_pvid=1871101368", "Cookie");

                e = e.substring(e.indexOf("(") + 1, e.lastIndexOf(")"));

                var e = eval('(' + e + ')');

                gdjiexi.data.sc++;
                gdjiexi.data.urls.push({ idx: i, key: e.key, fn: fn });
                if (gdjiexi.data.sc >= filenames.length) {
                    var seconds = params.ds.split("|");
                    var size = params.ss.split("|");
                    for (var _u in gdjiexi.data.urls) {
                        u = gdjiexi.data.urls[_u];
                        gdjiexi.data.vurls[u.idx] = {
                            u: params.cdn + u.fn + "?sdtfrom=v1010&guid=" + params.guid + "&vkey=" + gdjiexi.data.urls[0].key,
                            d: parseInt(seconds[u.idx]), s: parseInt(size[u.idx])
                        };
                    }
                    ; gdjiexi.delayPlayData = {
                        success: true,
                        urls: gdjiexi.data.vurls,
                        type: "mp4",
                        image: params.img
                    };
                    vParse_Play(gdjiexi.delayPlayData);
                    gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=3699&Pwd=1439649858&type=10&pageType=4&usertype=0&playertype=" + params.pversion + "&ua=" + encodeURIComponent(navigator.userAgent) + "&url=" + encodeURIComponent(params.pageUrl) + "&timeType=2&time=" + (18000 + parseInt(Math.random() * 500)) + "&_=" + parseInt(Math.random() * 500));
                    gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=4298&Pwd=686148428&uin=&vid=" + params.vid + "&coverid=" + params.cover + "&pid=" + params.pid + "&guid=" + params.guid + "&vt=" + params.vt + "&type=1036&url=" + encodeURIComponent(params.pageUrl) + "&bi=&bt=&version=" + params.appver + "&platform=" + params.platform + "&format=" + params.format + "&defn=" + params.fmt + "&ctime=" + encodeURIComponent(params.ctime) + "&ptag=&isvip=0&tpid=10&pversion=" + params.pversion + "&hc_uin=&hc_main_login=&hc_vuserid=&hc_openid=&hc_appid=&hc_pvid=" + params.pvid + "&hc_ssid=&hc_qq=&hh_ua=" + encodeURIComponent(navigator.userAgent) + "&ckey=" + gdjiexi.data.urls[0].key + "&iformat=" + params.format + "&hh_ref=" + encodeURIComponent(params.pageUrl) + "&v_idx=0&rcd_info=&vurl=" + encodeURIComponent(gdjiexi.data.vurls[0]["u"]) + "&step=6&val=" + (14000 + parseInt(Math.random() * 1000)) + "&val1=1&idx=0&diagonal=1273&isfocustab=1&isvisible=1");
                    gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=4501&Pwd=142347456&loginid=&loginex=&logintype=0&guid=" + params.guid + "&longitude=&latitude=&vip=0&online=1&p2p=0&downloadkit=0&resolution=1920*1080*1&testid=&osver=" + params.osname + "+" + params.osver + "&playerver=" + params.appver + "&playertype=1&uip=" + params.uip + "&confid=&cdnip=&cdnid=" + params.vt + "&cdnuip=&freetype=&sstrength=&network=&speed=&device=&appver=&p2pver=&url=" + encodeURIComponent(params.pageUrl) + "&refer=&ua=" + encodeURIComponent(navigator.userAgent) + "&ptag=&flowid=" + params.pid + "_" + params.platform + "&platform=" + params.platform + "&dltype=" + params.dltype + "&vid=" + params.vid + "&fmt=" + params.format + "&rate=" + params.br + "&clip=" + gdjiexi.data.urls.length + "&status=2&type=1036&duration=" + params.dur + "&data=%7B%22stime%22%3A" + (new Date().getTime() + parseInt(Math.random() * 1000)) + "%2C%22etime%22%3A" + (new Date().getTime() + 1000 + parseInt(Math.random() * 200)) + "%2C%22bufferduration%22%3A%22%22%2C%22vt%22%3A" + params.vt + "%2C%22url%22%3A%22" + encodeURIComponent(gdjiexi.data.vurls[0]["u"]) + "%22%2C%22urlindex%22%3A0%2C%22code%22%3A%22%22%7D&step=30&seq=3");
                    gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=3699&Pwd=1439649858&type=10&pageType=4&usertype=0&playertype=" + params.pversion + "&ua=" + encodeURIComponent(navigator.userAgent) + "&url=" + encodeURIComponent(params.pageUrl) + "&timeType=1&time=" + (3000 + parseInt(Math.random() * 500)) + "&_=" + parseInt(Math.random() * 500));
                    gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=4298&Pwd=686148428&uin=&vid=" + params.vid + "&coverid=" + params.cover + "&pid=" + params.pid + "&guid=" + params.guid + "&vt=" + params.vt + "&type=1036&url=" + encodeURIComponent(params.pageUrl) + "&bi=" + parseInt(params.ds.split("|")[0]) + "&bt=" + parseInt(params.dur) + "&version=" + params.appver + "&platform=" + params.platform + "&format=" + params.format + "&defn=" + params.fmt + "&ctime=" + encodeURIComponent(params.ctime) + "&ptag=&isvip=0&tpid=10&pversion=" + params.pversion + "&hc_uin=&hc_main_login=&hc_vuserid=&hc_openid=&hc_appid=&hc_pvid=" + params.pvid + "&hc_ssid=&hc_qq=&hh_ua=" + encodeURIComponent(navigator.userAgent) + "&ckey=" + gdjiexi.data.urls[0].key + "&iformat=" + params.format + "&hh_ref=" + encodeURIComponent(params.pageUrl) + "&v_idx=0&rcd_info=&vurl=" + encodeURIComponent(gdjiexi.data.vurls[0]["u"]) + "&step=35&val=" + (14000 + parseInt(Math.random() * 1000)) + "&val1=&val2=0");
                    gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=3699&Pwd=1439649858&type=10&pageType=4&usertype=0&playertype=" + params.pversion + "&ua=" + encodeURIComponent(navigator.userAgent) + "&url=" + encodeURIComponent(params.pageUrl) + "&timeType=3&time=" + (500 + parseInt(Math.random() * 500)) + "&_=" + parseInt(Math.random() * 500));
                    gdjiexi.data.reportUrls.push("//btrace.video.qq.com/kvcollect?BossId=4298&Pwd=686148428&uin=&vid=" + params.vid + "&coverid=" + params.cover + "&pid=" + params.pid + "&guid=" + params.guid + "&vt=" + params.vt + "&type=1036&url=" + encodeURIComponent(params.pageUrl) + "&bi=" + parseInt(params.ds.split("|")[0]) + "&bt=" + parseInt(params.dur) + "&version=" + params.appver + "&platform=" + params.platform + "&format=" + params.format + "&defn=" + params.fmt + "&ctime=" + encodeURIComponent(params.ctime) + "&ptag=&isvip=0&tpid=10&pversion=" + params.pversion + "&hc_uin=&hc_main_login=&hc_vuserid=&hc_openid=&hc_appid=&hc_pvid=" + params.pvid + "&hc_ssid=&hc_qq=&hh_ua=" + encodeURIComponent(navigator.userAgent) + "&ckey=" + gdjiexi.data.urls[0].key + "&iformat=" + params.format + "&hh_ref=" + encodeURIComponent(params.pageUrl) + "&v_idx=0&rcd_info=&vurl=" + encodeURIComponent(gdjiexi.data.vurls[0]["u"]) + "&step=30&val=" + (14000 + parseInt(Math.random() * 1000)) + "&val1=0&val2=1");
                    gdjiexi.qq.reportSuccess();
                    if (!params.ucookie) {
                        vParse_Report(1001, params.pwd, { guid: params.guid, pid: params.pid, vid: params.vid });
                    };

                };

            }
            ;
        }
    }
};
//[/Abc]


//解密的函数

function urlenc(a, b, d) {
    urlStr = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
    for (var e, f, g, h, i, j, k, l = "", m = 0; m < a.length;) e = a.charCodeAt(m++), f = a.charCodeAt(m++), g = a.charCodeAt(m++), 15 == m && (l += "A", l += b, l += d), h = e >> 2, i = (3 & e) << 4 | f >> 4, j = (15 & f) << 2 | g >> 6, k = 63 & g, isNaN(f) ? j = k = 64 : isNaN(g) && (k = 64), l = l + urlStr.charAt(h) + urlStr.charAt(i) + urlStr.charAt(j) + urlStr.charAt(k);
    return l
}


function hexToString(a) {
    for (var b = "", c = "0x" == a.substr(0, 2) ? 2 : 0; c < a.length; c += 2) b += String.fromCharCode(parseInt(a.substr(c, 2), 16));
    return b
}


function tempcalc(a, b) {
    for (var c = "", d = 0; d < a.length; d++) c += String.fromCharCode(a.charCodeAt(d) ^ b.charCodeAt(d % 4));
    return c
}

function u1(a, b) {
    for (var c = "", d = b; d < a.length; d += 2) c += a.charAt(d);
    return c
}
/**
 * 格式化json时间
 * @param strTime
 * @returns {*}
 * @constructor
 */
function FormatDate(strTime) {
    return new Date(strTime).Format("yyyy-MM-dd hh:mm:ss");
}
/**
 * 注入日期格式
 * @param fmt
 * @returns {*}
 * @constructor
 */
Date.prototype.Format = function (fmt) {
    var o = {
        "M+": this.getMonth() + 1, //月份
        "d+": this.getDate(), //日
        "h+": this.getHours(), //小时
        "m+": this.getMinutes(), //分
        "s+": this.getSeconds(), //秒
        "q+": Math.floor((this.getMonth() + 3) / 3), //季度
        "S": this.getMilliseconds()
        //毫秒
    };
    if (/(y+)/.test(fmt))
        fmt = fmt.replace(RegExp.$1, (this.getFullYear() + "")
            .substr(4 - RegExp.$1.length));
    for (var k in o)
        if (new RegExp("(" + k + ")").test(fmt))
            fmt = fmt.replace(RegExp.$1, (RegExp.$1.length == 1) ? (o[k])
                : (("00" + o[k]).substr(("" + o[k]).length)));
    return fmt;
}
function getvid(URl) {
    var cont = getUrlContent(URl);
    if (cont.indexOf("style/404.css") > 0) {
        return "";
    }
    var vid = cont.substring(cont.indexOf("\"vid\":\"") + 7);
    vid = vid.substring(0, vid.indexOf("\""));
    if (vid == null || vid == '') {
        return "";
    }

    return vid;
}

function getUuid() {
    var len = 32;//32长度
    var radix = 16;//16进制
    var chars = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'.split(''); var uuid = [], i; radix = radix || chars.length; if (len) { for (i = 0; i < len; i++)uuid[i] = chars[0 | Math.random() * radix]; } else { var r; uuid[8] = uuid[13] = uuid[18] = uuid[23] = '-'; uuid[14] = '4'; for (i = 0; i < 36; i++) { if (!uuid[i]) { r = 0 | Math.random() * 16; uuid[i] = chars[(i == 19) ? (r & 0x3) | 0x8 : r]; } } }
    return uuid.join('');
}

function decode(plt, vid, std, sts, rnd) {
    return hexToString(getMD5(plt + vid + rnd + Seed + 1 + std));
}


// https://v.qq.com/x/cover/h188mdjtrh3up22.html
function QqVodParsePlay(origUrl) {
    function _utf8_encode(string) {
        string = string.replace(/\r\n/g, "\n");
        var utftext = "";
        for (var n = 0; n < string.length; n++) {
            var c = string.charCodeAt(n);
            if (c < 128) {
                utftext += String.fromCharCode(c);
            } else if ((c > 127) && (c < 2048)) {
                utftext += String.fromCharCode((c >> 6) | 192);
                utftext += String.fromCharCode((c & 63) | 128);
            } else {
                utftext += String.fromCharCode((c >> 12) | 224);
                utftext += String.fromCharCode(((c >> 6) & 63) | 128);
                utftext += String.fromCharCode((c & 63) | 128);
            }

        }
        return utftext;
    }
    function encode(input) {
        var _keyStr = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
        var output = "";
        var chr1, chr2, chr3, enc1, enc2, enc3, enc4;
        var i = 0;
        input = _utf8_encode(input);
        while (i < input.length) {
            chr1 = input.charCodeAt(i++);
            chr2 = input.charCodeAt(i++);
            chr3 = input.charCodeAt(i++);
            enc1 = chr1 >> 2;
            enc2 = ((chr1 & 3) << 4) | (chr2 >> 4);
            enc3 = ((chr2 & 15) << 2) | (chr3 >> 6);
            enc4 = chr3 & 63;
            if (isNaN(chr2)) {
                enc3 = enc4 = 64;
            } else if (isNaN(chr3)) {
                enc4 = 64;
            }
            output = output +
                _keyStr.charAt(enc1) + _keyStr.charAt(enc2) +
                _keyStr.charAt(enc3) + _keyStr.charAt(enc4);
        }
        return output;
    }
    function transformRequest(data) {
        var ret = '';
        for (var it in data) {
            ret += encodeURIComponent(it) + '=' + encodeURIComponent(data[it]) + '&';
        }
        ret = ret.substring(0,ret.lastIndexOf('&'));
        return ret;
    }
    // 原生取流：复用本文件已有的 getQqUrl / getCkey（腾讯视频官方 App 同款
    // vd.l.qq.com/proxyhttp 接口，单次请求，不依赖任何第三方解析源）。
    // 旧 okjx.cc:666 第三方转发已死，故切回官方原生链路（符合"只模拟应用获取"红线）。
    var vid = getvid(origUrl);
    var urlArray = new Array();
    if (vid == "" || vid == null) {
        urlArray.push("error://qq_404");
        return urlArray;
    }
    var guid = getUuid();
    var hdUrl = getQqUrl(origUrl, vid, guid, "hd");
    var sdUrl = getQqUrl(origUrl, vid, guid, "sd");
    if (hdUrl != "" && hdUrl.indexOf("error://") < 0) {
        urlArray.push(hdUrl + "#高清");
    }
    if (sdUrl != "" && sdUrl.indexOf("error://") < 0 && sdUrl != hdUrl) {
        urlArray.push(sdUrl + "#标清");
    }
    if (urlArray.length == 0) {
        urlArray.push("error://qq_empty");
    }
    return urlArray;
}

function parseZzvod(origUrl) {
    var zvid = origUrl.replace("zzvod://", "");
    var ts = parseInt(Date.now() / 1000);
    var apiUrl = "https://txservice.imilive.cn/api/feed/videos/aggregate?cc=TG45620&smid=&conn=wifi&ast=1&icc=&cpu=&imsi=&ua=XiaomiMIX2&deviceId=2018091812114010b0717ae8f9fc5f597c62529c216ea701154c670d8c3ad7&sid=&uid=0&mtid=e40fd65efe36c88a56403e136ba092f1&cv=GA1.1.50_Android&lc=3d53785b7280318f&imei=&devi=8471086a&mtxid=bc4699bac493&osversion=android_26&aid=6cf9fb4bd88aba9a&ram=0&video_ids=" + zvid + "&ts=" + ts + "&rc=415228&sg=d8997addcd1042b0c4b3446db6a5d722";
    var source = urlRequest(apiUrl);
    var data = JSON.parse(source);
    var videos = data.data.videos
    var urlArray = [];
    if (videos.length > 0) {
        var video = videos[0].video;
        if (video.source_type < 2) {
            for (i = 0; i < video.play_info.play_list.length; i++) {
                var info = video.play_info.play_list[i];
                var playUrl = base64decode(info.play_url.main_url.url);
                urlArray.push(playUrl + "#" + info.definition);
            }
        }
        else if (video.source_type == 2) {
            var qqUrl = "https://v.qq.com/x/page/" + video.source_vid + ".html"
            return QqVodParsePlay(qqUrl);
        }
    }
    return urlArray;
}

function getQqUrl(origUrl, vid, guid, bt) {
    //var guid = getUuid();
    var tm = (Date.parse(new Date())) / 1000;
    var cKey = getCkey("10901", vid, "v1010", 1, tm, "7.3");
    var c = "charge=0&guid=" + guid + "&defaultfmt=auto&otype=ojson&platform=10901&sdtfrom=v1010&defnpayver=1&appVer=3.4.31&host=v.qq.com&refer=" + origUrl + "&ehost=" + origUrl + "&sphttps=1&tm=" + tm + "&encryptVer=7.3&cKey=" + cKey + "&spwm=4&vid=" + vid + "&defn=" + bt + "&fhdswitch=1&show1080p=1&isHLS=1&onlyGetinfo=true&dtype=3&sphls=1&defsrc=";
    var param = encodeURIComponent(c);
    var apiUrl = "https://vd.l.qq.com/proxyhttp?callback=&buid=onlyvinfo&vinfoparam=" + param + "&_=" + tm + "123";
    var pcua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.115 Safari/537.36";
    var apiCont = urlRequestWithPara(apiUrl, pcua, "User-Agent");
    playUrl = "";
    console.log(apiCont);
    if (apiCont.length > 100) {
        jsonObject = eval('(' + apiCont + ')');
        var vinfo = jsonObject.vinfo;
        jsonObject1 = eval('(' + vinfo + ')');
        // 【2026-10-26 加固】腾讯返回 errCode=0 但视频不可用（如 code=62.6 区域/下架限制）时，
        //   vinfo 里没有 vl.vi 结构；直接访问会抛 TypeError 冒泡成 __JSERR__。
        //   这里先判空，缺失则干净返回空（上层判该线路不可达，换下一个视频/线路即可）。
        if (!jsonObject1 || !jsonObject1.vl || !jsonObject1.vl.vi || !jsonObject1.vl.vi[0]) {
            console.log("qq getQqUrl: 视频不可用/无 vl 结构（可能下架或区域限制）");
            return "";
        }
        var urls = jsonObject1.vl.vi[0].ul.ui;
        var bip = urls[0].url;
        var playUrl = "";
        var hls = urls[0].hls;
        if (typeof hls == 'undefined') {
            var mp4Name = jsonObject1.vl.vi[0].fn;
            mp4Name = mp4Name.replace(".mp4", ".1.mp4");
            var vkey = jsonObject1.vl.vi[0].fvkey;
            playUrl = bip + mp4Name + "?sdtfrom=v1010&guid=" + guid + "&vkey=" + vkey;
        } else {
            var name = urls[0].hls.pt;
            playUrl = bip + name;
        }
        playUrl = playUrl.replace("https://", "http://");
    }
    return playUrl;
}

function getCkey(a, b, d, e, f, g) {
    if (magic = "123456", g.length < 3) return "err";
    if ("7." != g.substr(0, 2)) return "err";
    subver = g.substr(2), "1" == subver && (magic = "06fc1464"), "2" == subver && (magic = "4244ce1b"), "3" == subver && (magic = "77de31c5"), "4" == subver && (magic = "e0149fa2"), "5" == subver && (magic = "60394ced"), "6" == subver && (magic = "2da639f0"), "7" == subver && (magic = "c2f0cf9f");
    var f = f || parseInt(+new Date / 1e3),
        e = ("" + e).charAt(0),
        h = getMD5(magic + b + f + "*#06#" + a);
    return h
}

function base64decode(str) {
    var base64EncodeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    var base64DecodeChars = new Array(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1);
    var c1, c2, c3, c4;
    var i, len, out;
    len = str.length;
    i = 0;
    out = "";
    while (i < len) {
        /* c1 */
        do {
            c1 = base64DecodeChars[str.charCodeAt(i++) & 0xff];
        }
        while (i < len && c1 == -1);
        if (c1 == -1)
            break;
        /* c2 */
        do {
            c2 = base64DecodeChars[str.charCodeAt(i++) & 0xff];
        }
        while (i < len && c2 == -1);
        if (c2 == -1)
            break;
        out += String.fromCharCode((c1 << 2) | ((c2 & 0x30) >> 4));
        /* c3 */
        do {
            c3 = str.charCodeAt(i++) & 0xff;
            if (c3 == 61)
                return out;
            c3 = base64DecodeChars[c3];
        }
        while (i < len && c3 == -1);
        if (c3 == -1)
            break;
        out += String.fromCharCode(((c2 & 0XF) << 4) | ((c3 & 0x3C) >> 2));
        /* c4 */
        do {
            c4 = str.charCodeAt(i++) & 0xff;
            if (c4 == 61)
                return out;
            c4 = base64DecodeChars[c4];
        }
        while (i < len && c4 == -1);
        if (c4 == -1)
            break;
        out += String.fromCharCode(((c3 & 0x03) << 6) | c4);
    }
    return out;
}
/*function getvid(URl) {
    var cont = getUrlContent(URl);
    var vid = cont.substring(cont.indexOf("\"vid\":\"") + 7);
    vid = vid.substring(0, vid.indexOf("\""));
    if (vid == null || vid == '') {
        return new Array();
    }

    return vid;
}

function getUuid(){
    var len=32;//32长度
    var radix=16;//16进制
    var chars='0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'.split('');var uuid=[],i;radix=radix||chars.length;if(len){for(i=0;i<len;i++)uuid[i]=chars[0|Math.random()*radix];}else{var r;uuid[8]=uuid[13]=uuid[18]=uuid[23]='-';uuid[14]='4';for(i=0;i<36;i++){if(!uuid[i]){r=0|Math.random()*16;uuid[i]=chars[(i==19)?(r&0x3)|0x8:r];}}}
    return uuid.join('');
}*/