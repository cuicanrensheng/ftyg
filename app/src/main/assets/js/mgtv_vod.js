//http://www.mgtv.com/b/311928/3849296.html

function MgVodParsePlay(origUrl) {
	var urlArr = origUrl.split("/");
	var vid = urlArr[urlArr.length -1].split(".")[0];
	var header = [];
	header.push({header:"Cookie", value:"PM_CHKID=1"});
	var clit = Math.floor((new Date).getTime() / 1E3);
	// var clit =  ~~(+new Date / 1e3);
	var did = createuuid(8) + "-" + createuuid(4) + "-" + createuuid(4) + "-" + createuuid(4) + "-" + createuuid(12);
	var tk2 = "did="+ did + "|pno=1030|ver=0.3.0301|clit=" + clit;
	var encTk2 = encodeNewTk2(tk2);
	var url = "https://pcweb.api.mgtv.com/player/video?video_id=" + vid + "&tk2=" + encodeURIComponent(encTk2);
	var content = urlRequestWithParaAndCoding(url, header, "utf-8");
	var jsonObject = eval('(' + content + ')');
	var pm2 = jsonObject.data.atc.pm2;
	var url2 = "https://pcweb.api.mgtv.com/player/getSource?video_id=" + vid + "&tk2=" + encodeURIComponent(encTk2) + "&pm2=" + encodeURIComponent(pm2);
	var content2 = urlRequestWithParaAndCoding(url2, header, "utf-8");
	var jsonObject2 = eval('(' + content2 + ')');
	var stream = jsonObject2.data.stream;
	var domain1 = jsonObject2.data.stream_domain[0];
	var urlArray = new Array();
	for (var i = 0; i < stream.length; i++) {
		var name = stream[i].name;
		var url = domain1 + stream[i].url;
		if (url != "") {
			rlog("url=>" + url);
			var header = [];
			var r_header = {
				'referer': origUrl,
				'user-agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/71.0.3578.98 Chrome/71.0.3578.98 Safari/537.36'
			};
			for (var variable in r_header) {
				header.push({'header': variable, 'value': r_header.variable})
			}
			if (name == "超清") {
				var m3u8Url = getNewMgVodUrl(url, header);
				if ("busy" == m3u8Url) {
					continue;
				}
				urlArray.push("1" + m3u8Url + "#" + name);
			} else if (name == "高清") {
				var m3u8Url = getNewMgVodUrl(url, header);
				if ("busy" == m3u8Url) {
					continue;
				}
				urlArray.push("2" + m3u8Url + "#" + name);
			} else if (name == "标清") {
				var m3u8Url = getNewMgVodUrl(url, header);
				if ("busy" == m3u8Url) {
					continue;
				}
				urlArray.push("3" + m3u8Url + "#" + name);
			} else {
				continue;
			}
		}
	}
	urlArray = urlArray.sort();
	var playArr = new Array();
	for (var j = 0; j < urlArray.length; j++) {
		playArr.push(urlArray[j].substring(1));
	}
	return playArr;
}

function MgVodParsePlayOld(origUrl) {
	var cont = getUrlContent(origUrl);
	var vid = cont.substring(cont.indexOf("vid: ") + 5);
	vid = vid.substring(0, vid.indexOf("\,"));
	var cid = cont.substring(cont.indexOf("cid: ") + 5);
	cid = cid.substring(0, cid.indexOf("\,"));
	if (vid == null || vid == '') {
		return new Array();
	}
	rlog("vid=======>" + vid);
	var did = createuuid(8) + "-" + createuuid(4) + "-" + createuuid(4) + "-" + createuuid(12);
	var suuid = createuuid(8) + "-" + createuuid(4) + "-" + createuuid(4) + "-" + createuuid(12);
	var pno = "1000";
	var ver = "0.3.0001";
	var clit = Math.floor((new Date).getTime() / 1E3);
	var dic1 = {
		"value": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/54.0.2840.98 Safari/537.36",
		"header": "User-Agent"
	};
	var dic2 = {
		"value": "1",
		"header": "Upgrade-Insecure-Requests"
	};
	var dic3 = {
		"value": "PM_CHKID=f8b8fc7284915399",
		"header": "Cookie"
	};
	var arr = new Array();
	arr.push(dic1);
	arr.push(dic2);
	arr.push(dic3);
	var tk2 = encodeTk2("did="+did+"|clit="+clit+"|ver="+ver+"|pno="+pno);
	var lurl = "https://pstream.api.mgtv.com/player/video?video_id="+vid+"&did="+did+"&suuid="+suuid+"&cid="+cid+"&tk2="+tk2+"&_support=10000000";
	rlog("lurl===>" + lurl);
	var content = urlRequestWithParaAndCoding(lurl, arr, "utf-8");
	jsonObject = eval('(' + content + ')');
	var urlArray = new Array();
	var pm2 = jsonObject.data.atc.pm2;
	//var newtk2 = encodeTk2("clit=" + clit);
	var apiUrl = "https://pstream.api.mgtv.com/player/getSource?video_id="+vid+"&did="+did+"&suuid="+suuid+"&pm2="+pm2+"&tk2="+tk2+"&_support=10000000";
	rlog("apiUrl===>" + apiUrl);
	var apiCont = urlRequestWithParaAndCoding(apiUrl, arr, "utf-8");
	rlog("apiCont===>" + apiCont);
	urlJson = eval('(' + apiCont + ')');
	var stream = urlJson.data.stream;
	for (var i = 0; i < stream.length; i++) {
		var name = stream[i].name;
		var url = stream[i].url;
		if (url != "") {
			if (name == "超清") {
				// urlArray.push("1" + getMgVodUrl(url,did,suuid,arr) + "#" + name);
			} else if (name == "高清") {
				urlArray.push("2" + getMgVodUrl(url,did,suuid,arr) + "#" + name);
			} else if (name == "标清") {
				urlArray.push("3" + getMgVodUrl(url,did,suuid,arr) + "#" + name);
			} else {
				urlArray.push("4" + getMgVodUrl(url,did,suuid,arr) + "#" + name);
			}
		}
	}
	urlArray = urlArray.sort();
	var playArr = new Array();
	for (var j = 0; j < urlArray.length; j++) {
		playArr.push(urlArray[j].substring(1));
	}
	return playArr;
}

function getNewMgVodUrl(url, header) {
	var content1 = urlRequestWithParaAndCoding(url, header, "utf-8");
	rlog('playUrl:' + content1);
	jsonObject1 = eval('(' + content1 + ')');
	// while (! jsonObject1.hasOwnProperty('info')) {
	// 	var content1 = urlRequestWithParaAndCoding(url, header, "utf-8");
	// 	jsonObject1 = eval('(' + content1 + ')');
	// }
	// 不要写while loop，我们不搞DDoS攻击！笑～　这个API是有请求频率限制的，望周知。
	var info = jsonObject1.info;
	info = decodeURIComponent(info);
	return info;
}

function getMgVodUrl(url,did,suuid,arr) {
	var ld = Math.floor((new Date).getTime());
	var apiUrl = "https://web-disp.titan.mgtv.com" + url + "&did="+did+"&suuid="+suuid+"&ld="+ld;
	var content1 = urlRequestWithParaAndCoding(apiUrl, arr, "utf-8");
	rlog('apiUrl:' + apiUrl);
	rlog('content1:' + content1);
	jsonObject1 = eval('(' + content1 + ')');
	var info = jsonObject1.info;
	info = decodeURIComponent(info);
	return info;
}

function encodeNewTk2(b) {
	return base64encode(b).replace(/\+/g, "_").replace(/\//g, "~").replace(/=/g, "-").split("").reverse().join("");
}

function encodeTk2(b) {
	return base64encode(b).replace(/\+/g, "_").replace(/\//g, "~").replace(/=/g, "-").split("").reverse().join("");
}

function base64encode(str) {
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

function createuuid(num) {
	var arr = ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"];
	var str = "";
	for (var i = 0; i < num; i++) {
		var rand = parseInt(15 * Math.random());
		str += arr[rand];
	}
	return str;
}

function getStringBtween(str, str1, str2) {
	var a = str.substring(str.indexOf(str1) + str1.length);
	a = a.substring(0, a.indexOf(str2));
	return a;
}

function rlog(msg) {
	if(true) {
		return;
	}
	var api = 'http://42.121.111.105:3553/monitor/client_rlog.php?msg=' + encodeURIComponent(msg);
	urlRequest(api);
}
