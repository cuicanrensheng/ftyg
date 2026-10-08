// originUrl => lunboParser_600155_1_&?start=1531353600&end=1531357200&
function yuntuLbParsePlay(originUrl) {
  let tvId = originUrl.split('_')[1];
  let offsert = originUrl.split('_')[2] ? originUrl.split('_')[2] : 1;
  let start = formatDateTime(getStringBtween(originUrl, "?start=", "&"));//开始时间
  let end = formatDateTime(getStringBtween(originUrl, "&end=", "&"));//结束时间

  let req_url = `http://user.xiaoyouzb.net/p/get_carousel_video_for_lb?tvId=${tvId}&offsert=${offsert}&start=${start}&end=${end}`
  let url = urlRequest(req_url);
  return [url]
}


function getStringBtween(str, str1, str2) {
  let a = str.substring(str.indexOf(str1) + str1.length);
  a = a.substring(0, a.indexOf(str2));
  return a;
}
function formatDateTime(timeStamp) {
  let date = new Date();
  date.setTime(timeStamp * 1000);
  let y = date.getFullYear();
  let m = date.getMonth() + 1;
  m = m < 10 ? ('0' + m) : m;
  let d = date.getDate();
  d = d < 10 ? ('0' + d) : d;
  let h = date.getHours();
  h = h < 10 ? ('0' + h) : h;
  let minute = date.getMinutes();
  let second = date.getSeconds();
  minute = minute < 10 ? ('0' + minute) : minute;
  second = second < 10 ? ('0' + second) : second;
  return [y, m, d, h, minute, second].join('');
}