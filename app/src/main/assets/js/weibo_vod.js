//http://video.weibo.com/show?fid=1034:b8eb83c90f6e7b629586c6a63bc5fd13
//http://video.sina.cn/sports/football/premierleague/2017-03-07/detail-ifycaafp2207378.d.html?from=wap
function WeiboVodParsePlay(origUrl) {
	if( - 1 != origUrl.indexOf('video.sina.cn')){
		var cont = urlRequestWithPara(origUrl,"Mozilla/5.0 (iPhone; CPU iPhone OS 8_0 like Mac OS X) AppleWebKit/600.1.3 (KHTML, like Gecko) Version/8.0 Mobile/12A4345d Safari/600.1.4","User-Agent");
		var vurl = cont.substring(cont.indexOf("data-src=\"")+10);
		vurl = vurl.substring(0, vurl.indexOf("\""));
	}else if( - 1 != origUrl.indexOf('http://weibo.com/tv/')){
		//http://weibo.com/tv/v/F29PJ3sgH?from=vhot
		var url = origUrl.replace("http://weibo.com/tv/v/","http://m.weibo.cn/status/");
		var cont = urlRequestWithPara(url,"User-Agent: Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1","User-Agent");
		var vurl = cont.substring(cont.indexOf("stream_url\": \"")+14);
		vurl = vurl.substring(0, vurl.indexOf("\""));
		vurl = vurl.replace("https://","http://");
	}else if( - 1 != origUrl.indexOf('http://www.miaopai.com/show/')){
		//http://www.miaopai.com/show/jt5WSDLv84U-ciyqb70Q3gwoaTNwquBl.htm
		var cont = urlRequestWithPara(origUrl,"Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.81 Safari/537.36","User-Agent");
		var vurl = cont.substring(cont.indexOf("videoSrc\":\"")+11);
		vurl = vurl.substring(0, vurl.indexOf("\""));
	}else{
		origUrl=origUrl.replace("http://video.weibo.com/show?fid=","http://m.miaopai.com/show/channerWbH5/");
		var cont = urlRequestWithPara(origUrl,"Mozilla/5.0 (iPhone; CPU iPhone OS 8_0 like Mac OS X) AppleWebKit/600.1.3 (KHTML, like Gecko) Version/8.0 Mobile/12A4345d Safari/600.1.4","User-Agent");
		var vurl = cont.substring(cont.indexOf("data-url=\"")+10);
		vurl = vurl.substring(0, vurl.indexOf("\""));
	}
    if(vurl == null || vurl == ''){
        return new Array();
    }
    var urlArray = new Array();
	urlArray[0] = vurl + "#标清";
	return urlArray;
}
