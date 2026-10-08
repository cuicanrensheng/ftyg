var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
var AL = "zh-CN,zh;q=0.9,en;q=0.8"
var CC = "no-cache"
function iyfParsePlay(originUrl) {
  var id = originUrl.split('://')[1];
  var keys = getSignedVideoUrl(id);
  var playInfo = fetchPlayInfo(id, keys);
  var cdnUrl = extractCdnUrl(playInfo);
  return [signUrl(cdnUrl, keys)];

  function getSignedVideoUrl(id) {
    var html = urlRequestWithParaAndCoding("https://www.iyf.tv/play/" + id, [
      { header: 'User-Agent', value: UA },
      { header: 'Accept-Language', value: AL },
      { header: 'Cache-Control', value: CC },
      { header: 'Accept', value: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" }
    ], 'utf-8');
    var m = html.match(/var injectJson\s*=\s*(\{[\s\S]+?\});/);
    if (!m) return '';
    var json;
    try { json = JSON.parse(m[1]) } catch (e) { return '' }
    var cfg = json && json.config && json.config[0] && json.config[0].pConfig;
    if (!cfg) return '';
    return {
      publicKey: cfg.publicKey,
      privateKey: cfg.privateKey && cfg.privateKey.length ? cfg.privateKey[0] : ''
    };
  }

  function signUrl(url, keys) {
    var q = getQuery(url);
    if (!q || q.indexOf("pub=") !== -1 || q.indexOf("vv=") !== -1) return url;
    return url + "&vv=" + getMD5(keys.publicKey + "&" + q.toLowerCase() + "&" + keys.privateKey) + "&pub=" + keys.publicKey;
  }

  function fetchPlayInfo(id, keys) {
    if (!id) return null;
    function doReq(a) {
      return urlRequestWithParaAndCoding(signUrl("https://m10.iyf.tv/v3/video/play?cinema=1&id=" + id + "&a=" + a + "&lang=none&usersign=1&region=US&device=1&isMasterSupport=1", keys), [
        { header: 'User-Agent', value: UA },
        { header: 'Accept-Language', value: AL },
        { header: 'Cache-Control', value: CC },
        { header: 'Accept', value: "application/json, text/plain, */*" }
      ], 'application/json');
    }
    var raw = doReq(1);
    if (!raw) return null;
    var info;
    try { info = JSON.parse(raw) } catch (e) { return null }
    if (info && info.data && info.data.code === 1) {
      raw = doReq(0);
      if (!raw) return null;
      try { info = JSON.parse(raw) } catch (e) { return null }
    }
    return info;
  }

  function getQuery(url) {
    var idx = url.indexOf("?");
    if (idx === -1) return "";
    var parts = url.substring(idx + 1).split("&");
    var q = "";
    for (var i = 0; i < parts.length; i++) {
      var kv = parts[i].split("=");
      q += kv[0];
      for (var j = 1; j < kv.length; j++) q += "=" + decodeURIComponent(kv[j]).split("+").join(" ");
      q += "&";
    }
    return q.slice(0, -1);
  }

  function extractCdnUrl(playInfo) {
    if (!playInfo || !playInfo.data || !playInfo.data.info || !playInfo.data.info.length) return null;
    var clarity = playInfo.data.info[0].clarity;
    if (!clarity || Object.prototype.toString.call(clarity) !== '[object Array]') return null;
    for (var i = 0; i < clarity.length; i++) {
      var p = clarity[i] && clarity[i].path;
      if (p && p.result) return p.isHls ? (p.rtmp || p.result) : p.result;
    }
    return null;
  }
}