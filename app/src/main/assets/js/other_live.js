//http://xproxy.yuntuds.com/b2/live.php?src=ccali&id=cctv1&pwd=yuntutv
function yuntuLiveParsePlay(originUrl){
	if(originUrl.indexOf("&nwtime")!=-1){
		originArr = originUrl.split("&nwtime");
		originUrl = originArr[0];
	}
	var header = "";
	if(originUrl.indexOf("@@")!=-1){
		header = getStringBtween(originUrl,"@@","@@");
		header = "@@"+header+"@@";
		originUrl = originUrl.replace(header,"");
	}
	vid = originUrl.replace('&pwd=yuntutv', '');
	var nwtime = (Date.parse(new Date()))/1000;
    var skey = "dawowp[awf[pawkr[japrek[kai[]]]]]" + nwtime + "dhjaljfakj[akjfahrpakjrfo@#]";
	var sign = getMD5(skey);
	playUrl = vid+"&nwtime="+nwtime+"&sign="+sign+"&encypt=2";
    urlarr = new Array();
	if(typeof client!= 'undefined'){
		urlarr.push(playUrl+header+"#官方线路");
	}else{
		urlarr.push(playUrl+header);
	}
    return urlarr;
}

function rlog(msg) {
	if(true) {
		return;
	}
	var api = 'http://42.121.111.105:3553/monitor/client_rlog.php?msg=' + encodeURIComponent(msg);
	urlRequest(api);
}
function _getClientVersion() {
	if(typeof getVersion == 'function') {
		return getVersion();
	}
	return '';
}
function _parseVersion(v) {
	if (!String.prototype.endsWith) {
		String.prototype.endsWith = function(search, this_len) {
			if (this_len === undefined || this_len > this.length) {
				this_len = this.length;
			}
			return this.substring(this_len - search.length, this_len) === search;
		};
	}
	if (v.endsWith('m')) {
		v = v.substring(0, v.length - 1);
	}
	var va = v.split('.');
	var r = [];
	for(var i = 0; i < va.length; i++) {
		r.push(parseInt(va[i],10));
	}
	return r;
}

/* 比较当前客户端版本是否大于等于传入的版本
 * than: [4, 3, 1] 表示 4.3.1 版本
*/
function _versionGE(than) {
	var curVersion = _getClientVersion();
	if(!curVersion) {
		return false;
	}
	var curVersionA = _parseVersion(curVersion);
	if(curVersionA.length != than.length) {
		return false;
	}
	for(var i = 0; i < curVersionA.length; i++) {
		if(than[i] < curVersionA[i]) {
			return false;
		}
	}
	return true;
}

//http://xproxy.yuntuds.com/lk/bstjx.m3u8?id=cctv2&miguid=631780532&epgid=cctv2&bakurl=bstxj,bsthlj&?start=1531353600&end=1531357200
// http://xproxy.yuntuds.com/lk/bstxj.m3u8?id=cctv13&p2p03=75&miguid=608807423&epgid=cctv13&bakurl=bstjx,bsthlj,sziptv&webLbk&start=1604079360&end=1604080800
function yuntuLbkParsePlay(originUrl){
	/**
	 * TODO..
	 * 1.get服务接口是当天是否存在回看缓存
	 * true retrun 拿接口数据 false 请求客户端接口
	 */
  if (originUrl.indexOf('id=zjws&epgid=zjws') !== -1) {
    return zjwsLbkParser(originUrl);
  }
  if (originUrl.indexOf('tv=jsgw') !== -1) { // 江苏及地方台回看（来源：江苏直播官网）
		return jsgwPlayerd(originUrl)
	}
  if (originUrl.indexOf('muguv4') !== -1) { // miguhd
		return miguhdPlayerd(originUrl)
	}
  if (originUrl.indexOf('zjgw') !== -1) { // 浙江卫视回看
		return zjgwPlayerd(originUrl)
	}
  if (originUrl.indexOf('sdgw') !== -1) { // 山东卫视回看
		return sdgwPlayerd(originUrl)
	}
	if (originUrl.indexOf('zglj') !== -1) {
		return zgljPlayerd(originUrl)
	}
  if (originUrl.indexOf('zgyj') !== -1) {
		return zgyjPlayerd(originUrl)
	}
  if (originUrl.indexOf('trlb') !== -1) {
		return trlbPlayerd(originUrl)
	}
	if (originUrl.indexOf('muguv3') !== -1) {
		return muGuV3Playerd(originUrl)
	}
  if (originUrl.indexOf('yngw') !== -1) {
		return yngwLBParser(originUrl)
	}
	originUrl = originUrl+"&";
	var id = getStringBtween(originUrl,"?id=","&");
	var st = _getStringBtween(originUrl,"?start=","&");
	var et = _getStringBtween(originUrl,"&end=","&");
	var epgid = getStringBtween(originUrl,"&epgid=","&");
	var firstSrc = "";
	if(originUrl.indexOf(".m3u8")!=-1){
		firstSrc = getStringBtween(originUrl,"/lk/",".m3u8");
	}else{
		firstSrc = getStringBtween(originUrl,"/lk/",".php");
	}
	var urlarr = new Array();
	var miguUrl = "";
	if(originUrl.indexOf("miguid=")!=-1){
		var miguid = getStringBtween(originUrl,"miguid=","&");
		var livearr = miguLiveParsePlay(miguid);
		if(livearr && livearr.length) {
			var livearr1 = livearr[0].split("#");
			var liveurl = livearr1[0];
			var start = formatDateTime(st);
			var end = formatDateTime(et - 60);
			miguUrl = liveurl +"&playbackbegin="+start+"&playbackend="+end;
		}else {
			log('migu parse error! miguid:' + miguid);
		}
	}
	if(miguUrl!=""){
		urlarr.push(miguUrl+"#移动线路");
	}
    return urlarr;
}

function formatDateTime(timeStamp) {
    var date = new Date();
    date.setTime(timeStamp * 1000);
    var y = date.getFullYear();
    var m = date.getMonth() + 1;
    m = m < 10 ? ('0' + m) : m;
    var d = date.getDate();
    d = d < 10 ? ('0' + d) : d;
    var h = date.getHours();
    h = h < 10 ? ('0' + h) : h;
    var minute = date.getMinutes();
    var second = date.getSeconds();
    minute = minute < 10 ? ('0' + minute) : minute;
    second = second < 10 ? ('0' + second) : second;
    return [y, m, d, h, minute, second].join('');
}

function formatIptv345backDateTime(timeStamp) {
	var date = new Date();
	date.setTime(timeStamp * 1000);
	var y = date.getFullYear();
	var m = date.getMonth() + 1;
	m = m < 10 ? ('0' + m) : m;
	var d = date.getDate();
	d = d < 10 ? ('0' + d) : d;
	var h = date.getHours();
	h = h < 10 ? ('0' + h) : h;
	var minute = date.getMinutes();
	minute = minute < 10 ? ('0' + minute) : minute;
	return ""+y + m + d +h+minute;
}
// 201810101411
function formatCctvbackDateTime(timeStamp) {
    var date = new Date();
    date.setTime(timeStamp * 1000);
    var y = date.getFullYear();
    var m = date.getMonth() + 1;
    m = m < 10 ? ('0' + m) : m;
    var d = date.getDate();
    d = d < 10 ? ('0' + d) : d;
    var h = date.getHours();
    h = h < 10 ? ('0' + h) : h;
    var minute = date.getMinutes();
    minute = minute < 10 ? ('0' + minute) : minute;
    return ""+y + m + d +h+minute;
}

//http://v.qq.com/x/live/tv.html?type=1&url=ccali.php?id=cctv1
//http://v.qq.com/x/live/tv.html?type=2&url=wasu1://http://livehls1-al.wasu.cn/cctv1/z.m3u8
function yuntuZdyParsePlay(originUrl){
	var type = getStringBtween(originUrl,"type=","&");
	if(type=="1"){
		var playUrl = originUrl.split("url=")[1];
		playUrl = "http://xproxy.yuntuds.com/b2/" + playUrl + "&pwd=yuntutv";
	}else{
		var playUrl = originUrl.split("url=")[1];
		if ( - 1 != playUrl.indexOf('migu://')|| - 1 != playUrl.indexOf('migu1://')) {
			return miguLiveParsePlay(playUrl);
		} else if ( - 1 != playUrl.indexOf('hntv://')|| - 1 != playUrl.indexOf('mgpchtml://')) {
			return hntvLiveParsePlay(playUrl);
		}
	}
    urlarr = new Array();
	if(typeof client!= 'undefined'){
		urlarr.push(playUrl+"#官方线路");
	}else{
		urlarr.push(playUrl);
	}
    return urlarr;
}

// 湖北官方解析
// http://news.hbtv.com.cn/app/tv/35  官网地址
// hbgf://hubeitv?stream_id=431&site_id=10008& 请求url
function hbgfParse(originUrl) {
	var stream_id = getStringBtween(originUrl,"?stream_id=","&");
	var site_id = getStringBtween(originUrl,"&site_id=","&");
	var Referer = 'http://app.cjyun.org/video/videojs/index?site_id='+site_id+'&live_type=1&id='+stream_id+'&thumb=&next=&autostart=&type=&sid='+site_id+'&customid=15&channel=';
	var url = 'http://app.cjyun.org/video/player/stream?stream_id='+ stream_id +'&site_id='+site_id;
	var header = [
		{
			header: 'User-Agent',
			value: 'Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1'
		},
		{header:"Referer",value:Referer}
	];
	var jsonPlayContent = urlRequestWithParaAndCoding(url, header, 'utf-8');
	var u = JSON.parse(jsonPlayContent).url;
	// var a = u+"@@Referer=http://app.cjyun.org/@@";
	var header1 = [
    {header:"Referer",value:'https://news.hbtv.com.cn/app/tv/' + stream_id},
    {header:"User-Agent",value:"Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X)"}
  ]
  var req_url = 'https://news.hbtv.com.cn/ajax/get_cdn_leech?url=' + u + '&client-id=9332ff6c-e29e-4661-b396-1a7db72d49f3'
  var channelData = urlRequestWithParaAndCoding(req_url, header1, 'utf-8');
  var stream_url = JSON.parse(channelData).data + "@@Referer=https://news.hbtv.com.cn/@User-Agent=Mozilla/5.0@@"
	return [stream_url];
}

//整个m3u8链接放入
//http://xproxy.yuntuds.com/lk/xjyx.m3u8?&zglj=http://111.26.225.22/TVOD/2/224/3221225547/index.m3u8&?start=1625807036&end=1625809436
function zgljPlayerd(originUrl){
	var originUrl = originUrl +"&";
	var st = formatDateTime(getStringBtween(originUrl,"?start=","&"));//开始时间
	var et = formatDateTime(getStringBtween(originUrl,"&end=","&"));//结束时间
	var url1 = getStringBtween(originUrl,"&zglj=","&");
	var url2 = "?playseek=";
	var url = url1+url2+st+'-'+et;
	return [url];
}
function trlbPlayerd(originUrl){
    var originUrl = originUrl +"&";
    var st = Number(getStringBtween(originUrl,"?start=","&")+'000');//开始时间
	var et = Number(getStringBtween(originUrl,"&end=","&")+'000');//结束时间
    var urls = getStringBtween(originUrl,"&trlb=","&");
    var url = urls + st + ',' + (et-st) + '.m3u8';
    return [url]
}

// 私有的用于处理 ios 之前版本回看start end 截取问题
function _getStringBtween(str,str1,str2){
	var a = str.substring(str.lastIndexOf(str1)+str1.length);
	a = a.substring(0, a.indexOf(str2));
	return a;
}

// 浙江卫视回看
function zjwsLbkParser(originUrl) {
    originUrl += '&';
    var st = _getStringBtween(originUrl,"?start=","&") + '000';
    var et = _getStringBtween(originUrl,"&end=","&") + '000';
    var q = st + ',' + et;
    var src = 'http://ali-xwl.cztv.com/live/channel011080Plxw.m3u8/' + q;
    return [src];
}

// 咪咕直播网页 https://m.miguvideo.com/mgs/msite/prd/liveDetail.html
// migu://608807420
function miguLiveParsePlay(originUrl){
	var pID = originUrl.replace('migu://', '');
  var clientId = getClientId();
	// 这个服务器安卓客户端校验证书会报错
	var protocol = (typeof client!= 'undefined') ? 'http://': 'https://';
	// var apiUrl = protocol + 'webapi.miguvideo.com/gateway/playurl/v3/play/playurl?contId=' + pID + '&rateType=3&clientId=' + clientId + '&startPlay=true&channelId=0132_10010001005';
	var apiUrl = protocol + 'webapi.miguvideo.com/gateway/playurl/v2/play/playurlh5?contId=' + pID + '&rateType=3&clientId=' + clientId + '&startPlay=true&devId=cDNwMnpudHNndXVvNGlvOAyCS8SLcQKSZHaJi6KD45AIv3683LG1xeBYSNyhc9dD&xh265=false&channelId=0131_10010001005';
  var headers = [];
  // headers.push({ header: "x-up-client-channel-id", value: "0132_10010001005" });
  // headers.push({ header: "Referer", value: "https://www.miguvideo.com/" });
  // headers.push({ header: "appCode", value: "miguvideo_default_h5" }); // v2新增
  var apiCont = urlRequestWithParaAndCoding(apiUrl, headers, "utf-8");
  var list = JSON.parse(apiCont);
  var playUrl = encryption(list.body.urlInfo.url);
	var result = urlRequest(playUrl).replace('\n', '');
  return [result];
}
function getClientId() {
  for (var t = "", r = "0123456789abcdef", n = 0; n < 32; n++) {
    t += r.charAt(Math.random() * (r.length - 1) >> 0);
  }
  return t
}
// 获取migu ddCalcu重定向加密字段
function encryption (curHref) {
  var puData = getQueryString('puData', curHref);
  var ProgramID = getQueryString('ProgramID', curHref);
  var Program = 'cccccccccc'.split('');
  var arr_key = [];
  var s = puData.length;
  for (var v = 0; v < s / 2; v++) {
    arr_key.push(puData[s - v - 1]);
    arr_key.push(puData[v]);
    switch (v) {
      case 1:
        arr_key.push(arrkey(v));
        break;
      case 2:
        arr_key.push(arrkey(v));
        break;
      case 3:
        var chars = Program[ProgramID[1]];
        arr_key.push(chars);
        break;
      case 4:
        arr_key.push(arrkey(v));
        break;
    }
  }
  var ddCalcu = arr_key.join('');
  var p = curHref + '&ddCalcu=' + ddCalcu + '_s002&sv=10013&crossdomain=www';
  return p
}
function arrkey(v) {
  var put = ['a', 'b', '0', 'a']
  var mark = put[v - 1];
  return mark;
}
// 匹配migu视频链接中某个字段
function getQueryString(name,src) {
    var n,o = new RegExp("(^|\\?|&|#)" + name + "=([^&#]*)(&|#|$)", "i");
    if (src) {
        n = src.match(o);
    } else {
        try {
            n = top.location.href.match(o)
        } catch (err) {
            n = window.location.href.match(o)
        }
    }
    return null != n ? decodeURIComponent(n[2]) : null
}

// https://tv.gztv.com/tvLive
// gztv://zhpd 广州地方台
function gztvParser(sourceUrl) {
  var id = sourceUrl.split('://')[1];
  var map = {
      'zhpd': '综合频道',
      'xwpd': '新闻频道',
      'fzpd': '法治频道',
      'jspd': '竞赛频道',
      'yspd': '影视频道',
      '4k':'4K南国都市频道'
  };
  var name = map[id];
  var url = 'https://gzbn.gztv.com:7443/plus-cloud-manage-app/liveChannel/queryLiveChannelList?type=1'
  var r = urlRequest(url);
  var info = JSON.parse(r)['data']
  for(var i in info){
    if(info[i].name === name){
      return [info[i].httpUrl]
    }
  }
	return []
}

// 东莞 tv http://dgrtv.sun0769.com/index.php/online2/4
// dgtv://ggpd
// xwzh 东莞新闻综合频道 ggpd 东莞公共频道
function dgtvParser(sourceUrl) {
    var index = sourceUrl.split('://')[1];
    var table = {
        'ggpd': '5',
        'xwzh': '4'
    };
    var id = table[index];
    var api = 'http://dgrtv.sun0769.com/index.php/online2/' + id;
    var html = urlRequest(api);
    var str = /var\scurr_stream_hd\s=\s".*"/.exec(html)[0];
    var m3u8 = 'http:'+str.trim().split(' = "')[1].split('"')[0];
    var result = [];
    result.push(m3u8);
    return result;
}

//芒果TV官网http://live.mgtv.com/
//hntv://280
function hntvLiveParsePlay(originUrl){
    vid = originUrl.replace('hntv://', '');
	vid = vid.replace('mgpchtml://', '');
    var url = "https://mpplive.api.mgtv.com/v1/epg/turnplay/getLivePlayUrlMPP?version=PCweb_1.0&platform=1&buss_id=2000001&channel_id="+vid+"&definition=std&_support=10000000";
    htmlContent = getUrlContent(url);
    if (htmlContent.length <= 0){
        return new Array();
    }
    jsonContent = eval('(' + htmlContent + ')');
    playUrl = jsonContent.data.url;
    urlarr = new Array();
	if(typeof client!= 'undefined'){
		urlarr.push(playUrl+"#湖南官网");
	}else{
		urlarr.push(playUrl);
	}
    return urlarr;
}

function createRand(len){
	var arr = ["0","1","2","3","4","5","6","7","8","9","A","B","C","D","E","F"];
	var a = "";
	for(var i=0;i<len;i++){
		var j = Math.ceil(Math.random()*15);
		a = a + arr[j];
	}
	return a;
}

function getStringBtween(str,str1,str2){
	var a = str.substring(str.indexOf(str1)+str1.length);
	a = a.substring(0, a.indexOf(str2));
	return a;
}

function wasudecypt(_0x6A00) {
    if (_0x6A00.indexOf(".m" + "p4") > -1 || _0x6A00.indexOf(".m3" + "u8") > -1) {
        return _0x6A00;
    }
    var _0x69CA = "0" + "1424364" + "0" + "586" + "0" + "819";
    var _0x6979 = "5162271485" + "0" + "9" + "0" + "109";
    var _0x69E5 = "14" + "0" + "24364586" + "00" + "819";
    var _0x6994 = "51622714859" + "00" + "109";
    try {
        var _0x69AF = CryptoJS.enc.Utf8.parse(_0x69CA);
        var _0x695E = CryptoJS.enc.Utf8.parse(_0x6979);
        var _0x6943 = CryptoJS.AES.decrypt(_0x6A00, _0x69AF, {
            iv: _0x695E,
            mode: CryptoJS.mode.CBC,
            padding: CryptoJS.pad.ZeroPadding
        });
        var _0x6A1B = CryptoJS.enc.Utf8.stringify(_0x6943).toString();
    } catch(e) {
        try {
            var _0x69AF = CryptoJS.enc.Utf8.parse(_0x69E5);
            var _0x695E = CryptoJS.enc.Utf8.parse(_0x6994);
            var _0x6943 = CryptoJS.AES.decrypt(_0x6A00, _0x69AF, {
                iv: _0x695E,
                mode: CryptoJS.mode.CBC,
                padding: CryptoJS.pad.ZeroPadding
            });
            var _0x6A1B = CryptoJS.enc.Utf8.stringify(_0x6943).toString();
        } catch(e) {
            return _0x6A00;
        }
    };
    return _0x6A1B;
}

function base64encode(str){
	var base64EncodeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
	var base64DecodeChars = new Array(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1);
    var out, i, len;
    var c1, c2, c3;
    len = str.length;
    i = 0;
    out = "";
    while (i < len) {
        c1 = str.charCodeAt(i++) & 0xff;
        if (i == len) {
            out += base64EncodeChars.charAt(c1 >> 2);
            out += base64EncodeChars.charAt((c1 & 0x3) << 4);
            out += "==";
            break;
        }
        c2 = str.charCodeAt(i++);
        if (i == len) {
            out += base64EncodeChars.charAt(c1 >> 2);
            out += base64EncodeChars.charAt(((c1 & 0x3) << 4) | ((c2 & 0xF0) >> 4));
            out += base64EncodeChars.charAt((c2 & 0xF) << 2);
            out += "=";
            break;
        }
        c3 = str.charCodeAt(i++);
        out += base64EncodeChars.charAt(c1 >> 2);
        out += base64EncodeChars.charAt(((c1 & 0x3) << 4) | ((c2 & 0xF0) >> 4));
        out += base64EncodeChars.charAt(((c2 & 0xF) << 2) | ((c3 & 0xC0) >> 6));
        out += base64EncodeChars.charAt(c3 & 0x3F);
    }
    return out;
}

function base64decode(str){
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

var CryptoJS=CryptoJS||function(u,p){var d={},l=d.lib={},s=function(){},t=l.Base={extend:function(a){s.prototype=this;var c=new s;a&&c.mixIn(a);c.hasOwnProperty("init")||(c.init=function(){c.$super.init.apply(this,arguments)});c.init.prototype=c;c.$super=this;return c},create:function(){var a=this.extend();a.init.apply(a,arguments);return a},init:function(){},mixIn:function(a){for(var c in a)a.hasOwnProperty(c)&&(this[c]=a[c]);a.hasOwnProperty("toString")&&(this.toString=a.toString)},clone:function(){return this.init.prototype.extend(this)}},r=l.WordArray=t.extend({init:function(a,c){a=this.words=a||[];this.sigBytes=c!=p?c:4*a.length},toString:function(a){return(a||v).stringify(this)},concat:function(a){var c=this.words,e=a.words,j=this.sigBytes;a=a.sigBytes;this.clamp();if(j%4)
for(var k=0;k<a;k++)c[j+k>>>2]|=(e[k>>>2]>>>24-8*(k%4)&255)<<24-8*((j+k)%4);else if(65535<e.length)
for(k=0;k<a;k+=4)c[j+k>>>2]=e[k>>>2];else c.push.apply(c,e);this.sigBytes+=a;return this},clamp:function(){var a=this.words,c=this.sigBytes;a[c>>>2]&=4294967295<<32-8*(c%4);a.length=u.ceil(c/4)},clone:function(){var a=t.clone.call(this);a.words=this.words.slice(0);return a},random:function(a){for(var c=[],e=0;e<a;e+=4)c.push(4294967296*u.random()|0);return new r.init(c,a)}}),w=d.enc={},v=w.Hex={stringify:function(a){var c=a.words;a=a.sigBytes;for(var e=[],j=0;j<a;j++){var k=c[j>>>2]>>>24-8*(j%4)&255;e.push((k>>>4).toString(16));e.push((k&15).toString(16))}
return e.join("")},parse:function(a){for(var c=a.length,e=[],j=0;j<c;j+=2)e[j>>>3]|=parseInt(a.substr(j,2),16)<<24-4*(j%8);return new r.init(e,c/2)}},b=w.Latin1={stringify:function(a){var c=a.words;a=a.sigBytes;for(var e=[],j=0;j<a;j++)e.push(String.fromCharCode(c[j>>>2]>>>24-8*(j%4)&255));return e.join("")},parse:function(a){for(var c=a.length,e=[],j=0;j<c;j++)e[j>>>2]|=(a.charCodeAt(j)&255)<<24-8*(j%4);return new r.init(e,c)}},x=w.Utf8={stringify:function(a){try{return decodeURIComponent(escape(b.stringify(a)))}catch(c){throw Error("Malformed UTF-8 data");}},parse:function(a){return b.parse(unescape(encodeURIComponent(a)))}},q=l.BufferedBlockAlgorithm=t.extend({reset:function(){this._data=new r.init;this._nDataBytes=0},_append:function(a){"string"==typeof a&&(a=x.parse(a));this._data.concat(a);this._nDataBytes+=a.sigBytes},_process:function(a){var c=this._data,e=c.words,j=c.sigBytes,k=this.blockSize,b=j/(4*k),b=a?u.ceil(b):u.max((b|0)-this._minBufferSize,0);a=b*k;j=u.min(4*a,j);if(a){for(var q=0;q<a;q+=k)this._doProcessBlock(e,q);q=e.splice(0,a);c.sigBytes-=j}
return new r.init(q,j)},clone:function(){var a=t.clone.call(this);a._data=this._data.clone();return a},_minBufferSize:0});l.Hasher=q.extend({cfg:t.extend(),init:function(a){this.cfg=this.cfg.extend(a);this.reset()},reset:function(){q.reset.call(this);this._doReset()},update:function(a){this._append(a);this._process();return this},finalize:function(a){a&&this._append(a);return this._doFinalize()},blockSize:16,_createHelper:function(a){return function(b,e){return(new a.init(e)).finalize(b)}},_createHmacHelper:function(a){return function(b,e){return(new n.HMAC.init(a,e)).finalize(b)}}});var n=d.algo={};return d}(Math);(function(){var u=CryptoJS,p=u.lib.WordArray;u.enc.Base64={stringify:function(d){var l=d.words,p=d.sigBytes,t=this._map;d.clamp();d=[];for(var r=0;r<p;r+=3)
for(var w=(l[r>>>2]>>>24-8*(r%4)&255)<<16|(l[r+1>>>2]>>>24-8*((r+1)%4)&255)<<8|l[r+2>>>2]>>>24-8*((r+2)%4)&255,v=0;4>v&&r+0.75*v<p;v++)d.push(t.charAt(w>>>6*(3-v)&63));if(l=t.charAt(64))
for(;d.length%4;)d.push(l);return d.join("")},parse:function(d){var l=d.length,s=this._map,t=s.charAt(64);t&&(t=d.indexOf(t),-1!=t&&(l=t));for(var t=[],r=0,w=0;w<l;w++)
if(w%4){var v=s.indexOf(d.charAt(w-1))<<2*(w%4),b=s.indexOf(d.charAt(w))>>>6-2*(w%4);t[r>>>2]|=(v|b)<<24-8*(r%4);r++}
return p.create(t,r)},_map:"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="}})();(function(u){function p(b,n,a,c,e,j,k){b=b+(n&a|~n&c)+e+k;return(b<<j|b>>>32-j)+n}
function d(b,n,a,c,e,j,k){b=b+(n&c|a&~c)+e+k;return(b<<j|b>>>32-j)+n}
function l(b,n,a,c,e,j,k){b=b+(n^a^c)+e+k;return(b<<j|b>>>32-j)+n}
function s(b,n,a,c,e,j,k){b=b+(a^(n|~c))+e+k;return(b<<j|b>>>32-j)+n}
for(var t=CryptoJS,r=t.lib,w=r.WordArray,v=r.Hasher,r=t.algo,b=[],x=0;64>x;x++)b[x]=4294967296*u.abs(u.sin(x+1))|0;r=r.MD5=v.extend({_doReset:function(){this._hash=new w.init([1732584193,4023233417,2562383102,271733878])},_doProcessBlock:function(q,n){for(var a=0;16>a;a++){var c=n+a,e=q[c];q[c]=(e<<8|e>>>24)&16711935|(e<<24|e>>>8)&4278255360}
var a=this._hash.words,c=q[n+0],e=q[n+1],j=q[n+2],k=q[n+3],z=q[n+4],r=q[n+5],t=q[n+6],w=q[n+7],v=q[n+8],A=q[n+9],B=q[n+10],C=q[n+11],u=q[n+12],D=q[n+13],E=q[n+14],x=q[n+15],f=a[0],m=a[1],g=a[2],h=a[3],f=p(f,m,g,h,c,7,b[0]),h=p(h,f,m,g,e,12,b[1]),g=p(g,h,f,m,j,17,b[2]),m=p(m,g,h,f,k,22,b[3]),f=p(f,m,g,h,z,7,b[4]),h=p(h,f,m,g,r,12,b[5]),g=p(g,h,f,m,t,17,b[6]),m=p(m,g,h,f,w,22,b[7]),f=p(f,m,g,h,v,7,b[8]),h=p(h,f,m,g,A,12,b[9]),g=p(g,h,f,m,B,17,b[10]),m=p(m,g,h,f,C,22,b[11]),f=p(f,m,g,h,u,7,b[12]),h=p(h,f,m,g,D,12,b[13]),g=p(g,h,f,m,E,17,b[14]),m=p(m,g,h,f,x,22,b[15]),f=d(f,m,g,h,e,5,b[16]),h=d(h,f,m,g,t,9,b[17]),g=d(g,h,f,m,C,14,b[18]),m=d(m,g,h,f,c,20,b[19]),f=d(f,m,g,h,r,5,b[20]),h=d(h,f,m,g,B,9,b[21]),g=d(g,h,f,m,x,14,b[22]),m=d(m,g,h,f,z,20,b[23]),f=d(f,m,g,h,A,5,b[24]),h=d(h,f,m,g,E,9,b[25]),g=d(g,h,f,m,k,14,b[26]),m=d(m,g,h,f,v,20,b[27]),f=d(f,m,g,h,D,5,b[28]),h=d(h,f,m,g,j,9,b[29]),g=d(g,h,f,m,w,14,b[30]),m=d(m,g,h,f,u,20,b[31]),f=l(f,m,g,h,r,4,b[32]),h=l(h,f,m,g,v,11,b[33]),g=l(g,h,f,m,C,16,b[34]),m=l(m,g,h,f,E,23,b[35]),f=l(f,m,g,h,e,4,b[36]),h=l(h,f,m,g,z,11,b[37]),g=l(g,h,f,m,w,16,b[38]),m=l(m,g,h,f,B,23,b[39]),f=l(f,m,g,h,D,4,b[40]),h=l(h,f,m,g,c,11,b[41]),g=l(g,h,f,m,k,16,b[42]),m=l(m,g,h,f,t,23,b[43]),f=l(f,m,g,h,A,4,b[44]),h=l(h,f,m,g,u,11,b[45]),g=l(g,h,f,m,x,16,b[46]),m=l(m,g,h,f,j,23,b[47]),f=s(f,m,g,h,c,6,b[48]),h=s(h,f,m,g,w,10,b[49]),g=s(g,h,f,m,E,15,b[50]),m=s(m,g,h,f,r,21,b[51]),f=s(f,m,g,h,u,6,b[52]),h=s(h,f,m,g,k,10,b[53]),g=s(g,h,f,m,B,15,b[54]),m=s(m,g,h,f,e,21,b[55]),f=s(f,m,g,h,v,6,b[56]),h=s(h,f,m,g,x,10,b[57]),g=s(g,h,f,m,t,15,b[58]),m=s(m,g,h,f,D,21,b[59]),f=s(f,m,g,h,z,6,b[60]),h=s(h,f,m,g,C,10,b[61]),g=s(g,h,f,m,j,15,b[62]),m=s(m,g,h,f,A,21,b[63]);a[0]=a[0]+f|0;a[1]=a[1]+m|0;a[2]=a[2]+g|0;a[3]=a[3]+h|0},_doFinalize:function(){var b=this._data,n=b.words,a=8*this._nDataBytes,c=8*b.sigBytes;n[c>>>5]|=128<<24-c%32;var e=u.floor(a/4294967296);n[(c+64>>>9<<4)+15]=(e<<8|e>>>24)&16711935|(e<<24|e>>>8)&4278255360;n[(c+64>>>9<<4)+14]=(a<<8|a>>>24)&16711935|(a<<24|a>>>8)&4278255360;b.sigBytes=4*(n.length+1);this._process();b=this._hash;n=b.words;for(a=0;4>a;a++)c=n[a],n[a]=(c<<8|c>>>24)&16711935|(c<<24|c>>>8)&4278255360;return b},clone:function(){var b=v.clone.call(this);b._hash=this._hash.clone();return b}});t.MD5=v._createHelper(r);t.HmacMD5=v._createHmacHelper(r)})(Math);(function(){var u=CryptoJS,p=u.lib,d=p.Base,l=p.WordArray,p=u.algo,s=p.EvpKDF=d.extend({cfg:d.extend({keySize:4,hasher:p.MD5,iterations:1}),init:function(d){this.cfg=this.cfg.extend(d)},compute:function(d,r){for(var p=this.cfg,s=p.hasher.create(),b=l.create(),u=b.words,q=p.keySize,p=p.iterations;u.length<q;){n&&s.update(n);var n=s.update(d).finalize(r);s.reset();for(var a=1;a<p;a++)n=s.finalize(n),s.reset();b.concat(n)}
b.sigBytes=4*q;return b}});u.EvpKDF=function(d,l,p){return s.create(p).compute(d,l)}})();CryptoJS.lib.Cipher||function(u){var p=CryptoJS,d=p.lib,l=d.Base,s=d.WordArray,t=d.BufferedBlockAlgorithm,r=p.enc.Base64,w=p.algo.EvpKDF,v=d.Cipher=t.extend({cfg:l.extend(),createEncryptor:function(e,a){return this.create(this._ENC_XFORM_MODE,e,a)},createDecryptor:function(e,a){return this.create(this._DEC_XFORM_MODE,e,a)},init:function(e,a,b){this.cfg=this.cfg.extend(b);this._xformMode=e;this._key=a;this.reset()},reset:function(){t.reset.call(this);this._doReset()},process:function(e){this._append(e);return this._process()},finalize:function(e){e&&this._append(e);return this._doFinalize()},keySize:4,ivSize:4,_ENC_XFORM_MODE:1,_DEC_XFORM_MODE:2,_createHelper:function(e){return{encrypt:function(b,k,d){return("string"==typeof k?c:a).encrypt(e,b,k,d)},decrypt:function(b,k,d){return("string"==typeof k?c:a).decrypt(e,b,k,d)}}}});d.StreamCipher=v.extend({_doFinalize:function(){return this._process(!0)},blockSize:1});var b=p.mode={},x=function(e,a,b){var c=this._iv;c?this._iv=u:c=this._prevBlock;for(var d=0;d<b;d++)e[a+d]^=c[d]},q=(d.BlockCipherMode=l.extend({createEncryptor:function(e,a){return this.Encryptor.create(e,a)},createDecryptor:function(e,a){return this.Decryptor.create(e,a)},init:function(e,a){this._cipher=e;this._iv=a}})).extend();q.Encryptor=q.extend({processBlock:function(e,a){var b=this._cipher,c=b.blockSize;x.call(this,e,a,c);b.encryptBlock(e,a);this._prevBlock=e.slice(a,a+c)}});q.Decryptor=q.extend({processBlock:function(e,a){var b=this._cipher,c=b.blockSize,d=e.slice(a,a+c);b.decryptBlock(e,a);x.call(this,e,a,c);this._prevBlock=d}});b=b.CBC=q;q=(p.pad={}).Pkcs7={pad:function(a,b){for(var c=4*b,c=c-a.sigBytes%c,d=c<<24|c<<16|c<<8|c,l=[],n=0;n<c;n+=4)l.push(d);c=s.create(l,c);a.concat(c)},unpad:function(a){a.sigBytes-=a.words[a.sigBytes-1>>>2]&255}};d.BlockCipher=v.extend({cfg:v.cfg.extend({mode:b,padding:q}),reset:function(){v.reset.call(this);var a=this.cfg,b=a.iv,a=a.mode;if(this._xformMode==this._ENC_XFORM_MODE)var c=a.createEncryptor;else c=a.createDecryptor,this._minBufferSize=1;this._mode=c.call(a,this,b&&b.words)},_doProcessBlock:function(a,b){this._mode.processBlock(a,b)},_doFinalize:function(){var a=this.cfg.padding;if(this._xformMode==this._ENC_XFORM_MODE){a.pad(this._data,this.blockSize);var b=this._process(!0)}else b=this._process(!0),a.unpad(b);return b},blockSize:4});var n=d.CipherParams=l.extend({init:function(a){this.mixIn(a)},toString:function(a){return(a||this.formatter).stringify(this)}}),b=(p.format={}).OpenSSL={stringify:function(a){var b=a.ciphertext;a=a.salt;return(a?s.create([1398893684,1701076831]).concat(a).concat(b):b).toString(r)},parse:function(a){a=r.parse(a);var b=a.words;if(1398893684==b[0]&&1701076831==b[1]){var c=s.create(b.slice(2,4));b.splice(0,4);a.sigBytes-=16}
return n.create({ciphertext:a,salt:c})}},a=d.SerializableCipher=l.extend({cfg:l.extend({format:b}),encrypt:function(a,b,c,d){d=this.cfg.extend(d);var l=a.createEncryptor(c,d);b=l.finalize(b);l=l.cfg;return n.create({ciphertext:b,key:c,iv:l.iv,algorithm:a,mode:l.mode,padding:l.padding,blockSize:a.blockSize,formatter:d.format})},decrypt:function(a,b,c,d){d=this.cfg.extend(d);b=this._parse(b,d.format);return a.createDecryptor(c,d).finalize(b.ciphertext)},_parse:function(a,b){return"string"==typeof a?b.parse(a,this):a}}),p=(p.kdf={}).OpenSSL={execute:function(a,b,c,d){d||(d=s.random(8));a=w.create({keySize:b+c}).compute(a,d);c=s.create(a.words.slice(b),4*c);a.sigBytes=4*b;return n.create({key:a,iv:c,salt:d})}},c=d.PasswordBasedCipher=a.extend({cfg:a.cfg.extend({kdf:p}),encrypt:function(b,c,d,l){l=this.cfg.extend(l);d=l.kdf.execute(d,b.keySize,b.ivSize);l.iv=d.iv;b=a.encrypt.call(this,b,c,d.key,l);b.mixIn(d);return b},decrypt:function(b,c,d,l){l=this.cfg.extend(l);c=this._parse(c,l.format);d=l.kdf.execute(d,b.keySize,b.ivSize,c.salt);l.iv=d.iv;return a.decrypt.call(this,b,c,d.key,l)}})}();(function(){for(var u=CryptoJS,p=u.lib.BlockCipher,d=u.algo,l=[],s=[],t=[],r=[],w=[],v=[],b=[],x=[],q=[],n=[],a=[],c=0;256>c;c++)a[c]=128>c?c<<1:c<<1^283;for(var e=0,j=0,c=0;256>c;c++){var k=j^j<<1^j<<2^j<<3^j<<4,k=k>>>8^k&255^99;l[e]=k;s[k]=e;var z=a[e],F=a[z],G=a[F],y=257*a[k]^16843008*k;t[e]=y<<24|y>>>8;r[e]=y<<16|y>>>16;w[e]=y<<8|y>>>24;v[e]=y;y=16843009*G^65537*F^257*z^16843008*e;b[k]=y<<24|y>>>8;x[k]=y<<16|y>>>16;q[k]=y<<8|y>>>24;n[k]=y;e?(e=z^a[a[a[G^z]]],j^=a[a[j]]):e=j=1}
var H=[0,1,2,4,8,16,32,64,128,27,54],d=d.AES=p.extend({_doReset:function(){for(var a=this._key,c=a.words,d=a.sigBytes/4,a=4*((this._nRounds=d+6)+1),e=this._keySchedule=[],j=0;j<a;j++)
if(j<d)e[j]=c[j];else{var k=e[j-1];j%d?6<d&&4==j%d&&(k=l[k>>>24]<<24|l[k>>>16&255]<<16|l[k>>>8&255]<<8|l[k&255]):(k=k<<8|k>>>24,k=l[k>>>24]<<24|l[k>>>16&255]<<16|l[k>>>8&255]<<8|l[k&255],k^=H[j/d|0]<<24);e[j]=e[j-d]^k}
c=this._invKeySchedule=[];for(d=0;d<a;d++)j=a-d,k=d%4?e[j]:e[j-4],c[d]=4>d||4>=j?k:b[l[k>>>24]]^x[l[k>>>16&255]]^q[l[k>>>8&255]]^n[l[k&255]]},encryptBlock:function(a,b){this._doCryptBlock(a,b,this._keySchedule,t,r,w,v,l)},decryptBlock:function(a,c){var d=a[c+1];a[c+1]=a[c+3];a[c+3]=d;this._doCryptBlock(a,c,this._invKeySchedule,b,x,q,n,s);d=a[c+1];a[c+1]=a[c+3];a[c+3]=d},_doCryptBlock:function(a,b,c,d,e,j,l,f){for(var m=this._nRounds,g=a[b]^c[0],h=a[b+1]^c[1],k=a[b+2]^c[2],n=a[b+3]^c[3],p=4,r=1;r<m;r++)var q=d[g>>>24]^e[h>>>16&255]^j[k>>>8&255]^l[n&255]^c[p++],s=d[h>>>24]^e[k>>>16&255]^j[n>>>8&255]^l[g&255]^c[p++],t=d[k>>>24]^e[n>>>16&255]^j[g>>>8&255]^l[h&255]^c[p++],n=d[n>>>24]^e[g>>>16&255]^j[h>>>8&255]^l[k&255]^c[p++],g=q,h=s,k=t;q=(f[g>>>24]<<24|f[h>>>16&255]<<16|f[k>>>8&255]<<8|f[n&255])^c[p++];s=(f[h>>>24]<<24|f[k>>>16&255]<<16|f[n>>>8&255]<<8|f[g&255])^c[p++];t=(f[k>>>24]<<24|f[n>>>16&255]<<16|f[g>>>8&255]<<8|f[h&255])^c[p++];n=(f[n>>>24]<<24|f[g>>>16&255]<<16|f[h>>>8&255]<<8|f[k&255])^c[p++];a[b]=q;a[b+1]=s;a[b+2]=t;a[b+3]=n},keySize:8});u.AES=p._createHelper(d)
CryptoJS.pad.ZeroPadding={pad:function(data,blockSize){var blockSizeBytes=blockSize*4;data.clamp();data.sigBytes+=blockSizeBytes-((data.sigBytes%blockSizeBytes)||blockSizeBytes);},unpad:function(data){var dataWords=data.words;var i=data.sigBytes-1;while(!((dataWords[i>>>2]>>>(24-(i%4)*8))&0xff)){i--;}
data.sigBytes=i+1;}}})();

var CryptoJS1=CryptoJS1||function(i,m){var p={},h=p.lib={},n=h.Base=function(){function a(){}return{extend:function(b){a.prototype=this;var c=new a;b&&c.mixIn(b);c.$super=this;return c},create:function(){var a=this.extend();a.init.apply(a,arguments);return a},init:function(){},mixIn:function(a){for(var c in a)a.hasOwnProperty(c)&&(this[c]=a[c]);a.hasOwnProperty("toString")&&(this.toString=a.toString)},clone:function(){return this.$super.extend(this)}}}(),o=h.WordArray=n.extend({init:function(a,b){a=
this.words=a||[];this.sigBytes=b!=m?b:4*a.length},toString:function(a){return(a||e).stringify(this)},concat:function(a){var b=this.words,c=a.words,d=this.sigBytes,a=a.sigBytes;this.clamp();if(d%4)for(var f=0;f<a;f++)b[d+f>>>2]|=(c[f>>>2]>>>24-8*(f%4)&255)<<24-8*((d+f)%4);else if(65535<c.length)for(f=0;f<a;f+=4)b[d+f>>>2]=c[f>>>2];else b.push.apply(b,c);this.sigBytes+=a;return this},clamp:function(){var a=this.words,b=this.sigBytes;a[b>>>2]&=4294967295<<32-8*(b%4);a.length=i.ceil(b/4)},clone:function(){var a=
n.clone.call(this);a.words=this.words.slice(0);return a},random:function(a){for(var b=[],c=0;c<a;c+=4)b.push(4294967296*i.random()|0);return o.create(b,a)}}),q=p.enc={},e=q.Hex={stringify:function(a){for(var b=a.words,a=a.sigBytes,c=[],d=0;d<a;d++){var f=b[d>>>2]>>>24-8*(d%4)&255;c.push((f>>>4).toString(16));c.push((f&15).toString(16))}return c.join("")},parse:function(a){for(var b=a.length,c=[],d=0;d<b;d+=2)c[d>>>3]|=parseInt(a.substr(d,2),16)<<24-4*(d%8);return o.create(c,b/2)}},g=q.Latin1={stringify:function(a){for(var b=
a.words,a=a.sigBytes,c=[],d=0;d<a;d++)c.push(String.fromCharCode(b[d>>>2]>>>24-8*(d%4)&255));return c.join("")},parse:function(a){for(var b=a.length,c=[],d=0;d<b;d++)c[d>>>2]|=(a.charCodeAt(d)&255)<<24-8*(d%4);return o.create(c,b)}},j=q.Utf8={stringify:function(a){try{return decodeURIComponent(escape(g.stringify(a)))}catch(b){throw Error("Malformed UTF-8 data");}},parse:function(a){return g.parse(unescape(encodeURIComponent(a)))}},k=h.BufferedBlockAlgorithm=n.extend({reset:function(){this._data=o.create();
this._nDataBytes=0},_append:function(a){"string"==typeof a&&(a=j.parse(a));this._data.concat(a);this._nDataBytes+=a.sigBytes},_process:function(a){var b=this._data,c=b.words,d=b.sigBytes,f=this.blockSize,e=d/(4*f),e=a?i.ceil(e):i.max((e|0)-this._minBufferSize,0),a=e*f,d=i.min(4*a,d);if(a){for(var g=0;g<a;g+=f)this._doProcessBlock(c,g);g=c.splice(0,a);b.sigBytes-=d}return o.create(g,d)},clone:function(){var a=n.clone.call(this);a._data=this._data.clone();return a},_minBufferSize:0});h.Hasher=k.extend({init:function(){this.reset()},
reset:function(){k.reset.call(this);this._doReset()},update:function(a){this._append(a);this._process();return this},finalize:function(a){a&&this._append(a);this._doFinalize();return this._hash},clone:function(){var a=k.clone.call(this);a._hash=this._hash.clone();return a},blockSize:16,_createHelper:function(a){return function(b,c){return a.create(c).finalize(b)}},_createHmacHelper:function(a){return function(b,c){return l.HMAC.create(a,c).finalize(b)}}});var l=p.algo={};return p}(Math);
(function(){var i=CryptoJS1,m=i.lib,p=m.WordArray,m=m.Hasher,h=[],n=i.algo.SHA1=m.extend({_doReset:function(){this._hash=p.create([1732584193,4023233417,2562383102,271733878,3285377520])},_doProcessBlock:function(o,i){for(var e=this._hash.words,g=e[0],j=e[1],k=e[2],l=e[3],a=e[4],b=0;80>b;b++){if(16>b)h[b]=o[i+b]|0;else{var c=h[b-3]^h[b-8]^h[b-14]^h[b-16];h[b]=c<<1|c>>>31}c=(g<<5|g>>>27)+a+h[b];c=20>b?c+((j&k|~j&l)+1518500249):40>b?c+((j^k^l)+1859775393):60>b?c+((j&k|j&l|k&l)-1894007588):c+((j^k^l)-
899497514);a=l;l=k;k=j<<30|j>>>2;j=g;g=c}e[0]=e[0]+g|0;e[1]=e[1]+j|0;e[2]=e[2]+k|0;e[3]=e[3]+l|0;e[4]=e[4]+a|0},_doFinalize:function(){var i=this._data,h=i.words,e=8*this._nDataBytes,g=8*i.sigBytes;h[g>>>5]|=128<<24-g%32;h[(g+64>>>9<<4)+15]=e;i.sigBytes=4*h.length;this._process()}});i.SHA1=m._createHelper(n);i.HmacSHA1=m._createHmacHelper(n)})();

// md5
function safe_add(x, y) {
    var lsw = (x & 0xFFFF) + (y & 0xFFFF);
    var msw = (x >> 16) + (y >> 16) + (lsw >> 16);
    return (msw << 16) | (lsw & 0xFFFF);
}

/*
 * Bitwise rotate a 32-bit number to the left.
 */
function rol(num, cnt) {
    return (num << cnt) | (num >>> (32 - cnt));
}

/*
 * These functions implement the four basic operations the algorithm uses.
 */
function cmn(q, a, b, x, s, t) {
    return safe_add(rol(safe_add(safe_add(a, q), safe_add(x, t)), s), b);
}
function ff(a, b, c, d, x, s, t) {
    return cmn((b & c) | ((~b) & d), a, b, x, s, t);
}
function gg(a, b, c, d, x, s, t) {
    return cmn((b & d) | (c & (~d)), a, b, x, s, t);
}
function hh(a, b, c, d, x, s, t) {
    return cmn(b ^ c ^ d, a, b, x, s, t);
}
function ii(a, b, c, d, x, s, t) {
    return cmn(c ^ (b | (~d)), a, b, x, s, t);
}

/*
 * Calculate the MD5 of an array of little-endian words, producing an array
 * of little-endian words.
 */
function coreMD5(x) {
    var a =  1732584193;
    var b = -271733879;
    var c = -1732584194;
    var d =  271733878;

    for(var i = 0; i < x.length; i += 16)
    {
        var olda = a;
        var oldb = b;
        var oldc = c;
        var oldd = d;

        a = ff(a, b, c, d, x[i+ 0], 7 , -680876936);
        d = ff(d, a, b, c, x[i+ 1], 12, -389564586);
        c = ff(c, d, a, b, x[i+ 2], 17,  606105819);
        b = ff(b, c, d, a, x[i+ 3], 22, -1044525330);
        a = ff(a, b, c, d, x[i+ 4], 7 , -176418897);
        d = ff(d, a, b, c, x[i+ 5], 12,  1200080426);
        c = ff(c, d, a, b, x[i+ 6], 17, -1473231341);
        b = ff(b, c, d, a, x[i+ 7], 22, -45705983);
        a = ff(a, b, c, d, x[i+ 8], 7 ,  1770035416);
        d = ff(d, a, b, c, x[i+ 9], 12, -1958414417);
        c = ff(c, d, a, b, x[i+10], 17, -42063);
        b = ff(b, c, d, a, x[i+11], 22, -1990404162);
        a = ff(a, b, c, d, x[i+12], 7 ,  1804603682);
        d = ff(d, a, b, c, x[i+13], 12, -40341101);
        c = ff(c, d, a, b, x[i+14], 17, -1502002290);
        b = ff(b, c, d, a, x[i+15], 22,  1236535329);

        a = gg(a, b, c, d, x[i+ 1], 5 , -165796510);
        d = gg(d, a, b, c, x[i+ 6], 9 , -1069501632);
        c = gg(c, d, a, b, x[i+11], 14,  643717713);
        b = gg(b, c, d, a, x[i+ 0], 20, -373897302);
        a = gg(a, b, c, d, x[i+ 5], 5 , -701558691);
        d = gg(d, a, b, c, x[i+10], 9 ,  38016083);
        c = gg(c, d, a, b, x[i+15], 14, -660478335);
        b = gg(b, c, d, a, x[i+ 4], 20, -405537848);
        a = gg(a, b, c, d, x[i+ 9], 5 ,  568446438);
        d = gg(d, a, b, c, x[i+14], 9 , -1019803690);
        c = gg(c, d, a, b, x[i+ 3], 14, -187363961);
        b = gg(b, c, d, a, x[i+ 8], 20,  1163531501);
        a = gg(a, b, c, d, x[i+13], 5 , -1444681467);
        d = gg(d, a, b, c, x[i+ 2], 9 , -51403784);
        c = gg(c, d, a, b, x[i+ 7], 14,  1735328473);
        b = gg(b, c, d, a, x[i+12], 20, -1926607734);

        a = hh(a, b, c, d, x[i+ 5], 4 , -378558);
        d = hh(d, a, b, c, x[i+ 8], 11, -2022574463);
        c = hh(c, d, a, b, x[i+11], 16,  1839030562);
        b = hh(b, c, d, a, x[i+14], 23, -35309556);
        a = hh(a, b, c, d, x[i+ 1], 4 , -1530992060);
        d = hh(d, a, b, c, x[i+ 4], 11,  1272893353);
        c = hh(c, d, a, b, x[i+ 7], 16, -155497632);
        b = hh(b, c, d, a, x[i+10], 23, -1094730640);
        a = hh(a, b, c, d, x[i+13], 4 ,  681279174);
        d = hh(d, a, b, c, x[i+ 0], 11, -358537222);
        c = hh(c, d, a, b, x[i+ 3], 16, -722521979);
        b = hh(b, c, d, a, x[i+ 6], 23,  76029189);
        a = hh(a, b, c, d, x[i+ 9], 4 , -640364487);
        d = hh(d, a, b, c, x[i+12], 11, -421815835);
        c = hh(c, d, a, b, x[i+15], 16,  530742520);
        b = hh(b, c, d, a, x[i+ 2], 23, -995338651);

        a = ii(a, b, c, d, x[i+ 0], 6 , -198630844);
        d = ii(d, a, b, c, x[i+ 7], 10,  1126891415);
        c = ii(c, d, a, b, x[i+14], 15, -1416354905);
        b = ii(b, c, d, a, x[i+ 5], 21, -57434055);
        a = ii(a, b, c, d, x[i+12], 6 ,  1700485571);
        d = ii(d, a, b, c, x[i+ 3], 10, -1894986606);
        c = ii(c, d, a, b, x[i+10], 15, -1051523);
        b = ii(b, c, d, a, x[i+ 1], 21, -2054922799);
        a = ii(a, b, c, d, x[i+ 8], 6 ,  1873313359);
        d = ii(d, a, b, c, x[i+15], 10, -30611744);
        c = ii(c, d, a, b, x[i+ 6], 15, -1560198380);
        b = ii(b, c, d, a, x[i+13], 21,  1309151649);
        a = ii(a, b, c, d, x[i+ 4], 6 , -145523070);
        d = ii(d, a, b, c, x[i+11], 10, -1120210379);
        c = ii(c, d, a, b, x[i+ 2], 15,  718787259);
        b = ii(b, c, d, a, x[i+ 9], 21, -343485551);

        a = safe_add(a, olda);
        b = safe_add(b, oldb);
        c = safe_add(c, oldc);
        d = safe_add(d, oldd);
    }
    return [a, b, c, d];
}

/*
 * Convert an array of little-endian words to a hex string.
 */
function binl2hex(binarray) {
    var hex_tab = "0123456789abcdef";
    var str = "";
    for(var i = 0; i < binarray.length * 4; i++)
    {
        str += hex_tab.charAt((binarray[i>>2] >> ((i%4)*8+4)) & 0xF) +
            hex_tab.charAt((binarray[i>>2] >> ((i%4)*8)) & 0xF);
    }
    return str;
}

/*
 * Convert an array of little-endian words to a base64 encoded string.
 */
function binl2b64(binarray) {
    var tab = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    var str = "";
    for(var i = 0; i < binarray.length * 32; i += 6)
    {
        str += tab.charAt(((binarray[i>>5] << (i%32)) & 0x3F) |
            ((binarray[i>>5+1] >> (32-i%32)) & 0x3F));
    }
    return str;
}

/*
 * Convert an 8-bit character string to a sequence of 16-word blocks, stored
 * as an array, and append appropriate padding for MD4/5 calculation.
 * If any of the characters are >255, the high byte is silently ignored.
 */
function str2binl(str) {
    var nblk = ((str.length + 8) >> 6) + 1; // number of 16-word blocks
    var blks = new Array(nblk * 16);
    for(var i = 0; i < nblk * 16; i++) {
		blks[i] = 0;
	}
    for(var i = 0; i < str.length; i++) {
        blks[i>>2] |= (str.charCodeAt(i) & 0xFF) << ((i%4) * 8);
	}
    blks[i>>2] |= 0x80 << ((i%4) * 8);
    blks[nblk*16-2] = str.length * 8;
    return blks;
}

/*
 * Convert a wide-character string to a sequence of 16-word blocks, stored as
 * an array, and append appropriate padding for MD4/5 calculation.
 */
function strw2binl(str) {
    var nblk = ((str.length + 4) >> 5) + 1; // number of 16-word blocks
    var blks = new Array(nblk * 16);
    for(var i = 0; i < nblk * 16; i++) {
		blks[i] = 0;
	} 
    for(var i = 0; i < str.length; i++) {
        blks[i>>1] |= str.charCodeAt(i) << ((i%2) * 16);
	}
    blks[i>>1] |= 0x80 << ((i%2) * 16);
    blks[nblk*16-2] = str.length * 16;
    return blks;
}

/*
 * External interface
 */
function hexMD5 (str) { return binl2hex(coreMD5( str2binl(str))); }
function hexMD5w(str) { return binl2hex(coreMD5(strw2binl(str))); }
function b64MD5 (str) { return binl2b64(coreMD5( str2binl(str))); }
function b64MD5w(str) { return binl2b64(coreMD5(strw2binl(str))); }
/* Backward compatibility */
function calcMD5(str) { return binl2hex(coreMD5( str2binl(str))); }

function base64decode(str){
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

var CryptoJS=CryptoJS||function(u,l){var d={},n=d.lib={},p=function(){},s=n.Base={extend:function(a){p.prototype=this;var c=new p;a&&c.mixIn(a);c.hasOwnProperty("init")||(c.init=function(){c.$super.init.apply(this,arguments)});c.init.prototype=c;c.$super=this;return c},create:function(){var a=this.extend();a.init.apply(a,arguments);return a},init:function(){},mixIn:function(a){for(var c in a)a.hasOwnProperty(c)&&(this[c]=a[c]);a.hasOwnProperty("toString")&&(this.toString=a.toString)},clone:function(){return this.init.prototype.extend(this)}},
q=n.WordArray=s.extend({init:function(a,c){a=this.words=a||[];this.sigBytes=c!=l?c:4*a.length},toString:function(a){return(a||v).stringify(this)},concat:function(a){var c=this.words,m=a.words,f=this.sigBytes;a=a.sigBytes;this.clamp();if(f%4)for(var t=0;t<a;t++)c[f+t>>>2]|=(m[t>>>2]>>>24-8*(t%4)&255)<<24-8*((f+t)%4);else if(65535<m.length)for(t=0;t<a;t+=4)c[f+t>>>2]=m[t>>>2];else c.push.apply(c,m);this.sigBytes+=a;return this},clamp:function(){var a=this.words,c=this.sigBytes;a[c>>>2]&=4294967295<<
32-8*(c%4);a.length=u.ceil(c/4)},clone:function(){var a=s.clone.call(this);a.words=this.words.slice(0);return a},random:function(a){for(var c=[],m=0;m<a;m+=4)c.push(4294967296*u.random()|0);return new q.init(c,a)}}),w=d.enc={},v=w.Hex={stringify:function(a){var c=a.words;a=a.sigBytes;for(var m=[],f=0;f<a;f++){var t=c[f>>>2]>>>24-8*(f%4)&255;m.push((t>>>4).toString(16));m.push((t&15).toString(16))}return m.join("")},parse:function(a){for(var c=a.length,m=[],f=0;f<c;f+=2)m[f>>>3]|=parseInt(a.substr(f,
2),16)<<24-4*(f%8);return new q.init(m,c/2)}},b=w.Latin1={stringify:function(a){var c=a.words;a=a.sigBytes;for(var m=[],f=0;f<a;f++)m.push(String.fromCharCode(c[f>>>2]>>>24-8*(f%4)&255));return m.join("")},parse:function(a){for(var c=a.length,m=[],f=0;f<c;f++)m[f>>>2]|=(a.charCodeAt(f)&255)<<24-8*(f%4);return new q.init(m,c)}},x=w.Utf8={stringify:function(a){try{return decodeURIComponent(escape(b.stringify(a)))}catch(c){throw Error("Malformed UTF-8 data");}},parse:function(a){return b.parse(unescape(encodeURIComponent(a)))}},
r=n.BufferedBlockAlgorithm=s.extend({reset:function(){this._data=new q.init;this._nDataBytes=0},_append:function(a){"string"==typeof a&&(a=x.parse(a));this._data.concat(a);this._nDataBytes+=a.sigBytes},_process:function(a){var c=this._data,m=c.words,f=c.sigBytes,t=this.blockSize,b=f/(4*t),b=a?u.ceil(b):u.max((b|0)-this._minBufferSize,0);a=b*t;f=u.min(4*a,f);if(a){for(var e=0;e<a;e+=t)this._doProcessBlock(m,e);e=m.splice(0,a);c.sigBytes-=f}return new q.init(e,f)},clone:function(){var a=s.clone.call(this);
a._data=this._data.clone();return a},_minBufferSize:0});n.Hasher=r.extend({cfg:s.extend(),init:function(a){this.cfg=this.cfg.extend(a);this.reset()},reset:function(){r.reset.call(this);this._doReset()},update:function(a){this._append(a);this._process();return this},finalize:function(a){a&&this._append(a);return this._doFinalize()},blockSize:16,_createHelper:function(a){return function(c,m){return(new a.init(m)).finalize(c)}},_createHmacHelper:function(a){return function(c,m){return(new e.HMAC.init(a,
m)).finalize(c)}}});var e=d.algo={};return d}(Math);
(function(){var u=CryptoJS,l=u.lib.WordArray;u.enc.Base64={stringify:function(d){var n=d.words,l=d.sigBytes,s=this._map;d.clamp();d=[];for(var q=0;q<l;q+=3)for(var w=(n[q>>>2]>>>24-8*(q%4)&255)<<16|(n[q+1>>>2]>>>24-8*((q+1)%4)&255)<<8|n[q+2>>>2]>>>24-8*((q+2)%4)&255,v=0;4>v&&q+0.75*v<l;v++)d.push(s.charAt(w>>>6*(3-v)&63));if(n=s.charAt(64))for(;d.length%4;)d.push(n);return d.join("")},parse:function(d){var n=d.length,p=this._map,s=p.charAt(64);s&&(s=d.indexOf(s),-1!=s&&(n=s));for(var s=[],q=0,w=0;w<
n;w++)if(w%4){var v=p.indexOf(d.charAt(w-1))<<2*(w%4),b=p.indexOf(d.charAt(w))>>>6-2*(w%4);s[q>>>2]|=(v|b)<<24-8*(q%4);q++}return l.create(s,q)},_map:"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="}})();
(function(u){function l(b,e,a,c,m,f,t){b=b+(e&a|~e&c)+m+t;return(b<<f|b>>>32-f)+e}function d(b,e,a,c,m,f,t){b=b+(e&c|a&~c)+m+t;return(b<<f|b>>>32-f)+e}function n(b,e,a,c,m,f,t){b=b+(e^a^c)+m+t;return(b<<f|b>>>32-f)+e}function p(b,e,a,c,m,f,t){b=b+(a^(e|~c))+m+t;return(b<<f|b>>>32-f)+e}for(var s=CryptoJS,q=s.lib,w=q.WordArray,v=q.Hasher,q=s.algo,b=[],x=0;64>x;x++)b[x]=4294967296*u.abs(u.sin(x+1))|0;q=q.MD5=v.extend({_doReset:function(){this._hash=new w.init([1732584193,4023233417,2562383102,271733878])},
_doProcessBlock:function(r,e){for(var a=0;16>a;a++){var c=e+a,m=r[c];r[c]=(m<<8|m>>>24)&16711935|(m<<24|m>>>8)&4278255360}var a=this._hash.words,c=r[e+0],m=r[e+1],f=r[e+2],t=r[e+3],y=r[e+4],q=r[e+5],s=r[e+6],w=r[e+7],v=r[e+8],u=r[e+9],x=r[e+10],z=r[e+11],A=r[e+12],B=r[e+13],C=r[e+14],D=r[e+15],g=a[0],h=a[1],j=a[2],k=a[3],g=l(g,h,j,k,c,7,b[0]),k=l(k,g,h,j,m,12,b[1]),j=l(j,k,g,h,f,17,b[2]),h=l(h,j,k,g,t,22,b[3]),g=l(g,h,j,k,y,7,b[4]),k=l(k,g,h,j,q,12,b[5]),j=l(j,k,g,h,s,17,b[6]),h=l(h,j,k,g,w,22,b[7]),
g=l(g,h,j,k,v,7,b[8]),k=l(k,g,h,j,u,12,b[9]),j=l(j,k,g,h,x,17,b[10]),h=l(h,j,k,g,z,22,b[11]),g=l(g,h,j,k,A,7,b[12]),k=l(k,g,h,j,B,12,b[13]),j=l(j,k,g,h,C,17,b[14]),h=l(h,j,k,g,D,22,b[15]),g=d(g,h,j,k,m,5,b[16]),k=d(k,g,h,j,s,9,b[17]),j=d(j,k,g,h,z,14,b[18]),h=d(h,j,k,g,c,20,b[19]),g=d(g,h,j,k,q,5,b[20]),k=d(k,g,h,j,x,9,b[21]),j=d(j,k,g,h,D,14,b[22]),h=d(h,j,k,g,y,20,b[23]),g=d(g,h,j,k,u,5,b[24]),k=d(k,g,h,j,C,9,b[25]),j=d(j,k,g,h,t,14,b[26]),h=d(h,j,k,g,v,20,b[27]),g=d(g,h,j,k,B,5,b[28]),k=d(k,g,
h,j,f,9,b[29]),j=d(j,k,g,h,w,14,b[30]),h=d(h,j,k,g,A,20,b[31]),g=n(g,h,j,k,q,4,b[32]),k=n(k,g,h,j,v,11,b[33]),j=n(j,k,g,h,z,16,b[34]),h=n(h,j,k,g,C,23,b[35]),g=n(g,h,j,k,m,4,b[36]),k=n(k,g,h,j,y,11,b[37]),j=n(j,k,g,h,w,16,b[38]),h=n(h,j,k,g,x,23,b[39]),g=n(g,h,j,k,B,4,b[40]),k=n(k,g,h,j,c,11,b[41]),j=n(j,k,g,h,t,16,b[42]),h=n(h,j,k,g,s,23,b[43]),g=n(g,h,j,k,u,4,b[44]),k=n(k,g,h,j,A,11,b[45]),j=n(j,k,g,h,D,16,b[46]),h=n(h,j,k,g,f,23,b[47]),g=p(g,h,j,k,c,6,b[48]),k=p(k,g,h,j,w,10,b[49]),j=p(j,k,g,h,
C,15,b[50]),h=p(h,j,k,g,q,21,b[51]),g=p(g,h,j,k,A,6,b[52]),k=p(k,g,h,j,t,10,b[53]),j=p(j,k,g,h,x,15,b[54]),h=p(h,j,k,g,m,21,b[55]),g=p(g,h,j,k,v,6,b[56]),k=p(k,g,h,j,D,10,b[57]),j=p(j,k,g,h,s,15,b[58]),h=p(h,j,k,g,B,21,b[59]),g=p(g,h,j,k,y,6,b[60]),k=p(k,g,h,j,z,10,b[61]),j=p(j,k,g,h,f,15,b[62]),h=p(h,j,k,g,u,21,b[63]);a[0]=a[0]+g|0;a[1]=a[1]+h|0;a[2]=a[2]+j|0;a[3]=a[3]+k|0},_doFinalize:function(){var b=this._data,e=b.words,a=8*this._nDataBytes,c=8*b.sigBytes;e[c>>>5]|=128<<24-c%32;var m=u.floor(a/
4294967296);e[(c+64>>>9<<4)+15]=(m<<8|m>>>24)&16711935|(m<<24|m>>>8)&4278255360;e[(c+64>>>9<<4)+14]=(a<<8|a>>>24)&16711935|(a<<24|a>>>8)&4278255360;b.sigBytes=4*(e.length+1);this._process();b=this._hash;e=b.words;for(a=0;4>a;a++)c=e[a],e[a]=(c<<8|c>>>24)&16711935|(c<<24|c>>>8)&4278255360;return b},clone:function(){var b=v.clone.call(this);b._hash=this._hash.clone();return b}});s.MD5=v._createHelper(q);s.HmacMD5=v._createHmacHelper(q)})(Math);
(function(){var u=CryptoJS,l=u.lib,d=l.Base,n=l.WordArray,l=u.algo,p=l.EvpKDF=d.extend({cfg:d.extend({keySize:4,hasher:l.MD5,iterations:1}),init:function(d){this.cfg=this.cfg.extend(d)},compute:function(d,l){for(var p=this.cfg,v=p.hasher.create(),b=n.create(),u=b.words,r=p.keySize,p=p.iterations;u.length<r;){e&&v.update(e);var e=v.update(d).finalize(l);v.reset();for(var a=1;a<p;a++)e=v.finalize(e),v.reset();b.concat(e)}b.sigBytes=4*r;return b}});u.EvpKDF=function(d,l,n){return p.create(n).compute(d,
l)}})();
CryptoJS.lib.Cipher||function(u){var l=CryptoJS,d=l.lib,n=d.Base,p=d.WordArray,s=d.BufferedBlockAlgorithm,q=l.enc.Base64,w=l.algo.EvpKDF,v=d.Cipher=s.extend({cfg:n.extend(),createEncryptor:function(m,a){return this.create(this._ENC_XFORM_MODE,m,a)},createDecryptor:function(m,a){return this.create(this._DEC_XFORM_MODE,m,a)},init:function(m,a,b){this.cfg=this.cfg.extend(b);this._xformMode=m;this._key=a;this.reset()},reset:function(){s.reset.call(this);this._doReset()},process:function(a){this._append(a);return this._process()},
finalize:function(a){a&&this._append(a);return this._doFinalize()},keySize:4,ivSize:4,_ENC_XFORM_MODE:1,_DEC_XFORM_MODE:2,_createHelper:function(m){return{encrypt:function(f,b,e){return("string"==typeof b?c:a).encrypt(m,f,b,e)},decrypt:function(f,b,e){return("string"==typeof b?c:a).decrypt(m,f,b,e)}}}});d.StreamCipher=v.extend({_doFinalize:function(){return this._process(!0)},blockSize:1});var b=l.mode={},x=function(a,f,b){var c=this._iv;c?this._iv=u:c=this._prevBlock;for(var e=0;e<b;e++)a[f+e]^=
c[e]},r=(d.BlockCipherMode=n.extend({createEncryptor:function(a,f){return this.Encryptor.create(a,f)},createDecryptor:function(a,f){return this.Decryptor.create(a,f)},init:function(a,f){this._cipher=a;this._iv=f}})).extend();r.Encryptor=r.extend({processBlock:function(a,f){var b=this._cipher,c=b.blockSize;x.call(this,a,f,c);b.encryptBlock(a,f);this._prevBlock=a.slice(f,f+c)}});r.Decryptor=r.extend({processBlock:function(a,b){var c=this._cipher,e=c.blockSize,d=a.slice(b,b+e);c.decryptBlock(a,b);x.call(this,
a,b,e);this._prevBlock=d}});b=b.CBC=r;r=(l.pad={}).Pkcs7={pad:function(a,b){for(var c=4*b,c=c-a.sigBytes%c,e=c<<24|c<<16|c<<8|c,d=[],l=0;l<c;l+=4)d.push(e);c=p.create(d,c);a.concat(c)},unpad:function(a){a.sigBytes-=a.words[a.sigBytes-1>>>2]&255}};d.BlockCipher=v.extend({cfg:v.cfg.extend({mode:b,padding:r}),reset:function(){v.reset.call(this);var a=this.cfg,c=a.iv,a=a.mode;if(this._xformMode==this._ENC_XFORM_MODE)var b=a.createEncryptor;else b=a.createDecryptor,this._minBufferSize=1;this._mode=b.call(a,
this,c&&c.words)},_doProcessBlock:function(a,c){this._mode.processBlock(a,c)},_doFinalize:function(){var a=this.cfg.padding;if(this._xformMode==this._ENC_XFORM_MODE){a.pad(this._data,this.blockSize);var c=this._process(!0)}else c=this._process(!0),a.unpad(c);return c},blockSize:4});var e=d.CipherParams=n.extend({init:function(a){this.mixIn(a)},toString:function(a){return(a||this.formatter).stringify(this)}}),b=(l.format={}).OpenSSL={stringify:function(a){var c=a.ciphertext;a=a.salt;return(a?p.create([1398893684,
1701076831]).concat(a).concat(c):c).toString(q)},parse:function(a){a=q.parse(a);var c=a.words;if(1398893684==c[0]&&1701076831==c[1]){var b=p.create(c.slice(2,4));c.splice(0,4);a.sigBytes-=16}return e.create({ciphertext:a,salt:b})}},a=d.SerializableCipher=n.extend({cfg:n.extend({format:b}),encrypt:function(a,c,b,d){d=this.cfg.extend(d);var l=a.createEncryptor(b,d);c=l.finalize(c);l=l.cfg;return e.create({ciphertext:c,key:b,iv:l.iv,algorithm:a,mode:l.mode,padding:l.padding,blockSize:a.blockSize,formatter:d.format})},
decrypt:function(a,c,b,e){e=this.cfg.extend(e);c=this._parse(c,e.format);return a.createDecryptor(b,e).finalize(c.ciphertext)},_parse:function(a,c){return"string"==typeof a?c.parse(a,this):a}}),l=(l.kdf={}).OpenSSL={execute:function(a,c,b,d){d||(d=p.random(8));a=w.create({keySize:c+b}).compute(a,d);b=p.create(a.words.slice(c),4*b);a.sigBytes=4*c;return e.create({key:a,iv:b,salt:d})}},c=d.PasswordBasedCipher=a.extend({cfg:a.cfg.extend({kdf:l}),encrypt:function(c,b,e,d){d=this.cfg.extend(d);e=d.kdf.execute(e,
c.keySize,c.ivSize);d.iv=e.iv;c=a.encrypt.call(this,c,b,e.key,d);c.mixIn(e);return c},decrypt:function(c,b,e,d){d=this.cfg.extend(d);b=this._parse(b,d.format);e=d.kdf.execute(e,c.keySize,c.ivSize,b.salt);d.iv=e.iv;return a.decrypt.call(this,c,b,e.key,d)}})}();
(function(){function u(b,a){var c=(this._lBlock>>>b^this._rBlock)&a;this._rBlock^=c;this._lBlock^=c<<b}function l(b,a){var c=(this._rBlock>>>b^this._lBlock)&a;this._lBlock^=c;this._rBlock^=c<<b}var d=CryptoJS,n=d.lib,p=n.WordArray,n=n.BlockCipher,s=d.algo,q=[57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35,27,19,11,3,60,52,44,36,63,55,47,39,31,23,15,7,62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,28,20,12,4],w=[14,17,11,24,1,5,3,28,15,6,21,10,23,19,12,4,26,8,16,7,27,20,13,2,41,52,31,37,47,
55,30,40,51,45,33,48,44,49,39,56,34,53,46,42,50,36,29,32],v=[1,2,4,6,8,10,12,14,15,17,19,21,23,25,27,28],b=[{"0":8421888,268435456:32768,536870912:8421378,805306368:2,1073741824:512,1342177280:8421890,1610612736:8389122,1879048192:8388608,2147483648:514,2415919104:8389120,2684354560:33280,2952790016:8421376,3221225472:32770,3489660928:8388610,3758096384:0,4026531840:33282,134217728:0,402653184:8421890,671088640:33282,939524096:32768,1207959552:8421888,1476395008:512,1744830464:8421378,2013265920:2,
2281701376:8389120,2550136832:33280,2818572288:8421376,3087007744:8389122,3355443200:8388610,3623878656:32770,3892314112:514,4160749568:8388608,1:32768,268435457:2,536870913:8421888,805306369:8388608,1073741825:8421378,1342177281:33280,1610612737:512,1879048193:8389122,2147483649:8421890,2415919105:8421376,2684354561:8388610,2952790017:33282,3221225473:514,3489660929:8389120,3758096385:32770,4026531841:0,134217729:8421890,402653185:8421376,671088641:8388608,939524097:512,1207959553:32768,1476395009:8388610,
1744830465:2,2013265921:33282,2281701377:32770,2550136833:8389122,2818572289:514,3087007745:8421888,3355443201:8389120,3623878657:0,3892314113:33280,4160749569:8421378},{"0":1074282512,16777216:16384,33554432:524288,50331648:1074266128,67108864:1073741840,83886080:1074282496,100663296:1073758208,117440512:16,134217728:540672,150994944:1073758224,167772160:1073741824,184549376:540688,201326592:524304,218103808:0,234881024:16400,251658240:1074266112,8388608:1073758208,25165824:540688,41943040:16,58720256:1073758224,
75497472:1074282512,92274688:1073741824,109051904:524288,125829120:1074266128,142606336:524304,159383552:0,176160768:16384,192937984:1074266112,209715200:1073741840,226492416:540672,243269632:1074282496,260046848:16400,268435456:0,285212672:1074266128,301989888:1073758224,318767104:1074282496,335544320:1074266112,352321536:16,369098752:540688,385875968:16384,402653184:16400,419430400:524288,436207616:524304,452984832:1073741840,469762048:540672,486539264:1073758208,503316480:1073741824,520093696:1074282512,
276824064:540688,293601280:524288,310378496:1074266112,327155712:16384,343932928:1073758208,360710144:1074282512,377487360:16,394264576:1073741824,411041792:1074282496,427819008:1073741840,444596224:1073758224,461373440:524304,478150656:0,494927872:16400,511705088:1074266128,528482304:540672},{"0":260,1048576:0,2097152:67109120,3145728:65796,4194304:65540,5242880:67108868,6291456:67174660,7340032:67174400,8388608:67108864,9437184:67174656,10485760:65792,11534336:67174404,12582912:67109124,13631488:65536,
14680064:4,15728640:256,524288:67174656,1572864:67174404,2621440:0,3670016:67109120,4718592:67108868,5767168:65536,6815744:65540,7864320:260,8912896:4,9961472:256,11010048:67174400,12058624:65796,13107200:65792,14155776:67109124,15204352:67174660,16252928:67108864,16777216:67174656,17825792:65540,18874368:65536,19922944:67109120,20971520:256,22020096:67174660,23068672:67108868,24117248:0,25165824:67109124,26214400:67108864,27262976:4,28311552:65792,29360128:67174400,30408704:260,31457280:65796,32505856:67174404,
17301504:67108864,18350080:260,19398656:67174656,20447232:0,21495808:65540,22544384:67109120,23592960:256,24641536:67174404,25690112:65536,26738688:67174660,27787264:65796,28835840:67108868,29884416:67109124,30932992:67174400,31981568:4,33030144:65792},{"0":2151682048,65536:2147487808,131072:4198464,196608:2151677952,262144:0,327680:4198400,393216:2147483712,458752:4194368,524288:2147483648,589824:4194304,655360:64,720896:2147487744,786432:2151678016,851968:4160,917504:4096,983040:2151682112,32768:2147487808,
98304:64,163840:2151678016,229376:2147487744,294912:4198400,360448:2151682112,425984:0,491520:2151677952,557056:4096,622592:2151682048,688128:4194304,753664:4160,819200:2147483648,884736:4194368,950272:4198464,1015808:2147483712,1048576:4194368,1114112:4198400,1179648:2147483712,1245184:0,1310720:4160,1376256:2151678016,1441792:2151682048,1507328:2147487808,1572864:2151682112,1638400:2147483648,1703936:2151677952,1769472:4198464,1835008:2147487744,1900544:4194304,1966080:64,2031616:4096,1081344:2151677952,
1146880:2151682112,1212416:0,1277952:4198400,1343488:4194368,1409024:2147483648,1474560:2147487808,1540096:64,1605632:2147483712,1671168:4096,1736704:2147487744,1802240:2151678016,1867776:4160,1933312:2151682048,1998848:4194304,2064384:4198464},{"0":128,4096:17039360,8192:262144,12288:536870912,16384:537133184,20480:16777344,24576:553648256,28672:262272,32768:16777216,36864:537133056,40960:536871040,45056:553910400,49152:553910272,53248:0,57344:17039488,61440:553648128,2048:17039488,6144:553648256,
10240:128,14336:17039360,18432:262144,22528:537133184,26624:553910272,30720:536870912,34816:537133056,38912:0,43008:553910400,47104:16777344,51200:536871040,55296:553648128,59392:16777216,63488:262272,65536:262144,69632:128,73728:536870912,77824:553648256,81920:16777344,86016:553910272,90112:537133184,94208:16777216,98304:553910400,102400:553648128,106496:17039360,110592:537133056,114688:262272,118784:536871040,122880:0,126976:17039488,67584:553648256,71680:16777216,75776:17039360,79872:537133184,
83968:536870912,88064:17039488,92160:128,96256:553910272,100352:262272,104448:553910400,108544:0,112640:553648128,116736:16777344,120832:262144,124928:537133056,129024:536871040},{"0":268435464,256:8192,512:270532608,768:270540808,1024:268443648,1280:2097152,1536:2097160,1792:268435456,2048:0,2304:268443656,2560:2105344,2816:8,3072:270532616,3328:2105352,3584:8200,3840:270540800,128:270532608,384:270540808,640:8,896:2097152,1152:2105352,1408:268435464,1664:268443648,1920:8200,2176:2097160,2432:8192,
2688:268443656,2944:270532616,3200:0,3456:270540800,3712:2105344,3968:268435456,4096:268443648,4352:270532616,4608:270540808,4864:8200,5120:2097152,5376:268435456,5632:268435464,5888:2105344,6144:2105352,6400:0,6656:8,6912:270532608,7168:8192,7424:268443656,7680:270540800,7936:2097160,4224:8,4480:2105344,4736:2097152,4992:268435464,5248:268443648,5504:8200,5760:270540808,6016:270532608,6272:270540800,6528:270532616,6784:8192,7040:2105352,7296:2097160,7552:0,7808:268435456,8064:268443656},{"0":1048576,
16:33555457,32:1024,48:1049601,64:34604033,80:0,96:1,112:34603009,128:33555456,144:1048577,160:33554433,176:34604032,192:34603008,208:1025,224:1049600,240:33554432,8:34603009,24:0,40:33555457,56:34604032,72:1048576,88:33554433,104:33554432,120:1025,136:1049601,152:33555456,168:34603008,184:1048577,200:1024,216:34604033,232:1,248:1049600,256:33554432,272:1048576,288:33555457,304:34603009,320:1048577,336:33555456,352:34604032,368:1049601,384:1025,400:34604033,416:1049600,432:1,448:0,464:34603008,480:33554433,
496:1024,264:1049600,280:33555457,296:34603009,312:1,328:33554432,344:1048576,360:1025,376:34604032,392:33554433,408:34603008,424:0,440:34604033,456:1049601,472:1024,488:33555456,504:1048577},{"0":134219808,1:131072,2:134217728,3:32,4:131104,5:134350880,6:134350848,7:2048,8:134348800,9:134219776,10:133120,11:134348832,12:2080,13:0,14:134217760,15:133152,2147483648:2048,2147483649:134350880,2147483650:134219808,2147483651:134217728,2147483652:134348800,2147483653:133120,2147483654:133152,2147483655:32,
2147483656:134217760,2147483657:2080,2147483658:131104,2147483659:134350848,2147483660:0,2147483661:134348832,2147483662:134219776,2147483663:131072,16:133152,17:134350848,18:32,19:2048,20:134219776,21:134217760,22:134348832,23:131072,24:0,25:131104,26:134348800,27:134219808,28:134350880,29:133120,30:2080,31:134217728,2147483664:131072,2147483665:2048,2147483666:134348832,2147483667:133152,2147483668:32,2147483669:134348800,2147483670:134217728,2147483671:134219808,2147483672:134350880,2147483673:134217760,
2147483674:134219776,2147483675:0,2147483676:133120,2147483677:2080,2147483678:131104,2147483679:134350848}],x=[4160749569,528482304,33030144,2064384,129024,8064,504,2147483679],r=s.DES=n.extend({_doReset:function(){for(var b=this._key.words,a=[],c=0;56>c;c++){var d=q[c]-1;a[c]=b[d>>>5]>>>31-d%32&1}b=this._subKeys=[];for(d=0;16>d;d++){for(var f=b[d]=[],l=v[d],c=0;24>c;c++)f[c/6|0]|=a[(w[c]-1+l)%28]<<31-c%6,f[4+(c/6|0)]|=a[28+(w[c+24]-1+l)%28]<<31-c%6;f[0]=f[0]<<1|f[0]>>>31;for(c=1;7>c;c++)f[c]>>>=
4*(c-1)+3;f[7]=f[7]<<5|f[7]>>>27}a=this._invSubKeys=[];for(c=0;16>c;c++)a[c]=b[15-c]},encryptBlock:function(b,a){this._doCryptBlock(b,a,this._subKeys)},decryptBlock:function(b,a){this._doCryptBlock(b,a,this._invSubKeys)},_doCryptBlock:function(e,a,c){this._lBlock=e[a];this._rBlock=e[a+1];u.call(this,4,252645135);u.call(this,16,65535);l.call(this,2,858993459);l.call(this,8,16711935);u.call(this,1,1431655765);for(var d=0;16>d;d++){for(var f=c[d],n=this._lBlock,p=this._rBlock,q=0,r=0;8>r;r++)q|=b[r][((p^
f[r])&x[r])>>>0];this._lBlock=p;this._rBlock=n^q}c=this._lBlock;this._lBlock=this._rBlock;this._rBlock=c;u.call(this,1,1431655765);l.call(this,8,16711935);l.call(this,2,858993459);u.call(this,16,65535);u.call(this,4,252645135);e[a]=this._lBlock;e[a+1]=this._rBlock},keySize:2,ivSize:2,blockSize:2});d.DES=n._createHelper(r);s=s.TripleDES=n.extend({_doReset:function(){var b=this._key.words;this._des1=r.createEncryptor(p.create(b.slice(0,2)));this._des2=r.createEncryptor(p.create(b.slice(2,4)));this._des3=
r.createEncryptor(p.create(b.slice(4,6)))},encryptBlock:function(b,a){this._des1.encryptBlock(b,a);this._des2.decryptBlock(b,a);this._des3.encryptBlock(b,a)},decryptBlock:function(b,a){this._des3.decryptBlock(b,a);this._des2.encryptBlock(b,a);this._des1.decryptBlock(b,a)},keySize:6,ivSize:2,blockSize:2});d.TripleDES=n._createHelper(s)})();

CryptoJS.mode.ECB=function(){var a=CryptoJS.lib.BlockCipherMode.extend();a.Encryptor=a.extend({processBlock:function(a,b){this._cipher.encryptBlock(a,b)}});a.Decryptor=a.extend({processBlock:function(a,b){this._cipher.decryptBlock(a,b)}});return a}();
(function(){var C=CryptoJS;var j=C.lib;var k=j.Base;var l=C.enc;var m=l.Utf8;var n=C.algo;var o=n.HMAC=k.extend({init:function(a,b){a=this._hasher=new a.init();if(typeof b=='string'){b=m.parse(b)}var c=a.blockSize;var d=c*4;if(b.sigBytes>d){b=a.finalize(b)}b.clamp();var e=this._oKey=b.clone();var f=this._iKey=b.clone();var g=e.words;var h=f.words;for(var i=0;i<c;i++){g[i]^=0x5c5c5c5c;h[i]^=0x36363636}e.sigBytes=f.sigBytes=d;this.reset()},reset:function(){var a=this._hasher;a.reset();a.update(this._iKey)},update:function(a){this._hasher.update(a);return this},finalize:function(a){var b=this._hasher;var c=b.finalize(a);b.reset();var d=b.finalize(this._oKey.clone().concat(c));return d}})}());(function(u){var C=CryptoJS;var v=C.lib;var w=v.WordArray;var x=v.Hasher;var y=C.algo;var H=[];var K=[];(function(){function isPrime(n){var a=u.sqrt(n);for(var b=2;b<=a;b++){if(!(n%b)){return false}}return true}function getFractionalBits(n){return((n-(n|0))*0x100000000)|0}var n=2;var c=0;while(c<64){if(isPrime(n)){if(c<8){H[c]=getFractionalBits(u.pow(n,1/2))}K[c]=getFractionalBits(u.pow(n,1/3));c++}n++}}());var W=[];var z=y.SHA256=x.extend({_doReset:function(){this._hash=new w.init(H.slice(0))},_doProcessBlock:function(M,j){var H=this._hash.words;var a=H[0];var b=H[1];var c=H[2];var d=H[3];var e=H[4];var f=H[5];var g=H[6];var h=H[7];for(var i=0;i<64;i++){if(i<16){W[i]=M[j+i]|0}else{var k=W[i-15];var l=((k<<25)|(k>>>7))^((k<<14)|(k>>>18))^(k>>>3);var m=W[i-2];var n=((m<<15)|(m>>>17))^((m<<13)|(m>>>19))^(m>>>10);W[i]=l+W[i-7]+n+W[i-16]}var o=(e&f)^(~e&g);var p=(a&b)^(a&c)^(b&c);var q=((a<<30)|(a>>>2))^((a<<19)|(a>>>13))^((a<<10)|(a>>>22));var r=((e<<26)|(e>>>6))^((e<<21)|(e>>>11))^((e<<7)|(e>>>25));var s=h+r+o+K[i]+W[i];var t=q+p;h=g;g=f;f=e;e=(d+s)|0;d=c;c=b;b=a;a=(s+t)|0}H[0]=(H[0]+a)|0;H[1]=(H[1]+b)|0;H[2]=(H[2]+c)|0;H[3]=(H[3]+d)|0;H[4]=(H[4]+e)|0;H[5]=(H[5]+f)|0;H[6]=(H[6]+g)|0;H[7]=(H[7]+h)|0},_doFinalize:function(){var a=this._data;var b=a.words;var c=this._nDataBytes*8;var d=a.sigBytes*8;b[d>>>5]|=0x80<<(24-d%32);b[(((d+64)>>>9)<<4)+14]=u.floor(c/0x100000000);b[(((d+64)>>>9)<<4)+15]=c;a.sigBytes=b.length*4;this._process();return this._hash},clone:function(){var a=x.clone.call(this);a._hash=this._hash.clone();return a}});C.SHA256=x._createHelper(z);C.HmacSHA256=x._createHmacHelper(z)}(Math));

// 广东地方台（来源恩山论坛）荔枝网 v3接口
// gdes://134
function gdesParser(originUrl) {
    function getNode() {
        var url = "https://tcdn-api.itouchtv.cn/getParam?fromSource=wxapp";
        var header = getHeader(url);
        var data = urlRequestWithParaAndCoding(url, header, 'utf-8');
        var node = JSON.parse(data).node;
        return node;
    }
    function getUrl(node) {
        var url = 'https://api.itouchtv.cn/liveservice/v3/tvChannelList?fromSource=wxapp&node=' + node;
        var header = getHeader(url)
        var data1 = urlRequestWithParaAndCoding(url, header, 'utf-8');
        var a = JSON.parse(data1).tvChannelList;
        for (var k in a) {
            if (a[k].pk == id) {
                return a[k].url;
            }
        }
        return '';
    }
    function getWXDeviceID() {
        for (var t = [], r = new Date().getTime().toString(), n = 0; n < 36; n++) {
            t[n] = r.substr(Math.floor(16 * Math.random()), 1);
        }
        t[14] = "4", t[19] = r.substr(3 & t[19] | 8, 1), t[8] = t[13] = t[18] = t[23] = "-";
        return "WXAPP_" + t.join("");
    }
    function getHeader(url) {
        var timestamp = new Date().getTime();
        var key = 'HGXimfS2hcAeWbsCW19JQ7PDasYOgg1lY2UWUDVX8nNmwr6aSaFznnPzKrZ84VY1';
        var str = "GET" + "\n" + url + "\n" + timestamp + "\n";
        var sign = CryptoJS.enc.Base64.stringify(CryptoJS.HmacSHA256(str, key, true));
        var header = [
            {header: 'X-ITOUCHTV-APP-VERSION',value: '3.3.1'},
            {header: 'X-ITOUCHTV-Ca-Timestamp',value: timestamp.toString()},
            {header: 'X-ITOUCHTV-Ca-Signature',value: sign},
            {header: 'X-ITOUCHTV-DEVICE-ID',value: getWXDeviceID()},
            {header: 'X-ITOUCHTV-Ca-Key',value: '28778826534697375418351580924221'},
            {header: 'X-ITOUCHTV-CLIENT',value: 'WX_APP'},
            {header: 'User-Agent',value: 'Mozilla/5.0 (iPhone; CPU iPhone OS 15_6 like Mac OS X)'},
            {header: 'Referer',value: 'https://servicewechat.com/wxc5018ab64f4f2da5/17/page-frame.html'},
        ]
        return header;
    }
    var result = [];
    var id = originUrl.split('//')[1];
    var node = getNode();
    var url = getUrl(node);
    result.push(url);
    return result;
}

// 江苏卫视官网
// jsgw://jsws
function jsgwParser(originUrl) {
    var result = [];
    var id = originUrl.split('//')[1];
    var a = '/livezhuzhan/' + id + '.m3u8'
    var r = '8f6f55fb8ffbfd555aa29a9a1426a';
    // var i = Number(((new Date).getTime() / 1e3).toFixed()) + 5;
    var i = Number(((new Date).getTime() / 1e3).toFixed()) + (60*60*24);
    var s = r + "&" + i + "&" + a;
    var o = CryptoJS.MD5(s).toString().substring(12, 20);
    var d = o + i;
    var url = 'https://live-hls.jstv.com/livezhuzhan/' + id + '.m3u8?upt=' + d+'@@Referer=http://live.jstv.com/@User-Agent=Mozilla/5.0@@';
    result.push(url);
	return result;
}

// 江苏卫视等地方台回看
// https://live-hls.jstv.com/livezhuzhan/jsws.m3u8?starttime=1650240270&endtime=1650242683&upt=edfeaaf91650265557
// http://xproxy.yuntuds.com/lk/xjyx.m3u8?&id=jsws&?start=1650254400&end=1650258000
function jsgwPlayerd(originUrl) {
    var result = [];
    var originUrl = originUrl +"&"
    var id = getStringBtween(originUrl,"&id=","&");
    var starttime = getStringBtween(originUrl,"&?start=","&");
    var endtime = getStringBtween(originUrl,"&end=","&");
    var a = '/livezhuzhan/' + id + '.m3u8'
    var r = 'jstvlivezhuzhan@2022cdn!@#124gg';
    var i = Number(((new Date).getTime() / 1e3).toFixed()) + 300;
    var s = r + "&" + i + "&" + a;
    var o = CryptoJS.MD5(s).toString();
    var l = o.substring(12, 20);
    var d = l + i;
    var url = 'https://live-hls.jstv.com/livezhuzhan/' + id + '.m3u8?starttime=' + starttime + '&endtime=' + endtime + '&upt=' + d;
    result.push(url);
    return result;
}

// 浙江卫视回看
// http://xproxy.yuntuds.com/lk/xjyx.m3u8?&id=jsws&tvod=http://hw-m-l.cztv.com/channels/lantian/channel001/1080p.m3u8&?start=1650254400&end=1650258000
function zjgwPlayerd(originUrl) {
    originUrl = originUrl +"&"
    var tvod = getStringBtween(originUrl,"&tvod=","&");
    var starttime = getStringBtween(originUrl,"&?start=","&")+'000';
    var endtime = getStringBtween(originUrl,"&end=","&")+'000';
    var url = tvod + '/' + starttime + ',' + endtime;
    return [url];
}

// sddfParser 
// sddf://sdws//0   0山东热门地方台 1其他山东地方台 变化规律不一
var sddfParser = function (originUrl) {
  var result = [];
  var id = originUrl.split('//')[1];
  var tvIdList = {
    sdtv: "24581", // 山东卫视
    qlpd: "24584", // 山东齐鲁
    typd: "24587", // 山东体育
    shpd: "24596", // 山东生活
    zypd: "24593", // 山东综艺
    nkpd: "24599", // 山东农科
    yspd: "24590", // 山东文旅（原wlpd，页面代号为yspd）
    sepd: "24605", // 山东少儿
    ggpd: "24602"  // 山东新闻
  };
  var pdCid = tvIdList[id];
  if (!pdCid) return [];

  var postHeader = [];
  postHeader.push({ header: "Referer", value: "https://v.iqilu.com/" });
  postHeader.push({ header: "User-Agent", value: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" });
  postHeader.push({ header: "Content-Type", value: "text/plain" });

  var htmlContent = urlRequestWithParaAndCoding('https://v.iqilu.com/live/sdtv/index.html', postHeader, 'utf-8');
  var mxpxM = htmlContent.match(/var mxpx = '([^']+)';/);
  var alyM = htmlContent.match(/var aly = '([^']+)';/);
  if (!mxpxM || !alyM) return [];
  var mxpx = mxpxM[1];
  var key = alyM[1];

  var t = Date.now();
  var s = CryptoJS.MD5(String(pdCid) + String(t) + String(mxpx)).toString();
  var postUrl = 'https://feiying.litenews.cn/api/v1/auth/exchange?t=' + t + '&s=' + s;
  var postData = aesEncrypt(JSON.stringify({ channelMark: pdCid }), key);
  var encipheredData = postWithHeaders(postUrl, postData, postHeader, "text/plain");
  var obj = nextF(encipheredData);
  if (!obj || !obj.data) return [];
  return [obj.data + "@@Referer=https://v.iqilu.com/@User-Agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64)@@"];

  function nextF(str) {
    var parsed = null;
    try {
      parsed = JSON.parse(aesDecrypt(str));
    } catch (e) {
      parsed = null;
    }
    return parsed;
  }

  function aesEncrypt(word, key) {
    if (!word) return "";
    return CryptoJS.AES.encrypt(word, CryptoJS.enc.Utf8.parse(key), {
      iv: CryptoJS.enc.Utf8.parse("0000000000000000"),
      mode: CryptoJS.mode.CBC,
      padding: CryptoJS.pad.Pkcs7
    }).toString();
  }

  function aesDecrypt(word) {
    return CryptoJS.AES.decrypt(word, CryptoJS.enc.Utf8.parse(key), {
      iv: CryptoJS.enc.Utf8.parse("0000000000000000"),
      mode: CryptoJS.mode.CBC,
      padding: CryptoJS.pad.Pkcs7
    }).toString(CryptoJS.enc.Utf8);
  }
};
// 山东卫视回看
function sdgwPlayerd(originUrl) {
  originUrl = originUrl + "&"
  var starttime = getStringBtween(originUrl, "&?start=", "&");
  var endtime = getStringBtween(originUrl, "&end=", "&");
  var getUrl = 'https://sdxw.iqilu.com/v1/app/play/look/back?start=' + starttime + '&end=' + endtime + '&channelid=1&orgid=21';
  var header = [];
  header.push({ header: "referrer", value: "https://v.iqilu.com/" });
  header.push({ header: "User-Agent", value: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" });
  var data = urlRequestWithParaAndCoding(getUrl, header, 'utf-8');
  var url = JSON.parse(data).data.url + "@@Referer=https://v.iqilu.com/@User-Agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64)@@"
  return [url];
}
// 安徽卫视官网
// anhui://73
function ahgwParser(originUrl){
	var id = originUrl.split('//')[1];
	var url = 'https://mapi.ahtv.cn/api/open/ahtv/program.php?appid=m2otdjzyuuu8bcccnq&appkey=5eab6b4e1969a8f9aef459699f0d9000&zone=0&channel_id=' + id;
	var header = [
		{
			header: 'Referer',
			value: 'https://www.ahtv.cn/'
		},
		{
			header: 'User-Agent',
			value: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'
		},
	];
	var data = urlRequestWithParaAndCoding(url, header, 'utf-8');
	var t = new Date().getHours();
	var result = [];
	var a = JSON.parse(data)
	var u = a[t].m3u8 + '@@user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64)@Referer=http://www.ahtv.cn/@@'
	result.push(u);
	return result;
}

// 东方卫视官网
function dfgwParser(originUrl) {
    var t = {
        id: "800081",
        nonce: Math.random().toString(36).slice(-8),
        platform: "pc",
        stream: "live",
        timestamp: "" + parseInt(new Date().getTime() / 1000),
        version: "1.0"
    }
    t.id = originUrl.split('//')[1];
	var o = "";
	for (var c in t) o = o + c + "=" + t[c] + "&";
	o += "28c8edde3d61a0411511d3b1866f0636";
	t.sign = CryptoJS.MD5(CryptoJS.MD5(o).toString()).toString();
	var url = 'https://api-app.kankanews.com/kankan/pc/livepc?id=' + t.id + '&nonce=' + t.nonce + '&platform=' + t.platform + '&stream=' + t.stream + '&timestamp=' + t.timestamp + '&version=' + t.version + '&sign=' + t.sign;
    var header = [
        {
            header: 'Referer',
            value: 'https://live.kankanews.com/'
        },
        {
            header: 'User-Agent',
            value: 'Mozilla/5.0 (Linux; Android 8.0; Pixel 2 Build/OPD3.170816.012) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.149 Mobile Safari/537.36'
        },
    ];
    var data = urlRequestWithParaAndCoding(url, header, 'utf-8');
    var a = JSON.parse(data);
    var b = JSON.parse(CryptoJS.enc.Utf8.stringify(CryptoJS.enc.Base64.parse(a.result.code)));
    var result = [];
    result.push(b[0].appstream);
    return result
}

// 贵州卫视官网
// gzgw://01
function gzgwParser(originUrl){
	var id = originUrl.split('//')[1];
	var url = 'https://api.gzstv.com/v1/tv/ch' + id + '/?remote_address=&fields=description,title,stream_url,image,author';
	var data = urlRequest(url);
	var U = JSON.parse(data).stream_url;
	var result = [];
    result.push(U+"@@referer=https://www.gzstv.com/@user-agent=Mozilla/5.0@@");
	return result
}
// 追光影界 zgyj://G_CCTV-1-HQ
function zgyjParsePlay(originUrl){
  var contentId = originUrl.split('//')[1];
  var testUrl = 'http://hecmcc-gvp-zy-livod.dispatcher.gitv.tv/gitv_live/' + contentId + '/' + contentId + '.m3u8?'
  var relevantParam = getPlayAuthToken(testUrl)
  var postUrl = 'https://hecmcc-gvp-zys.playauth.gitv.tv/itv/playauth?playAuthToken=' + relevantParam.playAuthToken + '&gAreaId=CHN&gAppChannel=DEFAULT&gSoftTermId=SMART_SPEAKER&gManufacturer=' + relevantParam.gManufacturer + '&gModel=' + relevantParam.gModel + '&gMac=' + relevantParam.gMac + '&gStbId=unknown&gOsType=android&gOsVersion=12&gApiLevel=32&gAppVersionCode=1551&gAppVersionName=1.5.51&gDowngrade=NONEMERGENCY&gRomVersion=DEFAULT&gProductLineCode=GVP&gAndroidId=' + relevantParam.gAndroidId + '&gTimestamp=' + relevantParam.fdormatDateTime + '&gRequests=1&gDeviceID=' + relevantParam.gStbId
	var postData = '{"contentId":"' + contentId + '","playUrl":"' + relevantParam.bodyUrl + '","stbId":"unknown","userId":"yk1234"}'
	var postHeader = [{ header: 'User-Agent', value: 'okhttp/3.8.1' }]
	var apiCont = postWithHeaders(postUrl, postData, postHeader, 'application/json')
  var info = JSON.parse(apiCont)
  var url = ''
  if(info.code == 'A000000'){
    url = info.data.playUrl
  }
  return [url]
}
// 追光视界回看 http://xproxy.yuntuds.com/lk/xjyx.m3u8?&zgyj=G_CCTV-1-HQ&?start=1625807036&end=1625809436
function zgyjPlayerd(originUrl){
	var originUrl = originUrl +"&";
	var st = getStringBtween(originUrl,"?start=","&");//开始时间
	var et = getStringBtween(originUrl,"&end=","&");//结束时间
  var contentId = getStringBtween(originUrl,"&zgyj=","&");
  var testUrl = 'http://hecmcc-gvp-zy-livod.dispatcher.gitv.tv/gitv_live/' + contentId + '/history.m3u8?start=' + st + '&end=' + et + '&'
  var relevantParam = getPlayAuthToken(testUrl)
  var postUrl = 'https://hecmcc-gvp-zys.playauth.gitv.tv/itv/playauth?playAuthToken=' + relevantParam.playAuthToken + '&gAreaId=CHN&gAppChannel=DEFAULT&gSoftTermId=SMART_SPEAKER&gManufacturer=' + relevantParam.gManufacturer + '&gModel=' + relevantParam.gModel + '&gMac=' + relevantParam.gMac + '&gStbId=unknown&gOsType=android&gOsVersion=12&gApiLevel=32&gAppVersionCode=1551&gAppVersionName=1.5.51&gDowngrade=NONEMERGENCY&gRomVersion=DEFAULT&gProductLineCode=GVP&gAndroidId=' + relevantParam.gAndroidId + '&gTimestamp=' + relevantParam.fdormatDateTime + '&gRequests=1&gDeviceID=' + relevantParam.gStbId
	var postData = '{"contentId":"' + contentId + '","playUrl":"' + relevantParam.bodyUrl + '","stbId":"unknown","userId":"yk1234"}'
	var postHeader = [{ header: 'User-Agent', value: 'okhttp/3.8.1' }]
	var apiCont = postWithHeaders(postUrl, postData, postHeader, 'application/json')
  var info = JSON.parse(apiCont)
  var url = ''
  if(info.code == 'A000000'){
    url = info.data.playUrl
  }
  return [url]
}
// 追光获取PlayAuthToken等信息
function getPlayAuthToken(testUrl) {
	var auKey =
		'754vvn74y3h0cyxdy55dn9yy1p6ulux8tr338mnf4lvpc4hxz5j0u0os0g505dwbxdoz12lyo5lb5cinrlz0zpoftuhyegnqxebld3kqrmhb8706upbbf1vybwtew5my'
	var userId = 'yk1234'
	var gMac = generateRandomMAC()
	var gStbId = generateDeviceID()
	var gAndroidId = generateAndroidId()
	var gManufacturer = generatemanufacturer()
	var gModel = generateRandomModel()
	var gTimestamp = Math.floor(Date.now() / 1000)
	var fdormatDateTime = formatDateTime(gTimestamp)
	var concatenatedList = ['GITV_GVP_HB_CMCC_GVP_ZY', '_', '120']
	var keyBaseLen = auKey.length
	var firstNum = gTimestamp % (keyBaseLen > 13 ? keyBaseLen - 13 : 1)
	var encryptKeyFirst = auKey.substring(firstNum, firstNum + 14)
	var content = concatenatedList[0] + userId + gMac + gAndroidId + gManufacturer + gModel
	var hash1 = CryptoJS.SHA256(content).toString()

	var hash1len = hash1.length
	var secondNum = gTimestamp % (hash1len > 5 ? hash1len - 5 : 1)
	var encryptKeySecond = hash1.substring(secondNum, secondNum + 14)
	var content2 = ''
	var val1 = encryptKeySecond.substring(0, 3)
	content2 += val1 + encryptKeyFirst
	var val2 = encryptKeySecond.substring(3, 6)
	content2 += val2 + concatenatedList[2]
	var finalHash = CryptoJS.SHA256(content2).toString()
	var playAuthToken = finalHash + concatenatedList[1] + gTimestamp + concatenatedList[1] + concatenatedList[2]
  var postUrl1 = testUrl+'p=GITV&area=HB_CMCC_GVP_ZY&partnerCode=HB_CMCC_GVP_ZY&token=F2190A760AE9C3C2FA9834BDFF26799A1FF810DE670F36D9A5EA85BD7AFA2FBC33E418FF3C5B9ECFE4A58897B827CC3FF27F3A560E3515CEA21AE6E5B3A09ECF204CA38C1D1A0A544A7BABB67C995E5041C54BB175E2A51A361C93A17D6724254A3C01ABEC732A655A57D44E04F902E1813AC10A394A1071A0DCC96D487B34DB992CD2C0CF600D76BE3FDDA68561CDC93C8A106A43214BB2E8FB5CE2022027DB6624942998434C0832B442CDA01A0F88&version=0.0.0.0&apkVersion=1.5.51&mac=' + gMac + '&multicast=0&gAreaId=CHN&gAppChannel=DEFAULT&gSoftTermId=SMART_SPEAKER&gManufacturer=' + gManufacturer + '&gModel=' + gModel + '&gMac=' + gMac + '&gStbId=unknown&gOsType=android&gOsVersion=12&gApiLevel=32&gAppVersionCode=1551&gAppVersionName=1.5.51&gDowngrade=NONEMERGENCY&gRomVersion=DEFAULT&gProductLineCode=GVP&gTimestamp=' + fdormatDateTime + '&gRequests=1'
	var postHeader = [{ header: 'User-Agent', value: 'okhttp/3.8.1' }]
	var apiCont = postWithHeaders(postUrl1, '', postHeader, 'application/json')
  var res = JSON.parse(apiCont)
  var bodyUrl = res.data[0].url.replace(/=/g, '\\u003d').replace(/&/g, '\\u0026')
  return {
		playAuthToken: playAuthToken,
		gMac: gMac,
		gStbId: gStbId,
		gAndroidId: gAndroidId,
		gManufacturer: gManufacturer,
		gModel: gModel,
		gTimestamp: gTimestamp,
		fdormatDateTime: fdormatDateTime,
    bodyUrl: bodyUrl
	}
}
// 电视剧
//  http://zgyj_vod/eyJkYXRhIjogW3siY2RuTmFtZSI6ICJ...
function zgyjvodParsePlay(originUrl){
  var base64Str = originUrl.replace('http://zgyj_vod/', '')
  var timestamp = Math.floor(new Date().getTime() / 1e3)
  var gTimestamp = formatDateTime(timestamp)
  var str = ''
	var key = 'fUw8mWqp0ih8KRPzmgHnoL4fx0X0N78o4Od6D4lFTV32S7uVa2mYSZjRofquHHU16gkpjvInD07bKwb05S0hlJYfYMmUyQprvoEqM2I8Dfe2oX0MTAJEg8DYDul6lJqg'
	var len = Math.floor(timestamp % (key.length - 19))
  str = key.substring(len, len + 20)
  var playAuthToken = CryptoJS.SHA256(str + 86400).toString() + '_' + timestamp + '_86400'
  var g_mac = generateRandomMAC()
  var getUrl = 'http://hecmcc-gvp-zy-vod.playauth.gitv.tv/gitv_vod_auth/' + base64Str + '?playAuthToken=' + playAuthToken + '&gAreaId=CHN&gAppChannel=DEFAULT&gSoftTermId=SMART_SPEAKER&gManufacturer=vivo&gModel=V2185A&gMac=' + g_mac + '&gStbId=unknown&gOsType=android&gOsVersion=12&gApiLevel=32&gAppVersionCode=1482&gAppVersionName=1.4.82&gDowngrade=NONEMERGENCY&gRomVersion=DEFAULT&gProductLineCode=GVP&gTimestamp=' + gTimestamp + '&gRequests=1'
	var getHeader = [{ header: 'User-Agent', value: 'okhttp/3.8.1' }]
  var apiCont = urlRequestWithParaAndCoding(getUrl,getHeader,'utf-8')
  var info = JSON.parse(apiCont)
  var url = ''
  if(info.code == 'A000000'){
    url = info.data.playUrl
  }
  return [url]
}
// MAC地址的十六进制字符
function generateRandomMAC() {
	var hexChars = '0123456789ABCDEF'
	var mac = ''
	for (var i = 0; i < 6; i++) {
		var group = ''
		for (var j = 0; j < 2; j++) {
			group += hexChars[Math.floor(Math.random() * 16)]
		}
		mac += group
		if (i < 5) {
			mac += ':'
		}
	}
	return mac
}

// 生成 DeviceID（模拟 UUID 格式）
function generateDeviceID() {
	return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
		var r = (Math.random() * 16) | 0
		var v = c === 'x' ? r : (r & 0x3) | 0x8
		return v.toString(16)
	})
}

// 生成 AndroidId（16 位十六进制字符串）
function generateAndroidId() {
	var hex = '0123456789abcdef'
	var id = ''
	for (var i = 0; i < 16; i++) {
		id += hex.charAt(Math.floor(Math.random() * hex.length))
	}
	return id
}

function generatemanufacturer() {
	var manufacturers = ['samsung', 'huawei', 'xiaomi', 'oppo', 'vivo', 'oneplus', 'google', 'sony', 'lg', 'motorola']
	return manufacturers[Math.floor(Math.random() * manufacturers.length)]
}

function generateRandomModel() {
	var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
	var len = 8 + Math.floor(Math.random() * 8) // 长度 8~15
	var result = ''
	for (var i = 0; i < len; i++) {
		result += chars.charAt(Math.floor(Math.random() * chars.length))
	}
	return result
}
// 河南卫视官网
// henan://145
function henanParse(originUrl) {
	var cid = originUrl.replace('henan://', '')
	var timestamp = Math.floor(new Date().getTime() / 1e3),
		ut = '6ca114a836ac7d73' + timestamp,
		sign = CryptoJS.SHA256(ut).toString()
	var header = [
		{
			header: 'sign',
			value: sign
		},
		{
			header: 'timestamp',
			value: timestamp.toString()
		}
	]
	var a = urlRequestWithParaAndCoding('https://pubmod.hntv.tv/program/getAuth/live/class/program/11/', header, 'utf-8')
	var data = JSON.parse(a)
	var result = []
	for (var k in data) {
		if (data[k].cid == cid) {
			result.push(data[k].video_streams[0] + '@@user-agent=Mozilla/5.0@@')
		}
	}
	return result
}

// 河南南阳
// ny://421387
function nyParse(originUrl) {
	var cid = originUrl.replace('ny://', '')
	var timestamp = Math.floor(new Date().getTime() / 1e3),
		ut = '6ca114a836ac7d73' + timestamp,
		sign = CryptoJS.SHA256(ut).toString()
	var header = [
		{
			header: 'sign',
			value: sign
		},
		{
			header: 'timestamp',
			value: timestamp.toString()
		}
	]
	var a = urlRequestWithParaAndCoding('https://ny-pubmod.nydt.cn/program/getAuth/live/class/nanyangradio/15',header,'utf-8')
	var data = JSON.parse(a)
	var result = []
	for (var k in data) {
		if (data[k].cid == cid) {
			result.push(data[k].video_streams + '@@user-agent=Mozilla/5.0@@')
		}
	}
	return result
}

// 北京卫视及地方台
// bjgw://1
function bjgwParser(originUrl) {
  var id = originUrl.split('//')[1];
  var ids = {
    "3": "573ib1kp5nk92irinpumbo9krlb",       //北京卫视
    "5": "53bn9rlalq08lmb8nf8iadoph0b",       //北京科教
    "6": "50mqo8t4n4e8gtarqr3orj9l93v",       //北京影视
    "7": "50e335k9dq488lb7jo44olp71f5",       //北京财经
    "10": "53grctge7jb8aeamggnot6fve1o",      //北京青年
    "12": "55skfjq618b9kcq9tfjr5qllb7r",      //卡酷少儿
    "13": "54db6gi5vfj8r8q1e6r89imd64s",      //北京文艺
    "14": "50j015rjrei9vmp3h8upblr41jf",      //北京生活
    "15": "53gpt1ephlp86eor6ahtkg5b2hf",      //北京新闻
    "16": "54hv0f3pq079d4oiil2k12dkvsc",      //北京体育休闲
  }
  var url = 'https://app.api.btime.com/video/play?';
  var time = parseInt((new Date).getTime() / 1e3);
  var path = 'app_ver=9.1.9&browse_mode=1&carrier=中国电信&channel=AppStore&citycode=local_110000&fontsize=2&gid=' + ids[id] + '&id=' + ids[id] + '&location_citycode=local_110000&net=wifi&nightmode=1&os=iPhone13,2&os_type=iOS&os_ver=15.6&pro=btime&protocol=2&push_id=28d8fb06238d2f25447c41a3e6380fa1&push_switch=1&screen=390_844&timestamp=' + time + '&token=8ad5d9699504e282ff16828ac37f95cc&type_id=151&ver=90109&ver_code=90109';
  var sign = CryptoJS.MD5(path + 'shi!@#$%^&*[xian!@#]*').toString().substr(3, 7);
  var req_url = url + path + '&sign=' + sign;
  req_url = req_url.replace('中国电信', encodeURIComponent('中国电信'))
  var header = [{ header: 'User-Agent', value: 'okhttp/3.9.1' }];
  var data = urlRequestWithParaAndCoding(req_url, header, 'utf-8');
  var stream_url = JSON.parse(data).data.video_stream[0].stream_url;
  return [stream_url];
}

// 四川成都地方台
// chengdu://1
function chengDuParser(originUrl) {
	var id = originUrl.split('//')[1]
	// var url = 'https://www.cditv.cn/api.php?op=live&type=playTv&fluency=sd&videotype=m3u8&catid=192&id=' + id;
	var url = 'http://mob.api.cditv.cn/show/192-' + id + '.html'
	var header = [
		{
			header: 'User-Agent',
			value: 'Mozilla/5.0'
		}
	]
	var data = urlRequestWithParaAndCoding(url, header, 'utf-8')
	var url1 = ''
	try {
		url1 = JSON.parse(data).data.android_HDlive_url.replace('http://', 'https://')
	} catch (error) {
		url1 = ''
	}
	return [url1]
}

// 四川成都地方台
// scgw://1
function scgwParser(originUrl) {
  var id = originUrl.split('//')[1];
  var tvList = {
    'scws': '1',//四川卫视
    'scjj': '3',//四川经济
    'scwhly': '4',//四川文化旅游
    'scxw': '2',//四川新闻
    'scyswy': '5',//四川影视文艺
    'scxkgw': '7',//四川星空购物
    'scfnet': '6',//四川妇女儿童
    'scxc': '8',//四川乡村
    'kbws': '9',//康巴卫视
  }
  var headers = [
    { header: 'User-Agent', value: "Mozilla/5.0 (Linux; U; Android 8.1.0; zh-cn; BLA-AL00 Build/HUAWEIBLA-AL00) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/57.0.2987.132 MQQBrowser/8.9 Mobile Safari/537.36" },
    { header: 'Referer', value: "https://www.sctv.com/" }
  ];
  var apiUrl = "http://api.vonchange.com/utao/sctv?tag=" + tvList[id]
  debugger
  var url = urlRequestWithParaAndCoding(apiUrl, headers, "utf-8");
  return [url.trim()]
}

// 新咪咕http://jhvsc.aikan.miguvideo.com:7089
// miguNew://265667329//265667330
function muGuParser(originUrl) {
  var id1 = originUrl.split('//')[1], id2 = originUrl.split('//')[2];
  var postInfo = '{"terminalType":"AndroidPhone","loginType":"3"}';
  var result = [];
  // var IP_Info =  postWithHeaders('http://aikanlive.miguvideo.com:8082/EDS/JSON/Login', postInfo,[],'application/json');
  // var IP = JSON.parse(IP_Info).rootCerAddr;
	var ips = [7081, 7082, 7083];
  var randomIndex = Math.floor(Math.random() * ips.length);
  var IP = 'http://vsc.aikan.miguvideo.com:' + ips[randomIndex]
  var Authenticate = postWithHeaders(IP + '/EPG/VPE/PHONE/Authenticate', postInfo, [], 'application/json');
  var Cookie = JSON.parse(Authenticate).sessionID;
  var header = [{ header: 'Cookie', value: 'JSESSIONID=' + Cookie }];
  var pdata = '{"businessType":"BTV","channelID":"' + id1 + '","mediaID":"' + id2 + '"}';
  var data = postWithHeaders(IP + '/VSP/V3/PlayChannel', pdata, header, 'application/json');
  result.push(JSON.parse(data).playURL)
  return result
}

//http://xproxy.yuntuds.com/lk/xjyx.m3u8?id=muguv3&id1=619858170&id2=619858170&?start=1660539836&end=1625809436
// v3咪咕回看
function muGuV3Playerd(originUrl) {
  var result=[]
	var originUrl = originUrl+"&";
	var id1 = getStringBtween(originUrl,"&id1=","&");
	var id2 = getStringBtween(originUrl,"&id2=","&");
	var st = _getStringBtween(originUrl,"?start=","&");
	var et = _getStringBtween(originUrl,"&end=","&");
	var start = formatDateTime(st-28800);
	var end = formatDateTime(et - 28800);
	var data = muGuParser('migu://' + id1 + '//' + id2);
	var url = data[0]+'&playbackbegin='+start+'&playbackend='+end;
	result.push(url)
	return result;
}

function miguLiveHdParsePlay(originUrl) {
  var pid = originUrl.split('://')[1];
  // var liveSourceId = "miguhd_" + pid;
  // setChannelKey / getChannelKey
  // var liveSourceEn = getChannelKey(liveSourceId);
  // if (liveSourceEn) {
  //   var liveSource = base64decode(liveSourceEn)
  //   return [liveSource]
  // }
  var timestramp = Math.round(Date.now()).toString();
  var appVersion = "2600034600";
  var appVersionID = appVersion + "-99000-201600010010028";
  var client_id = getMD5(Date.now().toString());

  var headers = [
    { header: 'AppVersion', value: appVersion },
    { header: 'TerminalId', value: "android" },
    { header: 'X-UP-CLIENT-CHANNEL-ID', value: appVersionID },
    { header: 'ClientId', value: client_id },
    { header: 'appCode', value: "miguvideo_default_android" }
  ];
  var str = timestramp + pid + appVersion.substring(0, 8);
  var md5 = getMD5(str);
  // 替换padStart 6位补0
  var randomNum = Math.floor(Math.random() * 1000000);
  var salt = ("000000" + randomNum).slice(-6) + "25";
  var suffix = "2cac4f2c6c3346a5b34e085725ef7e33migu" + salt.substring(0, 4);
  var sign = getMD5(md5 + suffix);
  var baseURL = "https://play.miguvideo.com/playurl/v1/play/playurl";
  var params = "?sign=" + sign + "&rateType=3" + "&contId=" + pid + "&timestamp=" + timestramp + "&salt=" + salt + "&flvEnable=false&super4k=false";
  var requestURL = baseURL + params;
  var respData = urlRequestWithParaAndCoding(requestURL, headers, "utf-8");
  // 兼容ES5 去除可选链?.
  var jsonData = JSON.parse(respData);
  var url = (jsonData.body && jsonData.body.urlInfo) ? jsonData.body.urlInfo.url : "";
  var resURL = getddCalcuURL720p(url, pid);
  // if(resURL){
  //   setChannelKey(liveSourceId,base64encode(resURL),(60 * 60))
  // }
  return [resURL];

  function getDateString(date) { var year = date.getFullYear(); var month = ("0" + (date.getMonth() + 1)).slice(-2); var day = ("0" + date.getDate()).slice(-2); return String(year) + month + day }
  function getddCalcu720p(puData, programId) { if (!puData || !programId) return ""; var keys = "cdabyzwxkl"; var ddCalcu = []; var halfLen = Math.floor(puData.length / 2); for (var i = 0; i < halfLen; i++) { ddCalcu.push(puData[puData.length - i - 1]); ddCalcu.push(puData[i]); switch (i) { case 1: ddCalcu.push("v"); break; case 2: var dateStr = getDateString(new Date()); var idx = parseInt(dateStr[2], 10); ddCalcu.push(keys[idx]); break; case 3: ddCalcu.push(keys[programId[6]]); break; case 4: ddCalcu.push("a"); break } } return ddCalcu.join("") }
  function getddCalcuURL720p(puDataURL, programId) { if (!puDataURL || !programId) return ""; var puData = puDataURL.split("&puData=")[1]; var ddCalcu = getddCalcu720p(puData, programId); return puDataURL + "&ddCalcu=" + ddCalcu + "&sv=10004&ct=android" }
}
// http://xproxy.yuntuds.com/lk/xjyx.m3u8?id=muguv4&name=608807420&?start=1735779600&end=1735781400
function miguhdPlayerd(originUrl){
  originUrl = originUrl+"&";
  var id = getStringBtween(originUrl,"&name=","&");
  var arr = miguLiveHdParsePlay('miguhd://'+ id)
	var st = _getStringBtween(originUrl,"?start=","&");
	var et = _getStringBtween(originUrl,"&end=","&");
	var start = formatDateTime(st);
	var end = formatDateTime(et);
	var url =arr[0]+'&playbackbegin='+start+'&playbackend='+end;
  var result=[]
	result.push(url)
	return result;
}
// 青海卫视
// qhtv://11
function qhtvParser(originUrl) {
	var id = originUrl.split('//')[1],result=[];
	var URL = 'http://mapi.geermurmt.com/api/v1/channel.php?channel_id=' + id;
	var data = urlRequest(URL);
	var url = JSON.parse(data)[0].m3u8
	result.push(url);
	return result;
}

// 海南电视台解析 http://www.hnntv.cn/live/xwlive/ hnntv://5
function hnntvParser(originUrl) {
    var id = originUrl.split('//')[1];
    var header = [
        {
            header: 'User-Agent',
            value: 'Mozilla/5.0 (Linux; Android 8.0; Pixel 2 Build/OPD3.170816.012) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.149 Mobile Safari/537.36'
        }
    ];
    var result = [];
    var url = 'http://www.hnntv.cn/m2o/channel/channel_info.php?channel_id=' + id;
    var data = urlRequestWithParaAndCoding(url, header, 'utf-8');
    var src = JSON.parse(data)[0].m3u8;
    result.push(src);
    return result;
}

// 石家庄台 http://v.sjzntv.cn/vod/xwzh/
// 新闻综合 xwzh 娱乐频道 ylpd 生活频道 shpd 都市频道 dspd
// sjz://xwzh
function sjzParser(sourceUrl) {
	var index = sourceUrl.split('://')[1]
	var table = {
		xwzh: 0,
		csfw: 1,
		whyl: 2,
		ds: 3
	}
	var id = table[index]
	var api = 'https://mapi.sjzntv.cn/api/v1/channel.php?node_id=1'
	var r = urlRequest(api)
	var data = JSON.parse(r)
	var m3u8 = data[id].m3u8
	return [m3u8]
}

// 壹深圳 http://sztv.cutv.com/dianshi.shtml?id=7944
// sztv://id=7944 id 在官网的地址栏
function sztvParser(originUrl) {
  // 内置解密处理函数
  function Z(str) {
    var halfLen = str.length - Math.floor(str.length / 2);
    var mixStr = str.substring(halfLen) + str.substring(0, halfLen);
    return base64decode(mixStr.split("").reverse().join(""));
  }
  // 频道映射表
  var channelMap = {
    7867: ["AxeFRth", "7867"],
    7868: ["ZwxzUXr", "7868"],
    7880: ["4azbkoY", "7880"],
    7874: ["2q76Sw2", "7874"],
    7871: ["3vlcoxP", "7871"],
    7872: ["1q4iPng", "7872"],
    7881: ["1SIQj6s", "7881"],
    7869: ["wDF6KJ3", "7869"],
    7878: ["BJ5u5k2", "7878"],
    7876: ["xO1xQFv", "7876"],
    7944: ["sztvgjpd", "7944"]
  };
  // 基础参数解析
  var id = Number(originUrl.split("id=")[1]);
  var channel = channelMap[id];
  if (!channel) return [];
  var channelId = channel[0];
  var cdnId = channel[1];
  var ts = Math.floor(Date.now() / 1000);
  var baseReferer = "https://www.sztv.com.cn/";
  var ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.95 Safari/537.36";
  var headers = [
    { header: "User-Agent", value: ua },
    { header: "Referer", value: baseReferer }
  ];
  // 1. 请求liveKey
  var token1 = getMD5(ts + channelId + "cutvLiveStream|Dream2017");
  var liveApi = "https://hls-api.sztv.com.cn/getCutvHlsLiveKey?t=" + ts + "&id=" + channelId + "&token=" + token1 + "&at=1";
  var liveRes = urlRequestWithParaAndCoding(liveApi, headers, "utf-8");
  var liveKey = Z(liveRes);
  // 2. 获取CDN密钥
  var cdnDomain = "sztv-live.sztv.com.cn";
  var cdnKey = "ejow6p6p6hmrm9g96beh2knecdq5kyw9bp0zxyg7";
  var tsMs = ts * 1000;
  var page = encodeURIComponent(baseReferer + "pindao/index.html?id=" + cdnId);
  var token2 = getMD5("iYKkRHlmUanQGaNMIJziWOkN" + cdnDomain + tsMs);
  var cdnApi = "https://sttv2-api.sztv.com.cn/api/getCDNkey.php?domain=" + cdnDomain + "&page=" + page + "&token=" + token2 + "&t=" + tsMs;
  try {
    var cdnRes = urlRequestWithParaAndCoding(cdnApi, headers, "UTF-8");
    var cdnInfo = JSON.parse(cdnRes);
    if (cdnInfo.key) cdnKey = cdnInfo.key;
  } catch (e) {}
  // 3. 拼接最终播放链接
  var tsHex = ts.toString(16);
  var signStr = cdnKey + "/" + channelId + "/500/" + liveKey + ".m3u8" + tsHex;
  var sign = getMD5(signStr);
  var m3u8Url = "https://" + cdnDomain + "/" + channelId + "/500/" + liveKey + ".m3u8?sign=" + sign + "&t=" + tsHex;
  var source = m3u8Url + "@@referer=" + baseReferer + "@user-agent=" + ua + "@@";
  return [source];
}

// 内蒙古卫视 http://www.nmtv.cn
// nmg://ws  ws 内蒙古卫视 jjsh 经济生活频道 mgy 蒙古语卫视 se 少儿频道 mgywh 蒙古语文化 nm 农牧频道 xwzh 新闻综合频道 wtyl 文体娱乐频道
function nmgParser(sourceUrl) {
	var index = sourceUrl.split('://')[1]
	var table = {
		ws: '161',
		jjsh: '168',
		mgy: '163',
		se: '165',
		mgywh: '164',
		nm: '167',
		xwzh: '162',
		wtyl: '166'
	}
	var id = table[index]
	var api = 'https://mapi.m2oplus.nmtv.cn/api/v1/program.php?channel_id=' + id
	var r = urlRequest(api)
	var data = JSON.parse(r)
	var hour = new Date().getHours()
	var m3u8 = data[hour].m3u8
	var result = []
	result.push(m3u8)
	return result
}

// 莆田电视台
function ptdstParser(originUrl) {
	var id = originUrl.split('//')[1]
	var url = 'https://www.ptbtv.com/m2o/channel/channel_info.php?channel_id=' + id
	var time = new Date().getTime()
	var sign = getMD5('f33ba15effa5c10e873bf3842afb46a6&YWFkZDYwMjNkNzMzNzUwZWJjYjE4NWFjZjY3YmQyYzE=&1.0.0&' + time)
	var header = [
		{
			header: 'x-api-signature',
			value: sign
		},
		{
			header: 'x-api-timestamp',
			value: time.toString()
		},
		{
			header: 'x-api-version',
			value: '1.0.0'
		}
	]
	var data = urlRequestWithParaAndCoding(url, header, 'utf-8')
	var url1 = JSON.parse(data)[0].m3u8
	return [url1]
}

// 云南官网 yngw://yunnanweishi
function yngwParser(originUrl) {
	var name = originUrl.split('//')[1]
	var url = 'https://yntv-api.yntv.cn/index/jmd/getRq?name=' + name
	var header = [
		{
			header: 'Referer',
			value: 'https://www.yntv.cn/'
		},
		{
			header: 'User-Agent',
			value: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'
		}
	]
	var data = urlRequestWithParaAndCoding(url, header, 'utf-8')
	var info = JSON.parse(data)
	var url1 = info.url.replace(/chunks/, 'playlist')
	var wsSecret = info.string
	var time = info.time
	var url2 =
		'https://tvlive.yntv.cn' +
		url1 +
		'?wsSecret=' +
		wsSecret +
		'&wsTime=' +
		time +
		'@@Referer=https://www.yntv.cn/@User-Agent=Mozilla/5.0@@'
	return [url2]
}
// 云南官网回看
// http://xproxy.yuntuds.com/lk/bstjx.m3u8?yngw=yunnanweishi&?start=1669017673&end=1669019473&
function yngwLBParser(originUrl) {
	originUrl = originUrl + '&'
	var name = getStringBtween(originUrl, '?yngw=', '&')
	var start = _getStringBtween(originUrl, '?start=', '&')
	var end = _getStringBtween(originUrl, '&end=', '&')
	var duration = end - start
	var url = 'https://yntv-api.yntv.cn/index/jmd/getPb4?name=' + name + '&start=' + start + '&duration=' + duration + '&_=' + new Date().getTime()
	var header = [
		{
			header: 'Referer',
			value: 'https://www.yntv.cn/'
		},
		{
			header: 'User-Agent',
			value: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'
		}
	]
	var data = urlRequestWithParaAndCoding(url, header, 'utf-8')
	var url2 = JSON.parse(data).url.replace('http', 'https') + '@@Referer=http://www.yntv.cn/@User-Agent=Mozilla/5.0@@'
	return [url2]
}

// var apiCont = postWithHeaders(postUrl,postData,postHeader,"application/x-www-form-urlencoded");
// 江西 jxwg://tv_jxtv1.m3u8
// 网页源代码function Vu() {下面的方法
function jxgwParser(originUrl) {
	var data = {
		t: Math.floor(new Date().getTime() / 1000),
		stream: originUrl.split('//')[1],
		uuid: '00104508404e'
	}
	for (var e = 'ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678oOLl9gqVvUuI1', n = e.length, a = '', i = 0; i < 8; i++) {
		a += e.charAt(Math.floor(Math.random() * n))
	}
	var Authorization = getMD5(data.t + data.stream + a + 'kcOL3W1QyO5IqUcu')
	var etag = a
	var postUrl = 'https://cdnauth.jxgdw.com/liveauth/pc'
	// var postData = 't=' + data.t + '&stream=' + data.stream + '&uuid=' + data.uuid + '&'
	var postData = '{"t":"' + data.t + '","stream":"' + data.stream + '","uuid":"' + data.uuid + '"}'
	var postHeader = []
	postHeader.push({ header: 'Authorization', value: Authorization })
	postHeader.push({ header: 'Referer', value: 'http://www.jxntv.cn/' })
	postHeader.push({ header: 'Gpu', value: 'Google Inc. (NVIDIA), ANGLE (NVIDIA, NVIDIA' })
	postHeader.push({
		header: 'User-Agent',
		value: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36 Edg/113.0.1774.35'
	})
	postHeader.push({ header: 'etag', value: etag })
	var apiCont = postWithHeaders(postUrl, postData, postHeader, 'application/x-www-form-urlencoded')
	var list = JSON.parse(apiCont)
	var url = 'https://yun-live.jxtvcn.com.cn/live-jxtv/' + data.stream + '?source=pc&t=' + list.t + '&token=' + list.token + '&uuid=' + data.uuid + '@@user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64)@referer=https://www.jxntv.cn/@@'
	return [url]
}

//内蒙古赤峰地方台 nmgcf://1
function nmgcfParser(originUrl) {
	var id = originUrl.split('//')[1]
	var url = 'http://www.cfrtv.cn/m2o/channel/channel_info.php?id=' + id
	var header = [
		{
			header: 'User-Agent',
			value: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'
		}
	]
	var data = urlRequestWithParaAndCoding(url, header, 'utf-8')
	var m3u8 = JSON.parse(data)[0].m3u8
	return [m3u8]
}

// wasudecypt
// 五星体育
function wxtyParser(originUrl) {
  var channelKey ='wxty_' + originUrl.split('//')[1]
  var liveSourceEn = getChannelKey(channelKey);
  if (liveSourceEn) {
    var liveSource = base64decode(liveSourceEn)
    return [liveSource]
  }
  var url = getUrl(channelKey)
  return [url]
  function getUrl(channelKey){
    var info = ye()
    var header = [
      {header: 'x-requested-with',value: 'com.tencent.mm'},
      {header: 'referer',value: 'https://gsports-wxtv.smgtech.net/'},
      {header: 'user-agent',value: 'Mozilla/5.0 (Linux; Android 6.0.1; MX4 Build/MOB30M; wv)'}
    ]
    var req_url = 'https://gsports-cdn.smgtech.net/api/v1/channel/channel/list.html?appId=5starsalt&nonceStr=z7cl7WR9&time=' + info.time + '&sign=' + info.sign
    var body = urlRequestWithParaAndCoding(req_url , header, 'utf-8')
    var ciphertext = JSON.parse(body).data[0].live_address
    var url = urlDecrypt(ciphertext)+'@@User-Agent=Mozilla/5.0 (Linux; Android 6.0.1; MX4 Build/MOB30M; wv)@Referer=https://gsports-wxtv.smgtech.net/@@'
    setChannelKey(channelKey,base64encode(url),(60 * 60))
    return url
  }
  function ye() {
    var e = '5starsalt',
      t = 'z7cl7WR9',
      i = 'ysc7mpeu6i39',
      o = Math.floor(Date.now() / 1e3)
    var a = 'appId=' + e + '&nonceStr=' + t + '&time=' + o + i
    var n = getMD5(a)
    return {
      time: o,
      sign: n
    }
  }
  function urlDecrypt(ciphertext) {
    var decryptKey = '7jKKND2V38eXvJEm'
    var i = CryptoJS.enc.Utf8.parse(decryptKey)
    var o = CryptoJS.DES.decrypt(
      {
        ciphertext: CryptoJS.enc.Hex.parse(ciphertext)
      },
      i,
      { mode: CryptoJS.mode.ECB, padding: CryptoJS.pad.ZeroPadding }
    )
    var a = o.toString(CryptoJS.enc.Utf8)
    return a.replace(/\x02/g, '').replace(/\x04/g, '').trim()
  }
}

function generateSign() {
  var timestamp = Math.floor(Date.now() / 1000);
  var sign = getMD5('source_cache_' + timestamp);
  return { timestamp: timestamp, sign: sign };
}

function setChannelKey(key, val, timeout) {
  if (timeout == null) timeout = (60 * 30);
  var gs = generateSign();
  var url = 'http://user.xiaoyouzb.net/source_cache/set?k=' + key + '&v=' + val + '&tos=' + timeout + '&s_key=' + gs.sign + '&s_time=' + gs.timestamp
  urlRequest(url)
}
function getChannelKey(key) {
  var gs = generateSign();
  var url = 'http://user.xiaoyouzb.net/source_cache/get?k=' + key + '&s_key=' + gs.sign + '&s_time=' + gs.timestamp
  var data = urlRequest(url)
  return JSON.parse(data).data
}