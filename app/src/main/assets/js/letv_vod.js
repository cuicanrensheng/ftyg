/**
 * @brief 乐视播放地址解析
 * @author Lee<libo@yuntutv.net>
 * @date 2015-7-30
 * @version 1.0.0.0
 */
 
//解析点播地址http://m.le.com/vplay_1172303.html
//http://sports.le.com/video/28003747.html
//http://www.le.com/ptv/vplay/28091422.html
//http://www.le.com/ptv/vplay/30429735.html
function letvVodParsePlay(origUrl) {
	//1.获取乐视网页内容
		htmlContent = urlRequestWithPara(origUrl,"Mozilla/5.0 (Linux; Android 4.4.4; Nexus 5 Build/KTU84P) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/38.0.2125.114 Mobile Safari/537.36","User-Agent");//注意:url需要在调用改脚本之前调用，作为全局变量即可
		if(htmlContent.length <= 0){
			return letyVodParsePlay(origUrl);
		}
		//2.获取mmsid
		var mmsid = htmlContent.substring(htmlContent.indexOf("mmsid\":\"")+8);
		mmsid = mmsid.substring(0, mmsid.indexOf("\""));
		if(- 1 != mmsid.indexOf(",")){
			mmsids=mmsid.split(",");
			mmsid=mmsids[0];
		}
		if (mmsid.length <= 0){
            return letyVodParsePlay(origUrl);
        }
        console.log("mmsid======>bbbb"+mmsid);
		//3.获取vid
		var vid = htmlContent.substring(htmlContent.indexOf("\"vid\":\"")+7);
		vid = vid.substring(0, vid.indexOf("\""));
		if (vid.length <= 0){
            letyVodParsePlay(origUrl);
		}
		//4.地址md5值
		key = getMD5('http://www.letv.com/ptv/vplay/'+vid+'.html');
		lurl = 'http://dynamic.app.m.letv.com/android/dynamic.php?mod=minfo&ctl=videofile&act=index&mmsid='+mmsid+'&playid=0&tss=ios&key='+key+'&pcode=010210000&version=5.2';
		content = getUrlContent(lurl);
		if(content.length <= 100){
            return letyVodParsePlay(origUrl);
		}
		var data = JSON.parse(content);
		var dispatch = data['body']['videofile']['infos'];
		urlArray = new Array();
		if(dispatch['mp4_1300']){
			var mainUrl=dispatch['mp4_1300']['mainUrl'];
			var RealUrl=getRealUrl(mainUrl);
			urlArray.push(RealUrl + '#高清');
		}
        if(dispatch['mp4_1000']){
			var mainUrl=dispatch['mp4_1000']['mainUrl'];
			var RealUrl=getRealUrl(mainUrl);
			urlArray.push(RealUrl + '#标清');
		}
        if(dispatch['mp4_720p']){
			var mainUrl=dispatch['mp4_720p']['mainUrl'];
			var RealUrl=getRealUrl(mainUrl);
			urlArray.push(RealUrl + '#超清');
		}
        if(dispatch['mp4_350']){
			var mainUrl=dispatch['mp4_350']['mainUrl'];
			var RealUrl=getRealUrl(mainUrl);
			urlArray.push(RealUrl + '#流畅');
        }
		
		/*jsonObject = eval('('+content+')');
		body = jsonObject.body;
		if(body.length <= 0){
            return letyVodParsePlay(origUrl);
		}
		infos = jsonObject.body.videofile.infos;
		urlArray = new Array();
    
		for (var key in infos) {
			if ('__c' != key){
				//解析出当前清晰度地址列表
				typeUrl = jsonObject.body.videofile.infos[key];
				for (var tp in typeUrl) {
                        if(-1 != tp.indexOf('Url')) {
						playUrl = getUrlContent(typeUrl[tp]);
						if(playUrl.length <= 0){
							return new Array();
						}
						json = eval('('+playUrl+')');
                        if('200' == json.status){
							vodurl = json.location;
                            videoType = getType(key);
                            if ('' === videoType){
                                continue;
                            }
							urlArray.push(vodurl + "&tp="+key + "#" + videoType);
						}
					}
				}
			}
		}*/
		return urlArray;
}


//sports.le.com/video/26201908.html
 function letyVodParsePlay(origUrl) {
    var vid = origUrl.substring(origUrl.lastIndexOf("/")+1);
    vid = vid.substring(0, vid.lastIndexOf(".html"));
    if (vid.length <= 0){
        return new Array();
    }
    var t = (new Date()).getTime() / 1000;
	var tkey=mmsKey(t);
	var deviceId=getMD5(t+"");
    var lurl='http://player-pc.le.com/mms/out/video/playJson.json?platid=3&splatid=301&tss=no&id='+vid+'&detect=0&dvtype=1000&accessyx=1&domain=m.le.com&tkey=' + tkey +'&devid='+deviceId+'&source=1001&lang=cn&region=cn&isHttps=0';
    content = getUrlContent(lurl);
     console.log("====>"+content);
    urlArray = new Array();
	 var data = JSON.parse(content);
     var statuscode=data['msgs']['statuscode'];
     if(statuscode!="1001"){
         return new Array();
     }
     var stream_id = '';
    var dispatch = data['msgs']['playurl']['dispatch'];
    if (dispatch['1300']) {
        stream_id = '1300';
        var url2 = data['msgs']['playurl']['domain'][0] + data['msgs']['playurl']['dispatch'][stream_id][0];
        url2 += '&ctv=pc&m3v=1&termid=1&format=1&hwtype=un&ostype=Linux&tag=letv&sign=letv&expect=3&tn='+Math.random()+'&pay=0&iscpn=f9051&rateid='+stream_id;
		url2=url2.replace("&tss=no","&tss=ios");
		var RealUrl=getRealUrl(url2);
		urlArray.push(RealUrl + '#高清');
    }
	if (dispatch['1000']) {
        stream_id = '1000';
        var url2 = data['msgs']['playurl']['domain'][0] + data['msgs']['playurl']['dispatch'][stream_id][0];
        url2 += '&ctv=pc&m3v=1&termid=1&format=1&hwtype=un&ostype=Linux&tag=letv&sign=letv&expect=3&tn='+Math.random()+'&pay=0&iscpn=f9051&rateid='+stream_id;
		url2=url2.replace("&tss=no","&tss=ios");
		var RealUrl=getRealUrl(url2);
		urlArray.push(RealUrl + '#标清');
     }
	if(dispatch['720p']) {
        stream_id = '720p';
        var url2 = data['msgs']['playurl']['domain'][0] + data['msgs']['playurl']['dispatch'][stream_id][0];
        url2 += '&ctv=pc&m3v=1&termid=1&format=1&hwtype=un&ostype=Linux&tag=letv&sign=letv&expect=3&tn='+Math.random()+'&pay=0&iscpn=f9051&rateid='+stream_id;
        url2=url2.replace("&tss=no","&tss=ios");
		var RealUrl=getRealUrl(url2);
		urlArray.push(RealUrl + '#超清');
    }
	if(dispatch['1080p']) {
        stream_id = '1080p';
        var url2 = data['msgs']['playurl']['domain'][0] + data['msgs']['playurl']['dispatch'][stream_id][0];
        url2 += '&ctv=pc&m3v=1&termid=1&format=1&hwtype=un&ostype=Linux&tag=letv&sign=letv&expect=3&tn='+Math.random()+'&pay=0&iscpn=f9051&rateid='+stream_id;
        url2=url2.replace("&tss=no","&tss=ios");
		var RealUrl=getRealUrl(url2);
		urlArray.push(RealUrl + '#1080p');
    }
	if(dispatch['350']) {
        stream_id = '350'
        var url2 = data['msgs']['playurl']['domain'][0] + data['msgs']['playurl']['dispatch'][stream_id][0];
        url2 += '&ctv=pc&m3v=1&termid=1&format=1&hwtype=un&ostype=Linux&tag=letv&sign=letv&expect=3&tn='+Math.random()+'&pay=0&iscpn=f9051&rateid='+stream_id;
        url2=url2.replace("&tss=no","&tss=ios");
		var RealUrl=getRealUrl(url2);
		urlArray.push(RealUrl + '#流畅');
    }
	return urlArray;
}

function getRealUrl(url){
	var jsoncontent = getUrlContent(url);
	jsonObject = eval('('+jsoncontent+')');
    vodurl = jsonObject.location;
	return vodurl;
}

function mmsKey(e) {
	var key = 185025305;
    var t = key;
    var r = key % 17;
    var n = e;
    n = calcKey(n, r);
    return n ^ t;
}
function calcKey(e, t) {
    for (var r, n = 0; t > n; n++) {
        r = 1 & e;
        e >>= 1;
        r <<= 31;
         e += r;
    }
    return e;
}

//http://sports.letv.com/live/1020160907131524.html http://sports.letv.com/match/1002825003.html
function letyZbParsePlay(origUrl){
    origUrl=origUrl.replace("letv.com","le.com");
	origUrl=origUrl.replace("m.sports.le.com","sports.le.com");
	if( - 1 != url.indexOf('sports.le.com/match')){
		var htmlContent = urlRequestWithPara(origUrl,"Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/47.0.2526.106 Safari/537.36","User-Agent");
		console.log("htmlContent======>"+htmlContent);
		var liveId = htmlContent.substring(htmlContent.indexOf("liveId: '")+9);
		if (liveId.length <= 0){
			return new Array();
		}
		liveId = liveId.substring(0, liveId.indexOf("',"));
		if (liveId.length <= 0||liveId.length > 100){
			return new Array();
		}
        console.log("liveida======>"+liveId);
	}else{
		var liveId = origUrl.substring(origUrl.indexOf("live/")+5);
		if (liveId.length <= 0){
			return new Array();
		}
		liveId = liveId.substring(0, liveId.indexOf(".html"));
		if (liveId.length <= 0){
			return new Array();
		}
        console.log("liveidb======>"+liveId);
	}
	vidurl = 'http://player.pc.le.com/player/startup_by_pid/1001/'+liveId+'?host=sports.le.com';
	vidContent = urlRequestWithPara(vidurl,"Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/47.0.2526.106 Safari/537.36","User-Agent");
    console.log("vidContent======>"+vidContent);
    vidjsonContent = eval('(' + vidContent + ')');
    console.log("vidjsonContent======>"+vidjsonContent.rows.length);
    if (vidjsonContent.rows.length <= 0) {
        return '';
    }else if(vidjsonContent.rows.length == 1) {
        vid=vidjsonContent.rows[0].streamName;
    }else if(vidjsonContent.rows.length == 2) {
        vid=vidjsonContent.rows[1].streamName;
    }else {
        vid=vidjsonContent.rows[2].streamName;
    }
	if (vid==''||vid==null){
		return new Array();
	}
    var time = (Date.parse(new Date()))/1000;
    var skey = vid + "," + time + ",a2915e518ba60169f77";
    var key = getMD5(skey);
    lurl = "http://live.gslb.letv.com/gslb?stream_id=" + vid + "&tag=live&ext=m3u8&sign=live_photerne&tm=" + time + "&p1=0&p2=00&p3=001&splatid=1003&ostype=andriod&hwtype=un&platid=10&playid=1&termid=2&pay=0&key=" + key + "&expect=3&format=1";
    ipContent = getUrlContent(lurl);
    if(ipContent.length <= 0){
        return '';
    }
    ipContent = ipContent.toJS();   
    jsonContent = eval('(' + ipContent + ')');
    if (jsonContent.nodelist.length <= 0) {
        console.log('url:' + ipContent);
        return '';
    }
    vodurl = jsonContent.location;
    urlarr = new Array();
    urlarr.push(vodurl);
    return urlarr;
}

function lsyVodParsePlay(origUrl) {
	var uu = origUrl.substring(origUrl.indexOf("?uu=")+4);
	uu = uu.substring(0, uu.indexOf("&"));
	var vu = origUrl.substring(origUrl.indexOf("&vu=")+4);
	vu = vu.substring(0, vu.indexOf("&"));
	var ran = (Date.parse(new Date()))/1000;
	var sign = getMD5('cfhtml5_iosran'+ran+'uu'+uu+'bversafari8.0vu'+vu+'fbeh5player12c43eccf2bec3300344');
	var enurl = encodeURIComponent(origUrl);
	var lurl = "http://api.letvcloud.com/gpc.php?format=jsonp&ver=2.1&playid=0&cf=html5_ios&ran="+ran+"&pver=H5_Vod_20160225_4.1.7&bver=safari8.0&uuid=54A7F0014D077E9A9AC289BC2828AB6C_0&pf=html5&spf=1&uu="+uu+"&vu="+vu+"&sign="+sign+"&page_url="+enurl+"&callback=letvcloud14564575188098";
	content = getUrlContent(lurl);
	if(content.length <= 0){
		return new Array();
	}
	var cont = content.substring(content.indexOf("(")+1);
	cont = cont.substring(0, cont.lastIndexOf(")"));
	jsonObject = eval('('+cont+')');
	urlArray = new Array();
	urlArray[0] = base64decode(jsonObject.data.video_info.media.low.play_url.main_url)+"#标清";
	urlArray[1] = base64decode(jsonObject.data.video_info.media.high.play_url.main_url)+"#高清";
	urlArray[2] = base64decode(jsonObject.data.video_info.media.super.play_url.main_url)+"#超清";
	return urlArray;
}

//获取清晰度类型
function getType(type) {
    typeInfo = '';
    if ('mp4_1000' == type){
        typeInfo = '标清';
    } else if('mp4_1300' == type){
        typeInfo = '高清';
    } else if ('mp4_1800' == type){
        typeInfo = '超清';
    } else if ('mp4_350' == type){
        typeInfo = '清晰';
    } else if ('mp4_180' == type){
        typeInfo = '流畅';
    }
    return typeInfo;
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
