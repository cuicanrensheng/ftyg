//http://www.baofeng.com/play/247/play-833247.html?location=0517
function BaofengVodParsePlay(origUrl) {
	var Arr = origUrl.split("?location=");
	var htmlCont = urlRequestWithPara(Arr[0],"Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/66.0.3359.181 Safari/537.36","User-Agent");
	var pinfo = getStringBtween(htmlCont,"var movie_detail= ",";");
	var pinfoJson = eval('('+pinfo+')');
	var info_pianyuan = pinfoJson.info_pianyuan;
	var btNum = info_pianyuan.length;
	var urlArray = new Array();
	for(var i=0;i<btNum;i++){
		var aid = info_pianyuan[i].aid;
		var hd_type = info_pianyuan[i].hd_type;
		var Cont = getStringBtween(htmlCont,"var static_storm_json_"+aid+"=",";");
		var Json = eval('('+Cont+')');
		var video_list = Json.video_list;
		for(var j=0;j<video_list.length;j++){
			var loc = video_list[j].location;
			var iid = video_list[j].iid;
			var size = video_list[j].size;
			if(loc==Arr[1]){
				var timestamp = Date.parse(new Date());
				var apiUrl = "http://rd.p2p.baofeng.net/queryvp.php?type=3&gcid="+iid+"&_="+timestamp+"&callback=jsonp1";
				log("apiUrl=====>"+apiUrl);
				var apiCont = urlRequestWithPara(apiUrl,"Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/66.0.3359.181 Safari/537.36","User-Agent");
				apiCont = getStringBtween(apiCont,"(",")");
				var apiJson = eval('('+apiCont+')');
				var ips = apiJson.ip;
				var ipsArr = ips.split(",");
				var ip = decodeBaofengIp(ipsArr[0]);
				var port = apiJson.port;
				var path = apiJson.path;
				var key = apiJson.key;
				var playUrl = "http://"+ip+":"+port+"/"+path+"?key="+key+"&filelen="+size;
				if(hd_type=="1080P"){
					urlArray.push("2"+playUrl+"#1080P");
				}else if(hd_type=="720P"){
					urlArray.push("1"+playUrl+"#超清");
				}else{
					urlArray.push("3"+playUrl+"#高清");
				}
				break;
			}
		}
	}
	urlArray=urlArray.sort();
	var playArr = new Array();
	for(var j=0;j<urlArray.length;j++){
		playArr.push(urlArray[j].substring(1));
	}
    return playArr;
}

function decodeBaofengIp(enIp){
	var ip = replaceAll(enIp,"a","1");
	ip = replaceAll(ip,"o","2");
	ip = replaceAll(ip,"f","3");
	ip = replaceAll(ip,"e","4");
	ip = replaceAll(ip,"n","5");
	ip = replaceAll(ip,"g","6");
	ip = replaceAll(ip,"h","7");
	ip = replaceAll(ip,"t","8");
	ip = replaceAll(ip,"m","9");
	ip = replaceAll(ip,"b","0");
	ip = replaceAll(ip,"l",".");
	return ip;
}

function replaceAll(str,str1,str2){
	for(var i=0;i<15;i++){
		str = str.replace(str1,str2);
	}
	return str;
}

//cctv://5a9bc9d298314614872105967c2f66f3
function CctvVodParsePlay(origUrl) {
	var vid = origUrl.replace("cctv://","");
	var apiUrl = "http://vdn.apps.cntv.cn/api/getHttpVideoInfo.do?pid=" + vid;
	var apiCont = urlRequestWithPara(apiUrl,"Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/66.0.3359.181 Safari/537.36","User-Agent");
	log("apiCont===>"+apiCont);
	var jsonObject = eval('('+apiCont+')');
    var hls_url = jsonObject.hls_url;
	log("hls_url===>"+hls_url);
	var hlsCont = urlRequestWithPara(hls_url,"Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/66.0.3359.181 Safari/537.36","User-Agent");
	var hlsArr = hls_url.split("/");
	var fileUrlArr = hlsCont.match(/asp(.*?)\.m3u8/ig);
	var num = fileUrlArr.length;
	var urlArray = new Array();
	for(var i=0;i<num;i++){
		vodUrl = hlsArr[0]+"//"+hlsArr[2]+"/"+fileUrlArr[i];
		if(vodUrl.indexOf("/2000/")!=-1){
			urlArray.push("1"+vodUrl+"#超清");
		}else if(vodUrl.indexOf("/1200/")!=-1){
			urlArray.push("2"+vodUrl+"#高清");
		}else if(vodUrl.indexOf("/850/")!=-1){
			urlArray.push("3"+vodUrl+"#标清");
		}else if(vodUrl.indexOf("/450/")!=-1){
			urlArray.push("4"+vodUrl+"#流畅");
		}else{
			urlArray.push("5"+vodUrl+"#流畅");
		}
	}
	urlArray=urlArray.sort();
	var playArr = new Array();
	for(var j=0;j<urlArray.length;j++){
		playArr.push(urlArray[j].substring(1));
	}
    return playArr;
}

function cctvBackParse(origUrl) {

	var urlArray = new Array();
	var id = queryParam("id", origUrl);
	var st = queryParam("start", origUrl);
	var et = queryParam("end", origUrl);

	var start = formatCctvbackDateTime(st);
	var end = formatCctvbackDateTime(et);

	var apiUrl = "http://vdn.apps.cntv.cn/api/liveback.do?channel="+id+"&starttime="+start+"&endtime="+end;

	log(apiUrl)
	var content = urlRequestWithPara(apiUrl, "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36","User-Agent");

	var obj = JSON.parse(content);

	var arr;
	if (obj.video != undefined) {
		arr = JSON.stringify(obj.video.chapters);
	}
	else {
		urlArray.push("error://cntvback_fileapi_error");
		return urlArray;
	}

	var fileApi = "http://47.98.87.165/config/get_lbk?content=" + encodeURIComponent(arr) + "&urlPrefix=" + "&filename=" + id + "_" + start + "_" + end + ".lbk";

	backRs = urlRequest(fileApi)
	log(backRs);

	obj = JSON.parse(backRs);
	if (obj.code==0) {
		var playUrl = (typeof client!= 'undefined') ? obj.data+"#央视网" : obj.data;
		log(playUrl);
		urlArray.push(playUrl);
		return urlArray;
	}
	else {
		urlArray.push("error://cntvback_fileapi_error");
		return urlArray;
	}
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

function queryParam(name, url){
	if(name=(new RegExp('[?&]'+encodeURIComponent(name)+'=([^&]*)')).exec(url))
		return decodeURIComponent(name[1]);
}

//http://www.hanju.cc/hanju/144846/1.html
function HanjuVodParsePlay(origUrl) {
    var dic1 = {
        "value": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/54.0.2840.98 Safari/537.36",
        "header": "User-Agent"
    };
    var arr = new Array();
    arr.push(dic1);
    var cont = urlRequestWithParaAndCoding(origUrl, arr, 2147485234);
	var urlArray = new Array();
    if(cont.indexOf("opentv")!=-1){
        log("111111");
		var openUrl = cont.substring(cont.indexOf("http://www.hanju.cc/api/"));
		openUrl = openUrl.substring(0, openUrl.indexOf("\""));//http://www.hanju.cc/api/opentv123.php?v=PP10403dfdb1594cf0a4e6d636e8927896
		var openCont = urlRequestWithPara(openUrl, origUrl, "Referer");
		var apiUrl = openCont.substring(openCont.indexOf("https://"));
		apiUrl = apiUrl.substring(0, apiUrl.indexOf("\""));
		var playCont = urlRequestWithPara(apiUrl, openUrl,"Referer");
		var playUrl = playCont.substring(playCont.indexOf("http://"));
		playUrl = playUrl.substring(0, playUrl.indexOf("'"));
		urlArray.push(playUrl+"#流畅")
	}else if(cont.indexOf(".m3u8")!=-1){
		playUrl = getStringBtween(cont,"var vid='","'");
		urlArray.push(playUrl+"#流畅")
	}
	return urlArray;
}

//http://nangua.yingshiq.com/nangua.php?vid=3721
function NanguaVodParsePlay(origUrl) {
	var content = getUrlContent(origUrl);
	var ridarr = content.match(/option value="(.*?)"/ig);
	var vlen = ridarr.length;
    var urlArray = new Array();
    log("vlen====>"+vlen);
    if(vlen == 0){
        urlArray[0] = "http://error#流畅";
    }else if(vlen == 1){
        var rid0 = streplace(ridarr[0],'option value="','"');
        urlArray[0] = rid0 + "#标清";
    }else if(vlen == 2){
        var rid0 = streplace(ridarr[0],'option value="','"');
		var rid1 = streplace(ridarr[1],'option value="','"');
		urlArray[0] = rid1 + "#高清";
		urlArray[1] = rid0 + "#标清";
    }else{
        var rid0 = streplace(ridarr[0],'option value="','"');
		var rid1 = streplace(ridarr[1],'option value="','"');
		var rid2 = streplace(ridarr[2],'option value="','"');
		urlArray[0] = rid1 + "#高清";
		urlArray[1] = rid0 + "#标清";
		urlArray[2] = rid2 + "#超清";
    }
    return urlArray;
}

function getStringBtween(str,str1,str2){
	var a = str.substring(str.indexOf(str1)+str1.length);
	a = a.substring(0, a.indexOf(str2));
	return a;
}

function streplace(str,stra,strb){
    str=str.replace(stra,"");
    str=str.replace(strb,"");
    return str;
}

// xgvod://v02004f20000beiprircp2378d18dbog
function parseXiguaVod(origUrl) {
    var zvid = origUrl.replace("xgvod://", "");
	// log('zvid:' + zvid);
	var apiPath = 'https://ib.365yg.com' + (function(pathname) {
		var n = function () {
			for (var t = 0, e = new Array(256), n = 0; 256 != n; ++n)t = n, t = 1 & t ? -306674912 ^ t >>> 1 : t >>> 1, t = 1 & t ? -306674912 ^ t >>> 1 : t >>> 1, t = 1 & t ? -306674912 ^ t >>> 1 : t >>> 1, t = 1 & t ? -306674912 ^ t >>> 1 : t >>> 1, t = 1 & t ? -306674912 ^ t >>> 1 : t >>> 1, t = 1 & t ? -306674912 ^ t >>> 1 : t >>> 1, t = 1 & t ? -306674912 ^ t >>> 1 : t >>> 1, t = 1 & t ? -306674912 ^ t >>> 1 : t >>> 1, e[n] = t;
			return "undefined" != typeof Int32Array ? new Int32Array(e) : e
		}(), o = function (t) {
			for (var e, o, r = -1, i = 0, a = t.length; i < a;)e = t.charCodeAt(i++), e < 128 ? r = r >>> 8 ^ n[255 & (r ^ e)] : e < 2048 ? (r = r >>> 8 ^ n[255 & (r ^ (192 | e >> 6 & 31))], r = r >>> 8 ^ n[255 & (r ^ (128 | 63 & e))]) : e >= 55296 && e < 57344 ? (e = (1023 & e) + 64, o = 1023 & t.charCodeAt(i++), r = r >>> 8 ^ n[255 & (r ^ (240 | e >> 8 & 7))], r = r >>> 8 ^ n[255 & (r ^ (128 | e >> 2 & 63))], r = r >>> 8 ^ n[255 & (r ^ (128 | o >> 6 & 15 | (3 & e) << 4))], r = r >>> 8 ^ n[255 & (r ^ (128 | 63 & o))]) : (r = r >>> 8 ^ n[255 & (r ^ (224 | e >> 12 & 15))], r = r >>> 8 ^ n[255 & (r ^ (128 | e >> 6 & 63))], r = r >>> 8 ^ n[255 & (r ^ (128 | 63 & e))]);
			return r ^ -1
		}, r = pathname + "?r=" + Math.random().toString(10).substring(2);
		"/" != r[0] && (r = "/" + r);
		var i = o(r) >>> 0;
		return r + "&s=" + i
	})('/video/urls/v/1/toutiao/mp4/' + zvid);
	// log('apiPath:' + apiPath);

	var apiData = urlRequestWithPara(apiPath, 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.115 Safari/537.36',"User-Agent");
	// log('apiData:' + apiData);

	var jsonObject = eval('(' + apiData + ')');
	var mainUrl = base64decode(jsonObject.data.video_list.video_1.main_url);
	// var backupUrl = atob(jsonObject.data.video_list.video_1.backup_url_1);
	// log(mainUrl);
	return [mainUrl + '#标清'];
    // return ["error://xigua_error"];
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

function krVideoPlay(pageUrl){
    var arr = [{'header': 'User-Agent', 'value': 'Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/62.0.3202.9 Safari/537.36'}];
    var htmlContent = urlRequestWithParaAndCoding(pageUrl, arr);
    var raw_links_start_index = htmlContent.search(/fluency\=/);
    var raw_links_end_index = htmlContent.search(/action-data\=/);
    var r = unescape(htmlContent.slice(raw_links_start_index, raw_links_end_index));
    r = r.replace(/\\/g, '');
    r = r.replace(/\"/g, '');
    r = r.replace(/=n/g, '=');
    r = r.trim()
    var raw_links = r.split('//');
    var results = [];
    function add_result(str) {
        if (str.startsWith('fus')) {
                var i = str.search(/\d+(?==)/)
                var tag = str.slice(i, str.length-1) + 'p'
                results.push('http://' + str.trim() + '#' + tag)
        }
    }
    raw_links.forEach(add_result);
    return results
}


function cbox_cntv_cn_play(pageUrl){
	function extract_guid(url) {
		var pattern = new RegExp(/guid\=.+/);
		var result = pattern.exec(url);
		if (result) {
			return result[0].slice(5);
		} else {
			return null;
		}
	}

    function get_src_list(guid, pageUrl) {
        var url = 'http://asp.cntv.lxdns.com/asp/hls/main/0303000a/3/default/' + guid +'/main.m3u8?maxbr=2048';
        var arr = [{'haeder':'User-Agent', 'value': 'Mozilla/5.0 (Windows NT 10.0; WOW64; rv:56.0) Gecko/20100101 Firefox/56.0'}];
        arr.push({'header':'Referer', 'value': pageUrl});
        var htmlContent = urlRequestWithParaAndCoding(url, arr);
        var arr = htmlContent.match(/\/asp.*\.m3u8/g);
        return arr
    }

    function get_tag(r_src) {
        var index = r_src.search(/\d*(?=\.m3u8)/);
        var r = r_src.slice(index);
        var r_index = r.search(/\./);
        var num = r.slice(0, r_index);
        var tag = '';
        if (num > 1200) {
            tag = "超清";
        } else if (num == 1200) {
            tag = "高清";
        }
        else {
            tag = '标清';
        }
        return tag;
    }

	var guid = extract_guid(pageUrl)
    var r_scrs = get_src_list(guid, pageUrl);
    var result = [];
    for (var i = 0; i < r_scrs.length; i++) {
        var url = 'http://asp.cntv.lxdns.com' + r_scrs[i] + '#' + get_tag(r_scrs[i]);
        result.push(url);
    }
    return result;
}

function cctv_xiaoyan_play(pageUrl) {
    function get_guid(url) {
        var arr = [{'haeder':'User-Agent', 'value': 'Mozilla/5.0 (Windows NT 10.0; WOW64; rv:56.0) Gecko/20100101 Firefox/56.0'}];
        var htmlContent = urlRequestWithParaAndCoding(url, arr);
        var start_index = htmlContent.search(/guid\=/) + 5;
        var end_index = htmlContent.search(/var screenW/);
        var result = htmlContent.slice(start_index, end_index).trim();
        result = result.replace(/\"/g, '');
        result = result.replace(/\;/g, '');
        return result
    }

    function get_src_list(guid, pageUrl) {
        var url = 'http://asp.cntv.lxdns.com/asp/hls/main/0303000a/3/default/' + guid +'/main.m3u8?maxbr=2048';
        var arr = [{'haeder':'User-Agent', 'value': 'Mozilla/5.0 (Windows NT 10.0; WOW64; rv:56.0) Gecko/20100101 Firefox/56.0'}];
        arr.push({'header':'Referer', 'value': pageUrl});
        var htmlContent = urlRequestWithParaAndCoding(url, arr);
        var arr = htmlContent.match(/\/asp.*\.m3u8/g);
        return arr
    }

    function get_tag(r_src) {
        var index = r_src.search(/\d*(?=\.m3u8)/);
        var r = r_src.slice(index);
        var r_index = r.search(/\./);
        var num = r.slice(0, r_index);
        var tag = '';
        if (num > 1200) {
            tag = "超清";
        } else if (num == 1200) {
            tag = "高清";
        }
        else {
            tag = '标清';
        }
        return tag;
    }
    if(pageUrl.indexOf('.m3u8') != -1) {
        return pageUrl;
    }
    var guid = get_guid(pageUrl);
    var r_scrs = get_src_list(guid, pageUrl);
    var result = [];
    for (var i = 0; i < r_scrs.length; i++) {
        var url = 'http://asp.cntv.lxdns.com' + r_scrs[i] + '#' + get_tag(r_scrs[i]);
        result.push(url);
    }
    return result;
}

function onln_cn_play(pageUrl) {
	function get_tk() {
		var url = 'https://www.onln.cn/login/getToken';
		var arr = [];
		arr.push({'header': 'accept', 'value': 'application/json, text/javascript, */*; q=0.01'});
		arr.push({'header': 'accept-encoding', 'value': 'gzip, deflate, br'});
		arr.push({'header': 'accept-language', 'value': 'en-US,en;q=0.9'});
		arr.push({'header': 'content-length', 'value': '4'});
		arr.push({'header': 'content-type', 'value': 'application/x-www-form-urlencoded; charset=UTF-8'});
		arr.push({'header': 'origin', 'value':'https://www.onln.cn'});
		arr.push({'header': 'referer', 'value': 'https://www.onln.cn/home.html'});
		arr.push({'header': 'user-agent', 'value': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/71.0.3578.80 Chrome/71.0.3578.80 Safari/537.36'});
		arr.push({'header': 'x-requested-with', 'value': 'XMLHttpRequest'});
		var mediaType = 'application/x-www-form-urlencoded; charset=UTF-8';
		// var data = "{'mu':'0'}";
		var data = 'mu=0';
		var result = postWithHeaders(url, data, arr, mediaType);
		return JSON.parse(result)['msg']
	}

	function parse_page_url(){
		var result = {};
		if (-1 != pageUrl.indexOf('/0/')) {
			var zero_index = pageUrl.search(/\/0\//);
			var channelId = pageUrl.slice(zero_index+3);
			result['channelId'] = channelId;
			var id_start = pageUrl.search(/\d+\/0\//);
			var id = pageUrl.slice(id_start, zero_index)
			result['id'] = id
		} else {
			var index = pageUrl.search(/\/\d+/)
			var channelId = pageUrl.slice(index)
			result['channelId'] = channelId
		}
		return result
	}

	function get_vid_src(){
		var playDetail_url = "https://www.onln.cn/tv/playDetails";
		var params = parse_page_url();
		params['tk'] = get_tk();
		params['mu'] = 0;
		params['t'] = Math.random();
		var query = '';
		for (var k in params) {
			query += (k + '=' + params[k]+ '&');
		}
		playDetail_url += '?';
		playDetail_url += query;
		var headers = {
                    'referer': pageUrl,
                    'user-agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/71.0.3578.80 Chrome/71.0.3578.80 Safari/537.36',
                    'accept': 'application/json, text/javascript, */*; q=0.01',
                    'accept-encoding': 'gzip, deflate, br',
                    'accept-language': 'en-US,en;q=0.9'

        };
		var arr = [];
		for (var k in headers) {
			arr.push({'header':k, 'value': headers.k});
		}
		var response = urlRequestWithParaAndCoding(playDetail_url, arr);
		var result = JSON.parse(response);
		return result['data']['tv']['videoPath']

	}
	var result = get_vid_src();
	result += '@@referer=';
	result += pageUrl;
	result += "@user-agent="
	result += 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/71.0.3578.98 Chrome/71.0.3578.98 Safari/537.36'
	result += "@@"
	var results = [];
	results.push(result)
	return results
}

var MD5 = function(d) {
    result = M(V(Y(X(d), 8 * d.length)));
    return result.toLowerCase()
};

function M(d) {
    for (var _, m = "0123456789ABCDEF", f = "", r = 0; r < d.length; r++) _ = d.charCodeAt(r), f += m.charAt(_ >>> 4 & 15) + m.charAt(15 & _);
    return f
}

function X(d) {
    for (var _ = Array(d.length >> 2), m = 0; m < _.length; m++) _[m] = 0;
    for (m = 0; m < 8 * d.length; m += 8) _[m >> 5] |= (255 & d.charCodeAt(m / 8)) << m % 32;
    return _
}

function V(d) {
    for (var _ = "", m = 0; m < 32 * d.length; m += 8) _ += String.fromCharCode(d[m >> 5] >>> m % 32 & 255);
    return _
}

function Y(d, _) {
    d[_ >> 5] |= 128 << _ % 32, d[14 + (_ + 64 >>> 9 << 4)] = _;
    for (var m = 1732584193, f = -271733879, r = -1732584194, i = 271733878, n = 0; n < d.length; n += 16) {
        var h = m,
            t = f,
            g = r,
            e = i;
        f = md5_ii(f = md5_ii(f = md5_ii(f = md5_ii(f = md5_hh(f = md5_hh(f = md5_hh(f = md5_hh(f = md5_gg(f = md5_gg(f = md5_gg(f = md5_gg(f = md5_ff(f = md5_ff(f = md5_ff(f = md5_ff(f, r = md5_ff(r, i = md5_ff(i, m = md5_ff(m, f, r, i, d[n + 0], 7, -680876936), f, r, d[n + 1], 12, -389564586), m, f, d[n + 2], 17, 606105819), i, m, d[n + 3], 22, -1044525330), r = md5_ff(r, i = md5_ff(i, m = md5_ff(m, f, r, i, d[n + 4], 7, -176418897), f, r, d[n + 5], 12, 1200080426), m, f, d[n + 6], 17, -1473231341), i, m, d[n + 7], 22, -45705983), r = md5_ff(r, i = md5_ff(i, m = md5_ff(m, f, r, i, d[n + 8], 7, 1770035416), f, r, d[n + 9], 12, -1958414417), m, f, d[n + 10], 17, -42063), i, m, d[n + 11], 22, -1990404162), r = md5_ff(r, i = md5_ff(i, m = md5_ff(m, f, r, i, d[n + 12], 7, 1804603682), f, r, d[n + 13], 12, -40341101), m, f, d[n + 14], 17, -1502002290), i, m, d[n + 15], 22, 1236535329), r = md5_gg(r, i = md5_gg(i, m = md5_gg(m, f, r, i, d[n + 1], 5, -165796510), f, r, d[n + 6], 9, -1069501632), m, f, d[n + 11], 14, 643717713), i, m, d[n + 0], 20, -373897302), r = md5_gg(r, i = md5_gg(i, m = md5_gg(m, f, r, i, d[n + 5], 5, -701558691), f, r, d[n + 10], 9, 38016083), m, f, d[n + 15], 14, -660478335), i, m, d[n + 4], 20, -405537848), r = md5_gg(r, i = md5_gg(i, m = md5_gg(m, f, r, i, d[n + 9], 5, 568446438), f, r, d[n + 14], 9, -1019803690), m, f, d[n + 3], 14, -187363961), i, m, d[n + 8], 20, 1163531501), r = md5_gg(r, i = md5_gg(i, m = md5_gg(m, f, r, i, d[n + 13], 5, -1444681467), f, r, d[n + 2], 9, -51403784), m, f, d[n + 7], 14, 1735328473), i, m, d[n + 12], 20, -1926607734), r = md5_hh(r, i = md5_hh(i, m = md5_hh(m, f, r, i, d[n + 5], 4, -378558), f, r, d[n + 8], 11, -2022574463), m, f, d[n + 11], 16, 1839030562), i, m, d[n + 14], 23, -35309556), r = md5_hh(r, i = md5_hh(i, m = md5_hh(m, f, r, i, d[n + 1], 4, -1530992060), f, r, d[n + 4], 11, 1272893353), m, f, d[n + 7], 16, -155497632), i, m, d[n + 10], 23, -1094730640), r = md5_hh(r, i = md5_hh(i, m = md5_hh(m, f, r, i, d[n + 13], 4, 681279174), f, r, d[n + 0], 11, -358537222), m, f, d[n + 3], 16, -722521979), i, m, d[n + 6], 23, 76029189), r = md5_hh(r, i = md5_hh(i, m = md5_hh(m, f, r, i, d[n + 9], 4, -640364487), f, r, d[n + 12], 11, -421815835), m, f, d[n + 15], 16, 530742520), i, m, d[n + 2], 23, -995338651), r = md5_ii(r, i = md5_ii(i, m = md5_ii(m, f, r, i, d[n + 0], 6, -198630844), f, r, d[n + 7], 10, 1126891415), m, f, d[n + 14], 15, -1416354905), i, m, d[n + 5], 21, -57434055), r = md5_ii(r, i = md5_ii(i, m = md5_ii(m, f, r, i, d[n + 12], 6, 1700485571), f, r, d[n + 3], 10, -1894986606), m, f, d[n + 10], 15, -1051523), i, m, d[n + 1], 21, -2054922799), r = md5_ii(r, i = md5_ii(i, m = md5_ii(m, f, r, i, d[n + 8], 6, 1873313359), f, r, d[n + 15], 10, -30611744), m, f, d[n + 6], 15, -1560198380), i, m, d[n + 13], 21, 1309151649), r = md5_ii(r, i = md5_ii(i, m = md5_ii(m, f, r, i, d[n + 4], 6, -145523070), f, r, d[n + 11], 10, -1120210379), m, f, d[n + 2], 15, 718787259), i, m, d[n + 9], 21, -343485551), m = safe_add(m, h), f = safe_add(f, t), r = safe_add(r, g), i = safe_add(i, e)
    }
    return Array(m, f, r, i)
}

function md5_cmn(d, _, m, f, r, i) {
    return safe_add(bit_rol(safe_add(safe_add(_, d), safe_add(f, i)), r), m)
}

function md5_ff(d, _, m, f, r, i, n) {
    return md5_cmn(_ & m | ~_ & f, d, _, r, i, n)
}

function md5_gg(d, _, m, f, r, i, n) {
    return md5_cmn(_ & f | m & ~f, d, _, r, i, n)
}

function md5_hh(d, _, m, f, r, i, n) {
    return md5_cmn(_ ^ m ^ f, d, _, r, i, n)
}

function md5_ii(d, _, m, f, r, i, n) {
    return md5_cmn(m ^ (_ | ~f), d, _, r, i, n)
}

function safe_add(d, _) {
    var m = (65535 & d) + (65535 & _);
    return (d >> 16) + (_ >> 16) + (m >> 16) << 16 | 65535 & m
}

function bit_rol(d, _) {
    return d << _ | d >>> 32 - _
}


// console.log(MD5('fungolive'+1556168671));

function yuntuds_play(url) {
	var api_url = 'http://user.yuntuds.com/v3/vod/get_ali_play_info?';
	var pattern = new RegExp('alivod\:\/\/(.+)');
	var vid = pattern.exec(url)[1];
	api_url += '&vid=' + vid;
	var nwtime = parseInt(+ new Date()/1000);
	api_url += '&nwtime=' + nwtime;
	var sign = MD5('fungolive' + nwtime);
	api_url += '&sign=' + sign;
	var response = urlRequest(api_url);
	var result = JSON.parse(response);
	var r = [];
	if (result['error_code'] == '1') {
		r.push(result['data'][0]['playURL']);
	} else {
		r.push('error://' + result['msg']);
	}
	return r
}
