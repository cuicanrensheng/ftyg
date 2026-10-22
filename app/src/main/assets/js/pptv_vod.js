
/**
 * @brief pptv播放地址解析
 * @author gxl<gxl@yuntutv.net>
 * @date 2017-2-14
 * @version 1.0.0.0
 */

//http://m.pptv.com/show/kJzBP6cNfbsenAQ.html rid带%
//http://v.pptv.com/show/d5mDAWnPP33gXsY.html rid是hash值
//http://v.pptv.com/show/CC75d3OSWJb5d98.html 限pc版访问
//http://vip.pptv.com/show/teLNSrIYiaMZHicGfNPQ.html vip视频
function PptvVodParsePlay(origUrl) {
    var cont = urlRequestWithPara(origUrl,"Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/47.0.2526.106 Safari/537.36","User-Agent");
	var urlArray = new Array();
    if (cont.indexOf("非常抱歉，您访问的页面不存在")>-1) {
		urlArray.push("error://pptv_404");
        return urlArray;
    }
    if( - 1 != origUrl.indexOf("vip.pptv.com")){
        var vid = cont.substring(cont.indexOf("vid: \"")+6);
        vid = vid.substring(0, vid.indexOf("\""));
    }else{
        var vid = cont.substring(cont.indexOf("\"id\":")+5);
        vid = vid.substring(0, vid.indexOf("\,"));
    }

    if(vid == null || vid == ''){
        urlArray.push("error://pptv_parse_error");
        return urlArray;
    }
    var lurl = 'http://play.api.pptv.com/boxplay.api?auth=d410fafad87e7bbf6c6dd62434345818&id='+vid+'&sv=6.2.4&ver=1&type=phone.android.download&k_ver=1.1.0.8922&param=userType=0&gslbversion=2&content=need_drag&platform=android3'
    var content = getUrlContent(lurl);
    var dragdata = content.match(/\<dragdata(.*?)dragdata/ig);
    var ridarr = content.match(/item rid="(.*?).mp4/ig);
    var vlen = ridarr.length;
    var sharr = content.match(/\<sh\>(.*?)\<\/sh\>/ig);
    var keyarr = content.match(/UTC"\>(.*?)\<\/key/ig);
    var starr = content.match(/\<st\>(.*?)\<\/st\>/ig);
                               log("vlen====>"+vlen);
    if(vlen == 0){
        urlArray.push("error://pptv_parse_error");
        return urlArray;
    }else if(vlen == 1){
        var rid0 = streplace(ridarr[0],'item rid="','.mp4');
        var sh0 = streplace(sharr[0],'<sh>','</sh>');
        var key0 = streplace(keyarr[0],'UTC">','</key');
        var st0 = streplace(starr[0],'<st>','</st>');
        //var gkey = genkey(gettime(st0));
        urlArray[0] = getPptvVodUrl(rid0,sh0,key0) + "#流畅";
    }else if(vlen == 2){
        var rid0 = streplace(ridarr[0],'item rid="','.mp4');
        var sh0 = streplace(sharr[0],'<sh>','</sh>');
        var key0 = streplace(keyarr[0],'UTC">','</key');
        var rid1 = streplace(ridarr[1],'item rid="','.mp4');
        var sh1 = streplace(sharr[1],'<sh>','</sh>');
        var key1 = streplace(keyarr[1],'UTC">','</key');
        urlArray[0] = getPptvVodUrl(rid1,sh1,key1) + "#标清";
        urlArray[1] = getPptvVodUrl(rid0,sh0,key0) + "#流畅";
    }else{
        var rid0 = streplace(ridarr[0],'item rid="','.mp4');
        var sh0 = streplace(sharr[0],'<sh>','</sh>');
        var key0 = streplace(keyarr[0],'UTC">','</key');
        var rid1 = streplace(ridarr[1],'item rid="','.mp4');
        var sh1 = streplace(sharr[1],'<sh>','</sh>');
        var key1 = streplace(keyarr[1],'UTC">','</key');
        var rid2 = streplace(ridarr[2],'item rid="','.mp4');
        var sh2 = streplace(sharr[2],'<sh>','</sh>');
        var key2 = streplace(keyarr[2],'UTC">','</key');
        urlArray[0] = getPptvVodUrl(rid2,sh2,key2) + "#高清";
        urlArray[1] = getPptvVodUrl(rid1,sh1,key1) + "#标清";
        urlArray[2] = getPptvVodUrl(rid0,sh0,key0) + "#流畅";
    }
    return urlArray;
}

function getPptvVodUrl(rid,sh,key) {
	key = decodeURIComponent(key);
	if(typeof client!= 'undefined'){
		segUrls = 'http://'+sh+'/w/'+rid+'.mp4?type=phone.android.download&k='+key+'@User-Agent=LivePlayer/20';
	}else{
		segUrls = 'http://'+sh+'/w/'+rid+'.mp4?type=phone.android.download&k='+key;
	}
	return segUrls;
}

function streplace(str,stra,strb){
    str=str.replace(stra,"");
    str=str.replace(strb,"");
    return str;
}
function gettime(st) {
    var date = new Date(st);
    var time  = date.getTime();
    return time/1000-60;
}

function toHex(num){
    var rs = "";
    var temp;
    while(num/16 > 0){
        temp = num%16;
        rs = (temp+"").replace("10","a").replace("11","b").replace("12","c").replace("13","d").replace("14","e").replace("15","f") + rs;
        num = parseInt(num/16);
    }

    return rs;
}
