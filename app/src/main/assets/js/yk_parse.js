function get(name, str){
    if(name=(new RegExp('[?&]'+encodeURIComponent(name)+'=([^&]*)')).exec(str))
        return decodeURIComponent(name[1]);
}

function ykVodParsePlay1(origUrl) {
    var vid = get("vid", origUrl);
    var utid = get("utid", origUrl);
    var ckey = get("ckey", origUrl);
    var requestUrl = "https://ups.youku.com/ups/get.json?vid="+vid+"&ccode=0519&client_ip=192.168.1.1&client_ts=1529934005&utid="+utid+"&ckey="+ckey;
    var headers = [];
    headers.push({header:"Referer", value:"http://v.youku.com"})
    headers.push({header:"User-Agent", value:"Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/67.0.3396.99 Safari/537.36"})
    headers.push({header:"Cookie", value:"cna="+utid})
    var apiCont = urlRequestWithParaAndCoding(requestUrl, headers, "UTF-8");
    if(apiCont.length <= 0){
        return new Array();
    }
    var jsonObject = eval('('+apiCont+')');
    var stream = jsonObject.data.stream;
    var urlArray = new Array();
    var num = stream.length;
    for(var i=0;i<num;i++){
        var stream_type=stream[i].stream_type;
        var url=stream[i].m3u8_url;
        if(stream_type=="3gphd"){
            urlArray.push("4"+url+"#流畅");
        }else if(stream_type=="mp4hd3v2"||stream_type=="mp4hd3"){
            urlArray.push("1"+url+"#1080P");
        }else if(stream_type=="mp4hd2v2"||stream_type=="mp4hd2"){
            urlArray.push("0"+url+"#超清");
        }else if(stream_type=="mp4hd"){
            urlArray.push("2"+url+"#高清");
        }else if(stream_type=="mp4sd"||stream_type=="flvhd"){
            urlArray.push("3"+url+"#标清");
        }else{
            urlArray.push("5"+url+"#标清");
        }
    }
    urlArray=urlArray.sort();
    var playArr = new Array();
    for(var j=0;j<urlArray.length;j++){
        playArr.push(urlArray[j].substring(1));
    }
    return playArr;
}

function ykVodParsePlay(origUrl) {
	var pcua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.115 Safari/537.36";
	var cont = urlRequestWithPara(origUrl,pcua,"User-Agent");
	var showid = getStringBtween(cont,"showid: '","'");
	var vid = getStringBtween(cont,"videoId: '","'");
	var envid = getStringBtween(cont,"currentEncodeVid: '","'");
	var checkUrl = getVipCheckUrl(vid, showid);
	var checkCont = urlRequestWithPara(checkUrl,pcua,"User-Agent");
	var cnaUrl = "https://log.mmstat.com/eg.js";
	var cnaCont = urlRequestWithPara(cnaUrl,pcua,"User-Agent");
	var cna = getStringBtween(cnaCont,'goldlog.Etag="','"');
	cnaCont = urlRequestWithPara(cnaUrl, "cna="+cna, "Cookie");
	cna = cnaCont.substring(cnaCont.lastIndexOf("Etag=\"")+6);
	cna = cna.substring(0, cna.indexOf("\""));
	if(typeof client.getRespAndHeaders!='undefined'){
		log("客户端有getRespAndHeaders方法");
		var apiUrl1 = getAcsUrl("1",envid,cna,"",showid);
		var resp = getRespAndHeaders(apiUrl1,"");
		log(resp);
		var _m_h5_tk = getStringBtween(resp,'_m_h5_tk=',';');
		var _m_h5_tk_en = getStringBtween(resp,'_m_h5_tk_enc=',';');
		var tokenArr = _m_h5_tk.split("_");
		var token = tokenArr[0];
		log("token===>"+token);
		var cookie = "_m_h5_tk="+_m_h5_tk+"; _m_h5_tk_enc="+_m_h5_tk_en+";";
		log("cookie===>"+cookie);	
	}else{
		log("客户端没有getRespAndHeaders方法");
		var cookie = urlRequest("http://xproxy.tvesou.com/a/youku_cookie.php");
		var token = getStringBtween(cookie,"_m_h5_tk=","_");
	}
	cookie = cookie+" cna="+cna+";";
	var apiUrl2 = getAcsUrl("2",envid,cna,token,showid);
	var header = [];
	header.push({header:"User-Agent",value:pcua});
	header.push({header:"Referer",value:origUrl});
	header.push({header:"Cookie",value:cookie});
	header.push({header:"Host",value:"acs.youku.com"});
	log("apiUrl2===>"+apiUrl2);
	var apiCont = urlRequestWithParaAndCoding(apiUrl2, header,"utf-8");
	log("apiCont===>"+apiCont);
	if(apiCont.length <= 0){
		return new Array();
	}
	var jsonObject = eval('('+apiCont+')');
	var stream = jsonObject.data.data.stream;
	var urlArray = new Array();
	var num = stream.length;
	for(var i=0;i<num;i++){
		var stream_type=stream[i].stream_type;
		var url=stream[i].m3u8_url;
		if(stream_type=="3gphd"){
			urlArray.push("4"+url+"#流畅");
		}else if(stream_type=="mp4hd3v2"){
			urlArray.push("1"+url+"#1080P");
		}else if(stream_type=="mp4hd2v2"){
			urlArray.push("0"+url+"#超清");
		}else if(stream_type=="mp4hd"){
			urlArray.push("2"+url+"#高清");
		}else if(stream_type=="mp4sd"){
			urlArray.push("3"+url+"#标清");
		}else{
			urlArray.push("5"+url+"#标清");
		}
	}
	urlArray=urlArray.sort();
	var playArr = new Array();
	for(var j=0;j<urlArray.length;j++){
		playArr.push(urlArray[j].substring(1));
	}
    return playArr;
}

function getStringBtween(str,str1,str2){
	var a = str.substring(str.indexOf(str1)+str1.length);
	a = a.substring(0, a.indexOf(str2));
	return a;
}

function getAcsUrl(n, vid, cna, token,showid) {
  var appKey = "24679788";
  var time = (new Date).getTime();
  var dataJson = getServieceMtopParam(cna, vid,showid);
  var sign = getMD5(token + "&" + time + "&" + appKey + "&" + JSON.stringify(dataJson));

  var params = [];
  params.push("jsv=2.4.11");
  params.push("appKey=" + appKey);
  params.push("t=" + time);
  params.push("sign=" + sign);
  params.push("api=mtop.youku.play.ups.appinfo.get");
  params.push("v=1.1");
  params.push("timeout=20000");
  params.push("YKPid=20160317PLF000211");
  params.push("YKLoginRequest=true");
//  params.push("type=jsonp");
//  params.push("dataType=jsonp");
//  params.push("callback=mtopjsonp" + n);
  params.push("data=" + encodeURIComponent(JSON.stringify(dataJson)));
  var acs_url = "http://acs.youku.com/h5/mtop.youku.play.ups.appinfo.get/1.1/?" + params.join("&");
  return acs_url;
}

function getServieceMtopParam(_cna, mediaId,showid) {
  var _vid = {
    vid: mediaId,
	current_showid: showid
  };
  //var ckey = "DIl58SLFxFNndSV1GFNnMQVYkx1PP5tKe1siZu/86PR1u/Wh1Ptd+WOZsHHWxysSfAOhNJpdVWsdVJNsfJ8Sxd8WKVvNfAS8aS8fAOzYARzPyPc3JvtnPHjTdKfESTdnuTW6ZPvk2pNDh4uFzotgdMEFkzQ5wZVXl2Pf1/Y6hLK0OnCNxBj3+nb0v72gZ6b0td+WOZsHHWxysSo/0y9D2K42SaB8Y/+aD2K42SaB8Y/+ahU+WOZsHcrxysooUeND";
  var ckey = "109#I9za7KXUapsFoadLpC4jCz5G4ivERMZcCqXyebEWGMHGqDEFDeWncjIawJzGaAkj98nDwCIc/6Id7WnBIoz3Ar/AwD+NG+CuOjC2uN3jEzQpGacB4saYgb+raWYEVn8yxud7zN7eEzOpK0f1GPJeeplIaNGmk7llABdj0hGyMqE8H8dpdCozXOiiJ5tsOLAYNzCCEEg3PedbNKWWhXrbSoGfz+QWt+tY8XMnAHg/YZy1LqxQjKBfMWmBe0+oY1kpZqDGq/B24CM7rsZsVE/rfc+AoWFFTdZmSfEX1X55d2Hr6zd2lrahcRoGZY7XTe5j20SqIxtgJvKH4W8xRo+WI26kPLPRxkjnO5U5aTGlQ9ZCHy2rAg5jwxdw+CzMMXZXuFYfyQNbIHevyHsZrQ0YgGCPyKmyyHXeGICnymeXmJUvQHS4AFDHXqHNppgGeD013I2ZKL3GEkddx0HIiiORsRNwqEiID0I=";
  var t = {
    ccode: "0502",
    client_ip: "192.168.1.1",
    utid: _cna,
    client_ts: parseInt((new Date).getTime() / 1e3),
    ckey: ckey
  };
  var ad = {
    site: 1,
    wintype: "interior",
    p: 1,
    fu: 0,
    vs: "1.0",
    rst: "mp4",
    dq: "flv",
    os: "win",
    osv: "",
    d: "0",
    bt: "pc",
    aw: "w",
    needbf: 1
  }
  return {
    steal_params: JSON.stringify(t),
    biz_params: JSON.stringify(_vid),
    ad_params: JSON.stringify(ad)
  };
}

function getVipCheckUrl(vid, showid) {
  var t = +new Date;
  var c = [];
  c.push("beta");
  c.push("timestamp=" + t);
  c.push("callback=page_find_play_model_interaction_feePaymentPro__getFeeInfo");
  c.push("vid=" + vid);
  c.push("showid=" + showid);
  c.push("t=" + Math.random());

  var ret = "http://v.youku.com/fee/feeinfo?" + c.join("&");

  return ret;
}