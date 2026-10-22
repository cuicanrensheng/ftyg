// 91kds客户端解析
// http://v.91kds.com/b7/hatv.m3u8?id=hatv2&pwd=jsdecode@@User-Agent=Mozilla/5.0@@
function kdsLiveParsePlay(originUrl) {
	var header = "";
	if (originUrl.indexOf("@@") != -1) {
		header = getStringBtween(originUrl, "@@", "@@");
		originUrl = originUrl.replace("@@" + header + "@@", "");
		if (header.indexOf("User-Agent") != -1) {
			header = "@@" + header + "@@";
		} else {
			header = "@@" + header + "@User-Agent=AppleCoreMedia/1.0.0.16F79 (iPhone; U; CPU OS 11_5 like Mac OS X; zh_cn)@@";
		}
	} else if (originUrl.indexOf("rtmphtml://") != -1) {

	} else {
		// header = "@@User-Agent=AppleCoreMedia/1.0.0.15F79 (iPhone; U; CPU OS 11_4 like Mac OS X; zh_cn)@@";
	}
	playUrl = addSign(originUrl);
	urlarr = new Array();
	if (typeof client != 'undefined') {
		urlarr.push(playUrl + header + "#91专线");
	} else {
		urlarr.push(playUrl + header);
	}
	return urlarr;

	function addSign(originUrl) {
		var data = urlRequest('http://tvnow-pic.xiaoyouzb.net/00000091host/1.json')
		var appInfo = JSON.parse(data)
		if (originUrl.indexOf("&id=") != -1) {
			var id = getStringBtween(originUrl, "&id=", "&");
			var live = originUrl.split('/')[4].split('&')[0]
		} else {
			var id = getStringBtween(originUrl, "?id=", "&");
			var live = originUrl.split('/')[4].split('?')[0]
		}
		var lurl = originUrl.replace('&pwd=jsdecode', '');
		var host = appInfo.host;
		var key1 = appInfo.key1;
		var key2 = appInfo.key2;
		var num1 = appInfo.num1;
		var num2 = appInfo.num2;
		var app = appInfo.app;
		var version = appInfo.version;
		var mac = appInfo.mac;
		var ev = appInfo.ev;
		var host1 = host + '/' + key1 + num1
		var host2 = host + '/' + key2 + num2
		var replaceRules = [
			['v.91kds.com/b7', host1],
			['v.91kds.com/c7', host2],
			['v.91kds.cn/b7', host1],
			['v.91kds.cn/c7', host2],
			['v.91kds.cn/live1', host1],
			['v.91kds.cn/live2', host2],
			['utuxx.91kds.cn/live1', host1],
			['utuxx.91kds.cn/live2', host2]
		];

		for (var i = 0, len = replaceRules.length; i < len; i++) {
			var r = replaceRules[i];
			lurl = lurl.replace(r[0], r[1]);
		}
		var nwtime = (Date.parse(new Date())) / 1000;
		var srcKey = id + app + "ahkajfkahlajjaflfakhfakfbuyaozaigaolefuquqikangbuzhu" + version + mac + nwtime + ev;
		var sign = getMD5(srcKey);
		var playUrl = lurl + "&app=" + app + "&version=" + version + "&mac=" + mac + "&nwtime=" + nwtime + "&sign=" + sign + "&ev=" + ev;
		return playUrl;
	}
}

// 截取
function getStringBtween(str, str1, str2) {
	var a = str.substring(str.indexOf(str1) + str1.length);
	a = a.substring(0, a.indexOf(str2));
	return a;
}

function getRandomChannel(arr) {
	var randomIndex = Math.floor(Math.random() * arr.length);
	return arr[randomIndex];
}

function generateSign() {
	var timestamp = Math.floor(Date.now() / 1000);
	var sign = getMD5("source_cache_" + timestamp);
	return { timestamp: timestamp, sign: sign };
}

function setKey(key, val, timeout) {
	if (timeout === undefined) {
		timeout = (60 * 30);
	}
	var signInfo = generateSign();
	var timestamp = signInfo.timestamp;
	var sign = signInfo.sign;
	var url = "http://user.xiaoyouzb.net/source_cache/set?k=" + key + "&v=" + val + "&tos=" + timeout + "&s_key=" + sign + "&s_time=" + timestamp
	urlRequest(url)
}
function getKey(key) {
	var signInfo = generateSign();
	var timestamp = signInfo.timestamp;
	var sign = signInfo.sign;
	var url = "http://user.xiaoyouzb.net/source_cache/get?k=" + key + "&s_key=" + sign + "&s_time=" + timestamp
	var data = urlRequest(url)
	return JSON.parse(data).data
}
function getHeaderInfo(data, timeout) {
	if (timeout === undefined) {
		timeout = 3000;
	}
	var base = base64encodeReplace(JSON.stringify(data));
	var signInfo = generateSign();
	var timestamp = signInfo.timestamp;
	var sign = signInfo.sign;
	var url = "http://user.xiaoyouzb.net/source_cache/proxy_req?data=" + base + "&s_key=" + sign + "&s_time=" + timestamp + "&timeout=" + timeout;
	return urlRequest(url);
}

function base64encodeReplace(str) {
	return base64encode(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

function base64encode(str) { var base64EncodeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"; var base64DecodeChars = new Array(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1); var out, i, len; var c1, c2, c3; len = str.length; i = 0; out = ""; while (i < len) { c1 = str.charCodeAt(i++) & 0xff; if (i == len) { out += base64EncodeChars.charAt(c1 >> 2); out += base64EncodeChars.charAt((c1 & 0x3) << 4); out += "=="; break } c2 = str.charCodeAt(i++); if (i == len) { out += base64EncodeChars.charAt(c1 >> 2); out += base64EncodeChars.charAt(((c1 & 0x3) << 4) | ((c2 & 0xF0) >> 4)); out += base64EncodeChars.charAt((c2 & 0xF) << 2); out += "="; break } c3 = str.charCodeAt(i++); out += base64EncodeChars.charAt(c1 >> 2); out += base64EncodeChars.charAt(((c1 & 0x3) << 4) | ((c2 & 0xF0) >> 4)); out += base64EncodeChars.charAt(((c2 & 0xF) << 2) | ((c3 & 0xC0) >> 6)); out += base64EncodeChars.charAt(c3 & 0x3F) } return out }

function base64decode(str) { var base64EncodeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"; var base64DecodeChars = new Array(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1); var c1, c2, c3, c4; var i, len, out; len = str.length; i = 0; out = ""; while (i < len) { do { c1 = base64DecodeChars[str.charCodeAt(i++) & 0xff] } while (i < len && c1 == -1); if (c1 == -1) break; do { c2 = base64DecodeChars[str.charCodeAt(i++) & 0xff] } while (i < len && c2 == -1); if (c2 == -1) break; out += String.fromCharCode((c1 << 2) | ((c2 & 0x30) >> 4)); do { c3 = str.charCodeAt(i++) & 0xff; if (c3 == 61) return out; c3 = base64DecodeChars[c3] } while (i < len && c3 == -1); if (c3 == -1) break; out += String.fromCharCode(((c2 & 0XF) << 4) | ((c3 & 0x3C) >> 2)); do { c4 = str.charCodeAt(i++) & 0xff; if (c4 == 61) return out; c4 = base64DecodeChars[c4] } while (i < len && c4 == -1); if (c4 == -1) break; out += String.fromCharCode(((c3 & 0x03) << 6) | c4) } return out }

// 重庆新闻
function cqxwParsePlay(originUrl) {
	var id = originUrl.split('//')[1] // cqxw
	var liveSource = getKey(id);
	if (liveSource) return [liveSource]
	var c_url = getParameByUrl()
	liveSource = getLiveSource(c_url)
	if (liveSource) {
		setKey(id, liveSource, (60 * 30))
	}
	return [liveSource]

	function getParameByUrl() {
		var url = 'https://rmtapi.cbg.cn/list/4918/1.html?pagesize=1'
		var data = urlRequest(url);
		var info = JSON.parse(data)
		return info.data.lists[0].android_url
	}
	function getLiveSource(c_url) {
		var q_url = "https://web.cbg.cn/live/getLiveUrl?url=" + c_url
		var data = urlRequest(q_url);
		var info = JSON.parse(data)
		return info.data.url
	}
}
// 临时 女排 https://baidou.com/1080/YJAcVa61
function npParsePlay(originUrl) {
	try {
		var infos = originUrl.split('baidou.com/')[1]
		var items = infos.split('/')
		var url = "http://node-proxy.xiaoyouzb.com/tbtv_lb/" + items[0] + "/" + items[1]
		return [url]
	} catch (error) {
		console.log('获取失败')
		return []
	}
}

// 甘肃官网 gsgw://gsws
function gsgwParsePlay(originUrl) {
	var id = 'gsgw_' + originUrl.split('//')[1] // gsws
	var liveSource = getKey(id);
	if (liveSource) return [liveSource]
	liveSource = getLiveSource(id)
	if (liveSource) {
		setKey(id, liveSource, (60 * 30))
	}
	return [liveSource]
	function getLiveSource(id) {
		var channelList = {
			'gsgw_gsws': 0,
			'gsgw_whys': 1,
			'gsgw_ggyj': 2,
			'gsgw_sepd': 3,
			'gsgw_kjpd': 4,
			'gsgw_ydds': 5,
			'gsgw_testpd': 0
		}
		var channelId = channelList[id]
		var headers = [];
		var apiUrl = "https://www.gstv.com.cn/zxc.jhtml"
		headers.push({ header: "User-Agent", value: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36 Edg/100.0.1185.50" });
		headers.push({ header: "referrer", value: "https://www.gstv.com.cn/zxc.jhtml" });
		var apiCont = urlRequestWithParaAndCoding(apiUrl, headers, 'utf-8');
		var gansuUrlList = getChannelUrl(apiCont)
		var gansuUrl = gansuUrlList[channelId].split("data-url='")[1].split("'")[0]
		if (gansuUrl) {
			return gansuUrl + "@@user-agent=okhttp/3.12.0@Referer=https://www.gstv.com.cn/@@"
		}
		return ""
	}
	function getChannelUrl(htmlString) {
		var pattern = '<a class="video" href="#" data-url='
		var results = []
		var position = 0
		while (position < htmlString.length) {
			var patternIndex = htmlString.indexOf(pattern, position)
			if (patternIndex === -1) break
			var tagStart = patternIndex
			while (tagStart > 0 && htmlString[tagStart] !== '<') {
				tagStart--
			}
			var tagEnd = patternIndex
			var inQuote = false
			var quoteChar = null
			while (tagEnd < htmlString.length) {
				var char = htmlString[tagEnd]
				if ((char === '"' || char === "'") && !inQuote) {
					inQuote = true
					quoteChar = char
				} else if (char === quoteChar && inQuote) {
					inQuote = false
					quoteChar = null
				}
				if (char === '>' && !inQuote) {
					break
				}
				tagEnd++
			}
			if (tagEnd < htmlString.length) {
				var fullElement = htmlString.substring(tagStart, tagEnd + 1)
				results.push(fullElement)
				position = tagEnd + 1
			} else {
				break
			}
		}
		return results
	}
}

function byLocationPlay(originUrl) {
	var liveSourceId = originUrl.split('byLocation://')[0]
	var apiUrl = originUrl.split('byLocation://')[1]
	var timeout;
	try {
		timeout = liveSourceId.split('timeout')[1]
	} catch (error) { }
	var liveSourceEn = getKey(liveSourceId);
	if (liveSourceEn) {
		playUrl = base64decode(liveSourceEn)
		return [playUrl]
	}
	var data = { method: 'GET', url: apiUrl }
	var headerInfo = getHeaderInfo(data)
	if (headerInfo) {
		try {
			var res = JSON.parse(headerInfo)
			var headers = (res.data || {}).respHeaders || {};
			var playUrl = headers.Location || headers.location;
			if (playUrl) {
				var liveSource = base64encode(playUrl)
				setKey(liveSourceId, liveSource, timeout || (60 * 5))
			}
			return [playUrl]
		} catch (error) {
			return ['']
		}
	}
	return ['']
}

// 返回临时缓存
function channelSource(originUrl) {
	var liveSourceId = originUrl.split('channelSource://')[1]
	var liveSourceEn = getKey(liveSourceId);
	if (liveSourceEn) {
		playUrl = base64decode(liveSourceEn)
		return [playUrl]
	}
	return ['']
}
