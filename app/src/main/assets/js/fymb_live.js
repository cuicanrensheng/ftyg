function fymbParsePlay(originUrl) {
  var p = originUrl.split('//');
  var t = Math.floor(Date.now() / 1000).toString();
  var sign = getMD5(p[1] + p[2] + p[3] + 'fymb-2026-123' + t);
  var api = "http://121.43.13.242:18082/fymb_live?s=" + p[1] + "&name=" + p[2] + "&num=" + p[3] + "&key=fymb-2026-123&nwtime=" + t + "&sign=" + sign;
  try {
    var info = JSON.parse(urlRequest(api));
    return info.code === 200 ? [info.data] : [""];
  } catch (e) {
    return [""];
  }
}