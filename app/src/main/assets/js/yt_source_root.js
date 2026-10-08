

function RootFunction(url) {
  var arr = new Array();
  if (- 1 != url.indexOf('cctv://')) {
    arr.push("ysp_live.js");
    arr.push("cctvgwParsePlay");
  } else if (- 1 != url.indexOf('fymb://')) { // 风云秒播
    arr.push("fymb_live.js");
    arr.push("fymbParsePlay");
  } else if (-1 != url.indexOf('rrsp://')) { // 人人视频
    arr.push('tv_live.js');
    arr.push('rrspParsePlay');
  } else if (- 1 != url.indexOf('fgtv://')) {
    arr.push("ysp_live.js");
    arr.push("fgtvParsePlay");
  } else if (- 1 != url.indexOf('channelSource://')) { // 临时缓存
    arr.push("replay.js");
    arr.push("channelSource");
  } else if (- 1 != url.indexOf('iyf://')) { // 爱壹帆
    arr.push("iyf_live.js");
    arr.push("iyfParsePlay");
  } else if (- 1 != url.indexOf('tvpass://')) {
    arr.push("tvpass_live.js");
    arr.push("tvpassParsePlay");
  } else if (- 1 != url.indexOf('byLocation://')) {
    arr.push("replay.js");
    arr.push("byLocationPlay");
  } else if (- 1 != url.indexOf('migu_sport://')) {
    arr.push("migu_sport.js");
    arr.push("miguSportParsePlay");
  } else if (- 1 != url.indexOf('http://v.qq.com/x/live/tv.html?type=')) {
    arr.push("other_live.js");
    arr.push("yuntuZdyParsePlay");
  } else if (- 1 != url.indexOf('&pwd=yuntutv')) {
    arr.push("other_live.js");
    arr.push("yuntuLiveParsePlay");
  } else if (- 1 != url.indexOf('cctvback://')) {
    arr.push("other_vod.js");
    arr.push("cctvBackParse");
  } else if ((-1 != url.indexOf('lunboParser'))) { // 轮播回看 lunboParser_600155_1
    arr.push("lunbo.js");
    arr.push("yuntuLbParsePlay");
  } else if ((-1 != url.indexOf('&?start='))) {
    arr.push("other_live.js");
    arr.push("yuntuLbkParsePlay");
  } else if (- 1 != url.indexOf('yuntv.letv.com')) {
    arr.push("letv_vod.js");
    arr.push("lsyVodParsePlay");
  } else if (- 1 != url.indexOf('sports.le.com/match') || - 1 != url.indexOf('sports.le.com/live') || - 1 != url.indexOf('sports.letv.com/live') || - 1 != url.indexOf('sports.letv.com/match')) {
    arr.push("letv_vod.js");
    arr.push("letyZbParsePlay");
  } else if (- 1 != url.indexOf('sports.le.com') || - 1 != url.indexOf('sports.letv.com')) {
    arr.push("letv_vod.js");
    arr.push("letyVodParsePlay");
  } else if (- 1 != url.indexOf('.letv.com') || - 1 != url.indexOf('.le.com')) {
    arr.push("letv_vod.js");
    arr.push("letvVodParsePlay");
  } else if (- 1 != url.indexOf('iqy_live://')) {
    arr.push("iqy_live.js");
    arr.push("iqyLiveParsePlay");
  } else if (- 1 != url.indexOf('.bilibili.com')) {
    if (-1 == url.indexOf('@referer=')) {　// iOS 要解析两次，这一步防止第二次重复解析
      if (-1 != url.indexOf('bangumi/play/')) {
        arr.push("bili_vod.js");
        arr.push("bangumi_play_ep_parse");
      } else if (-1 != url.indexOf('video/av')) {
        arr.push("bili_vod.js");
        arr.push('video_av_play');
      }
      else {

      }
    }
  } else if (- 1 != url.indexOf('.mgtv.com')) {
    arr.push("mgtv_vod.js");
    arr.push("MgVodParsePlay");
  } else if (- 1 != url.indexOf('tv.sohu.com')) {
    arr.push("sohu_vod.js");
    arr.push("SohuVodParsePlay");
  } else if (- 1 != url.indexOf('.pptv.com/show')) {
    arr.push("pptv_vod.js");
    arr.push("PptvVodParsePlay");
  } else if (- 1 != url.indexOf('ysp://')) {
    arr.push("ysp_live.js");
    arr.push("extract");
  } else if (- 1 != url.indexOf('ysp2://')) { // 未加密
    arr.push("ysp_live.js");
    arr.push("extract");
  } else if (- 1 != url.indexOf('ysp1://')) {
    arr.push("ysp_live.js");
    arr.push("ysponeParsePlay");
  } else if (- 1 != url.indexOf('miguold://')) {
    arr.push("ysp_live.js");
    arr.push("miguoldParsePlay");
  } else if (- 1 != url.indexOf('.fun.tv')) {
    arr.push("fun_vod.js");
    arr.push("FunVodParsePlay");
  } else if (- 1 != url.indexOf('.hanju.cc')) {
    arr.push("other_vod.js");
    arr.push("HanjuVodParsePlay");
  } else if (- 1 != url.indexOf('nangua.yingshiq.com/nangua.php?vid=')) {
    arr.push("other_vod.js");
    arr.push("NanguaVodParsePlay");
  } else if (- 1 != url.indexOf('v.qq.com')) {
    arr.push("qq_vod.js");
    arr.push("QqVodParsePlay");
  } else if (-1 < url.indexOf("xgvod://")) {
    arr.push("other_vod.js");
    arr.push("parseXiguaVod");
  } else if (-1 < url.indexOf("zzvod://")) {
    arr.push("qq_vod.js");
    arr.push("parseZzvod");
  } else if (- 1 != url.indexOf('cntvlbk://')) {
    arr.push("other_live.js");
    arr.push("cntvLbkParsePlay");
  } else if (- 1 != url.indexOf('hntv://') || - 1 != url.indexOf('mgpchtml://')) {
    arr.push("other_live.js");
    arr.push("hntvLiveParsePlay");
  } else if (- 1 != url.indexOf('migu1://') || - 1 != url.indexOf('migu://') || - 1 != url.indexOf('http://h5spdegrade.miguvideo.com/wap/resource/migu/detail/Detail_live.jsp?cid=')) {
    arr.push("other_live.js");
    arr.push("miguLiveParsePlay");
  } else if (- 1 != url.indexOf('miguhd://')) {
    arr.push("other_live.js");
    arr.push("miguLiveHdParsePlay");
  } else if (- 1 != url.indexOf('www.baofeng.com')) {
    arr.push("other_vod.js");
    arr.push("BaofengVodParsePlay");
  } else if (- 1 != url.indexOf('krcom.cn')) {
    arr.push("other_vod.js");
    arr.push("krVideoPlay");
  } else if (- 1 != url.indexOf('weibo.com')) {
    if (- 1 != url.indexOf('live.weibo.com')) {
      arr.push('weibo_live.js');
      arr.push('weiboLivePlay');
    } else if (- 1 != url.indexOf('video.weibo.com')) {
      arr.push("weibo_vod.js");
      arr.push("WeiboVodParsePlay");
    } else {
      arr.push('weibo_vod.js');
      arr.push("weiboPageVidParse");
    }

  } else if (-1 != url.indexOf('miaopai.com')) {
    // 避开　http://gslb.miaopai.com
    if (-1 == url.indexOf('gslb.miaopai.com')) {
      // 秒拍分享页面解析!
      arr.push("weibo_vod.js");
      if (-1 != url.indexOf('n.miaopai.com/media')) {
        arr.push("miaopaiMediaVidPlay");
      } else if (-1 != url.indexOf('www.miaopai.com/show')) {
        arr.push("miaopaiShowVidPlay");
      } else {
        // for legacy supporting
        arr.push("WeiboVodParsePlay");
      }
    }
  } else if (-1 != url.indexOf('cctv.com')) {
    if (-1 != url.indexOf('news.cctv.com')) {
      arr.push('other_vod.js');
      arr.push('cctv_xiaoyan_play');
    }
  } else if (-1 != url.indexOf('cntv.cn')) {
    if (-1 != url.indexOf('cbox.cntv.cn')) {
      arr.push('other_vod.js');
      arr.push('cbox_cntv_cn_play')
    }
  } else if (-1 != url.indexOf('onln.cn') || -1 != url.indexOf('www.onln.cn')) {
    arr.push('other_vod.js');
    arr.push('onln_cn_play');
  } else if (-1 != url.indexOf('alivod://')) {
    arr.push('other_vod.js');
    arr.push('yuntuds_play');
  }  else if (url.indexOf('jswx://') != -1) {
    arr.push('gw_live.js');// 江苏无锡
    arr.push('jswxParser');
  } else if (url.indexOf('lzw://') != -1) {
    arr.push('gw_live.js');// 新荔枝网
    arr.push('LzwParse');
  } else if (url.indexOf('gdes://') != -1) {
    arr.push('other_live.js');// 新荔枝网
    arr.push('gdesParser');
  } else if (url.indexOf('jsgw://') != -1) {
    arr.push('other_live.js');// 江苏直播官网
    arr.push('jsgwParser');
  } else if (url.indexOf('sddf://') != -1) {
    arr.push('other_live.js');// 山东地方台
    arr.push('sddfParser');
  } else if (url.indexOf('sddft://') != -1) {
    arr.push('gw_live.js');// 山东其他地方台
    arr.push('sddftParser');
  } else if (url.indexOf('anhui://') != -1) {
    arr.push('other_live.js');// 安徽官网
    arr.push('ahgwParser');
  } else if (url.indexOf('hefei://') != -1) {
    arr.push('gw_live.js');// 合肥
    arr.push('hefeiParser');
  } else if (url.indexOf('dfgw://') != -1) {
    arr.push('other_live.js');// 东方卫视官网
    arr.push('dfgwParser');
  } else if (url.indexOf('fjgw://') != -1) {
    arr.push('gw_live.js');// 福建卫视官网
    arr.push('fjgwParser');
  } else if (url.indexOf('sxgw://') != -1) {
    arr.push('gw_live.js');// 山西卫视官网
    arr.push('sxgwParser');
  } else if (url.indexOf('gzgw://') != -1) {
    arr.push('other_live.js');// 贵州官网
    arr.push('gzgwParser');
  } else if (url.indexOf('bjgw://') != -1) {
    arr.push('other_live.js');// 北京卫视地方台
    arr.push('bjgwParser');
  } else if (url.indexOf('chengdu://') != -1) {
    arr.push('other_live.js'); // 四川成都
    arr.push('chengDuParser');
  } else if (url.indexOf('scgw://') != -1) {
    arr.push('other_live.js'); // 四川官网
    arr.push('scgwParser');
  } else if (url.indexOf('miguNew://') != -1) {
    arr.push('other_live.js'); // 四川成都
    arr.push('muGuParser');
  } else if (url.indexOf('qhtv://') != -1) {
    arr.push('other_live.js'); // 青海卫视
    arr.push('qhtvParser');
  } else if (url.indexOf('sztv://') != -1) { // 深圳
    arr.push('other_live.js');
    arr.push('sztvParser');
  } else if (url.indexOf('nmg://') != -1) { // 内蒙古
    arr.push('other_live.js');
    arr.push('nmgParser');
  } else if (url.indexOf('dgtv://') != -1) { // 东莞
    arr.push('other_live.js');
    arr.push('dgtvParser');
  } else if (url.indexOf('jxgw://') != -1) { // 江西官网
    arr.push('other_live.js');
    arr.push('jxgwParser');
  } else if (url.indexOf('sjz://') != -1) { // 石家庄
    arr.push('other_live.js');
    arr.push('sjzParser');
  } else if (url.indexOf('gztv://') != -1) { // 广州地方台
    arr.push('other_live.js');
    arr.push('gztvParser');
  } else if (url.indexOf('ptdst://') != -1) { // 莆田电视台
    arr.push('other_live.js');
    arr.push('ptdstParser');
  } else if (url.indexOf('yngw://') != -1) { // 云南官网
    arr.push('other_live.js');
    arr.push('yngwParser');
  } else if (url.indexOf('cctvh5://') != -1) { // cctv h5
    arr.push('gw_live.js');
    arr.push('cctvh5Parser');
  } else if (url.indexOf('hnntv://') != -1) {
    arr.push('other_live.js');
    arr.push('hnntvParser');
  } else if (url.indexOf('hbgf://') != -1) {
    arr.push('other_live.js');
    arr.push('hbgfParse')
  } else if (url.indexOf('henan://') != -1) {
    arr.push('other_live.js'); // 河南官网
    arr.push('henanParse')
  } else if (url.indexOf('ny://') != -1) {
    arr.push('other_live.js'); // 河南南阳
    arr.push('nyParse')
  } else if (url.indexOf('&pwd=jsdecode') != -1) {
    arr.push('replay.js');
    arr.push('kdsLiveParsePlay')
  } else if (url.indexOf('nmgcf://') != -1) { // 内蒙古赤峰
    arr.push('other_live.js');
    arr.push('nmgcfParser')
  } else if (url.indexOf('wxty://') != -1) { // 五星体育
    arr.push('other_live.js');
    arr.push('wxtyParser')
  } else if (url.indexOf('cqgw://') != -1) { // 重庆官网
    arr.push('replay.js');
    arr.push('cqxwParsePlay')
  } else if (url.indexOf('baidou.com/') != -1) { // 女排
    arr.push('replay.js');
    arr.push('npParsePlay')
  } else if (url.indexOf('gsgw://') != -1) { // 甘肃卫视官网
    arr.push('replay.js');
    arr.push('gsgwParsePlay')
  } else if (url.indexOf('zgyj://') != -1) { // 追光影界
    arr.push('other_live.js');
    arr.push('zgyjParsePlay')
  } else if (url.indexOf('http://zgyj_vod/') != -1) { // 追光影界
    arr.push('other_live.js');
    arr.push('zgyjvodParsePlay')
  }
  return arr;
}
