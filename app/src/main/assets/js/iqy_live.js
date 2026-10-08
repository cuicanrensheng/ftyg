function iqyLiveParsePlay(originUrl) {
  var vid = originUrl.replace('iqy_live://', '');
  var tm = Date.now() / 1000;
  var host = 'https://live.video.iqiyi.com';
  var params = {
    lp: vid,
    src: '01010031010000000000',
    uid: '',
    rateVers: 'PC_QIYI_3',
    k_uid: getMacid(24),
    qdx: 'n',
    qdv: 3,
    qd_v: 1,
    dfp: getRandomStr(66),
    v: 1,
    k_err_retries: 0,
    tm: Math.floor(tm + 1)
  };
  var src = '/live?' + getQueryString(params)
  var vf = cmd5x(src);
  var reqUrl = host + src + '&vf=' + vf;
  var st = Math.floor(tm * 1000);
  var et = Math.floor((tm + 1296000) * 1000);
  var c_dfp = '__dfp=' + params.dfp + '@' + et + '@' + st;
  var headers = []
  headers.push({ header: "Cookie", value: c_dfp });
  debugger
  var info = {
    stream_types: [],
    streams: {}
  }
  var data = urlRequestWithParaAndCoding(reqUrl, headers, 'utf-8');
  var streamsData = JSON.parse(data).data.streams
  var ids = ['TD', 'BD', 'HD', 'SD'];
  var type_2_id = {
    'RESOLUTION_1080P': 'BD',
    'RESOLUTION_720P': 'TD',
    'HIGH_DEFINITION': 'HD',
    'SMOOTH': 'SD'
  }
  for (var i = 0; i < streamsData.length; i++) {
    var stream = streamsData[i];
    if (stream.formatType === 'TS') {
      var m3u8 = stream.url;
      var streamType = stream.steamType;
      var streamProfile = stream.screenSize;
      var streamId = type_2_id[streamType];
      if (streamId) {
        info.stream_types.push(streamId);
        info.streams[streamId] = {
          video_profile: streamProfile,
          container: 'm3u8',
          src: m3u8,
          size: Infinity
        };
      }
    }
  }
  info.stream_types = info.stream_types.sort(function (a, b) {
    return ids.indexOf(a) - ids.indexOf(b);
  });
  if (info.stream_types.length > 0) {
    var url = info.streams[info.stream_types[0]].src;
    return [url]
  }
  return []
}

function getQueryString(params) {
  var queryString = ''
  for (var key in params) {
    if (params.hasOwnProperty(key)) {
      if (queryString.length > 0) {
        queryString += '&';
      }
      queryString += encodeURIComponent(key) + '=' + encodeURIComponent(params[key]);
    }
  }
  return queryString
}

// 生成指定长度的随机字符串
function getRandomStr(l) {
  var chars = 'abcdefghijklnmopqrstuvwxyz0123456789';
  var string = '';
  for (var i = 0; i < l; i++) {
    string += chars[Math.floor(Math.random() * chars.length)];
  }
  return string;
}
// 获取 macid
function getMacid(l) {
  if (l === undefined) {
    l = 32;
  }
  return getRandomStr(l);
}

function cmd5x(s) {
  return getMD5(s + 'h2l6suw16pbtikmotf0j79cej4n8uw13');
}

function md5x(s) {
  return getMD5(s + '1j2k2k3l3l4m4m5n5n6o6o7p7p8q8q9r');
}
