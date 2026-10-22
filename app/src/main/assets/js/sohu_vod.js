/**
 * @brief 搜狐播放地址解析
 * @author gxl<gxl@yuntutv.net>
 * @date 2016-1-7
 * @version 1.0.0.0
 */

//解析点播地址 http://tv.sohu.com/20170222/n481465251.shtml
function SohuVodParsePlay(origUrl) {

    var uid = createUUID();
	var muid = createUUID();
    if (-1 != origUrl.indexOf('m.tv.sohu.com/u/vw/')) {
        var vid = origUrl.substring(origUrl.lastIndexOf("m.tv.sohu.com/u/vw/") + 19);
        vid = vid.substring(0, vid.lastIndexOf(".shtml"));
        if (vid.length <= 0) {
            return new Array();
        }
        lurl = 'http://m.tv.sohu.com/phone_playinfo?vid=' + vid + '&site=2&appid=tv&api_key=f351515304020cad28c92f70f002261c&plat=17&sver=1.0&partner=1&uid=' + uid+ '&muid=' +muid + '&_c=1&pt=5&qd=680&src=11060001&_=' + new Date().getTime();
    } else if (-1 != origUrl.indexOf('m.tv.sohu.com/v')) {
        var vid = origUrl.substring(origUrl.lastIndexOf("m.tv.sohu.com/v") + 15);
        vid = vid.substring(0, vid.lastIndexOf(".shtml"));
        if (vid.length <= 0) {
            return new Array();
        }
        lurl = 'http://m.tv.sohu.com/phone_playinfo?vid=' + vid + '&site=1&appid=tv&api_key=f351515304020cad28c92f70f002261c&plat=17&sver=1.0&partner=1&uid=' + uid + '&muid=' + muid + '&_c=1&pt=5&qd=680&src=11060001&_=' + new Date().getTime();
    } else if (-1 != origUrl.indexOf('my.tv.sohu.com/')) {
        var urlcontent = sohuurlRequest(origUrl);
        var vid = urlcontent.substring(urlcontent.lastIndexOf("vid = '") + 7);
        vid = vid.substring(0, vid.indexOf("'"));
        if (vid.length <= 0) {
            return new Array();
        }
        lurl = 'http://m.tv.sohu.com/phone_playinfo?vid=' + vid + '&site=2&appid=tv&api_key=f351515304020cad28c92f70f002261c&plat=17&sver=1.0&partner=1&uid=' + uid + '&muid=' + muid + '&_c=1&pt=5&qd=680&src=11060001&_=' + new Date().getTime();
    } else {
        var urlcontent = sohuurlRequest(origUrl);
        var vid = urlcontent.substring(urlcontent.lastIndexOf('vid="') + 5);
        vid = vid.substring(0, vid.indexOf('"'));
        if (vid.length <= 0) {
            return new Array();
        }
        lurl = 'http://m.tv.sohu.com/phone_playinfo?vid=' + vid + '&site=1&appid=tv&api_key=f351515304020cad28c92f70f002261c&plat=17&sver=1.0&partner=1&uid=' +uid + '&muid=' + muid + '&_c=1&pt=5&qd=680&src=11060001&_=' + new Date().getTime();
    }
    content = sohuurlRequest(lurl);
    if (content.length <= 0) {
        return new Array();
    }
    jsonObject = eval('(' + content + ')');
    urlArray = new Array();
	/*urlhig = jsonObject.data.urls.m3u8.hig;
    if (urlhig.length > 0) {
        urlArray.push(urlhig + "#高清");
    }
    urlnor = jsonObject.data.urls.m3u8.nor;
    if (urlnor.length > 0) {
        lennor = jsonObject.data.durations.nor;
        urlArray.push(urlnor + "#流畅");
    }
    urlsup = jsonObject.data.urls.m3u8.sup;
    if (urlsup.length > 0) {
        urlArray.push(urlsup + "#超清");
    }*/
	urlhig = jsonObject.data.urls.mp4.hig;
    if (urlhig.length > 0) {
        lenhig = jsonObject.data.durations.hig;
        urlArray.push(getSohuVodUrl(urlhig, lenhig, "sd") + "#高清");
    }
    urlnor = jsonObject.data.urls.mp4.nor;
    if (urlnor.length > 0) {
        lennor = jsonObject.data.durations.nor;
        urlArray.push(getSohuVodUrl(urlnor, lennor, "ld") + "#流畅");
    }
    urlsup = jsonObject.data.urls.mp4.sup;
    if (urlsup.length > 0) {
        lensup = jsonObject.data.durations.sup;
        urlArray.push(getSohuVodUrl(urlsup, lensup, "hd") + "#超清");
    }
    var original_video_url = jsonObject.data.original_video_url;
    var cate_code = jsonObject.data.cate_code;
    var pid = jsonObject.data.aid;
    var tvid = jsonObject.data.tv_id;
    var url_html5 = jsonObject.data.url_html5
    var nid = original_video_url.substring(original_video_url.indexOf("/n") + 2);
    nid = nid.substring(0, nid.indexOf(".shtml"));
	var refer = encodeURIComponent(original_video_url);
	refer = encodeURIComponent(refer);
	var curUrl = encodeURIComponent(url_html5);
	curUrl = encodeURIComponent(curUrl);
	var t = new Date().getTime();
	urlRequest('http://z.m.tv.sohu.com/h5_cc.gif?t=' + parseInt(new Date().getTime()) + '&uid=' + uid + '&position=page_adbanner&op=click&details=%7B%7D&nid=&url=http%253A%252F%252Fm.tv.sohu.com%252Fus%252F209059447%252F59038490.shtml&refer=http%253A%252F%252Fmy.tv.sohu.com%252Fus%252F209059447%252F59038490.shtml&screen=1366x768&os=ios&platform=ipad&passport=&vid=59038490&pid=6960285&channeled=1211020000&MTV_SRC=11060001');
	urlRequest('http://z.m.tv.sohu.com/h5_cc.gif?t=' + parseInt(new Date().getTime()) + '&uid=' + uid + '&position=play_verify&op=click&details=%7B%7D&nid=&url=http%253A%252F%252Fm.tv.sohu.com%252Fus%252F209059447%252F59038490.shtml&refer=http%253A%252F%252Fmy.tv.sohu.com%252Fus%252F209059447%252F59038490.shtml&screen=768x1024&os=ios&platform=ipad&passport=&vid=59038490&pid=6960285&channeled=1211020000&MTV_SRC=11070001');
	urlRequest('http://z.m.tv.sohu.com/h5_cc.gif?t=' + parseInt(new Date().getTime()) + '&uid=' + uid + '&position=page_adbanner_success&op=click&details=%7B%7D&nid=&url=http%253A%252F%252Fm.tv.sohu.com%252Fus%252F209059447%252F59038490.shtml&refer=http%253A%252F%252Fmy.tv.sohu.com%252Fus%252F209059447%252F59038490.shtml&screen=1366x768&os=ios&platform=ipad&passport=&vid=59038490&pid=6960285&channeled=1211020000&MTV_SRC=11060001');
	
    return urlArray;
}

function getSohuVodUrl(urlarr, lenarr, filename) {
    fileContent = 'ffconcat version 1.0 \n';
    var size = urlarr.length;
    for (var i = 0; i < size; i++) {
        fileContent += 'file ' + urlarr[i] + '\n';
        fileContent += 'duration ' + lenarr[i] + '\n';
    }
    console.log("fileContent=======>" + fileContent);
    fileName = filename + '.lbk';
    filePath = writeFileWithName(fileContent, fileName);
    return filePath;
}

function sohuurlRequest(urlString) {
    var dic1 = {
        "value": "Mozilla/5.0 (Linux; Android 4.4.4; Nexus 5 Build/KTU84P) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/38.0.2125.114 Mobile Safari/537.36",
        "header": "User-Agent"
    };
    var dic2 = {"value": "1", "header": "Upgrade-Insecure-Requests"};

    var arr = new Array();
    arr.push(dic1);
    arr.push(dic2);

    return urlRequestWithParaAndCoding(urlString, arr, 2147485234);
}

function createUUID() {
    var screenWidth = 1366;
    var screenHeight = 1024;
    var t = Math.floor(screenWidth), e = Math.floor(screenHeight),
        i = Math.floor(Math.sqrt(t * t + e * e)) || 0, n = Math.round(5) || 1, a = 1e3 * +new Date().getTime(),
        s = a + n + i + Math.round(1e3 * Math.random());
    return s
}


