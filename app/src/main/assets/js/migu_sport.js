// migu_sport://120000450555
function miguSportParsePlay(originUrl) {
  let mgdbId = originUrl.split('://')[1];
  // let pidUrl = `https://app-sc.miguvideo.com/vms-match/v5/staticcache/basic/basic-data/${mgdbId}/miguvideo`
  let pidUrl = `https://vms-sc.miguvideo.com/vms-match/v6/staticcache/basic/basic-data/${mgdbId}/miguvideo`
  // var list = JSON.parse(urlRequest(pidUrl)).body.trailers; // v5
  var list = JSON.parse(urlRequest(pidUrl)).body; // v6
  // let pID = list[0].pID; // v5
  let pID = list.pId; // v6
  let m3u8Url = getUrl(pID);
  let result = urlRequest(m3u8Url).replace('\n', '')
  return [result];



  function getUrl(pID) {
    let clientId = getClientId();
    let apiUrl = `https://webapi.miguvideo.com/gateway/playurl/v3/play/playurl?contId=${pID}&rateType=3&clientId=${clientId}&startPlay=true&flvEnable=true&xh265=false&chip=mgwww&channelId=0132_10010001005`
    let headers = [];
    headers.push({ header: "x-up-client-channel-id", value: "0132_10010001005" });
    headers.push({ header: "Referer", value: "https://www.miguvideo.com/" });
    headers.push({ header: "terminalId", value: "www" });
    let apiCont = urlRequestWithParaAndCoding(apiUrl, headers, "utf-8");
    let list = JSON.parse(apiCont);
    return getDdCalcu(list.body.urlInfo.url);
  }
  function getClientId() {
    for (var t = "", r = "0123456789abcdef", n = 0; n < 32; n++) {
      t += r.charAt(Math.random() * (r.length - 1) >> 0);
    }
    return t
  }
  function getDdCalcu(url) {
    const puData = getQueryString('puData', url);
    const ProgramID = getQueryString('ProgramID', url);
    const Program = 'yzwxcdwbgh'.split('');
    const arr_key = [];
    const s = puData.length;
    for (let v = 0; v < s / 2; v++) {
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
          let chars = Program[ProgramID[1]];
          arr_key.push(chars);
          break;
        case 4:
          arr_key.push(arrkey(v));
          break;
      }
    }
    const ddCalcu = arr_key.join('');
    const p = `${url}&ddCalcu=${ddCalcu}&sv=10000&crossdomain=www&ct=www`;
    return p
  }
  function arrkey(v) {
    const put = ['z', 'y', '0', 'z'];
    const mark = put[v - 1];
    return mark;
  }
  function getQueryString(name, url) {
    var reg = new RegExp("(^|&)" + name + "=([^&]*)(&|$)", "i");
    var r = url.substr(1).match(reg);
    if (r != null) return unescape(decodeURI(r[2])); return null;
  }
}
