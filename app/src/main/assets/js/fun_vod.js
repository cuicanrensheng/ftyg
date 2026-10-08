
var globalScriptContent = [];
var globalRandoms = [];

function gettoken(t1) {
    var e1, i1, o1, n1, a1, r1, s1 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    for (o1 = t1.length, i1 = 0, e1 = ""; o1 > i1;) {
        if (n1 = 255 & t1.charCodeAt(i1++), i1 == o1) {
            e1 += s1.charAt(n1 >> 2), e1 += s1.charAt((3 & n1) << 4), e1 += "==";
            break
        }
        if (a1 = t1.charCodeAt(i1++), i1 == o1) {
            e1 += s1.charAt(n1 >> 2), e1 += s1.charAt((3 & n1) << 4 | (240 & a1) >> 4), e1 += s1.charAt((15 & a1) << 2), e1 += "=";
            break
        }
        r1 = t1.charCodeAt(i1++), e1 += s1.charAt(n1 >> 2), e1 += s1.charAt((3 & n1) << 4 | (240 & a1) >> 4), e1 += s1.charAt((15 & a1) << 2 | (192 & r1) >> 6), e1 += s1.charAt(63 & r1)
    }
    return e1
}


function decodeWebToken(t2) {
   log("t2.infohash"+t2.infohash)

    for (var e2, o2, n2 = 0; !e2 && 2 > n2;) {
        if (e2 = i(t2.infohash), 41 == e2.length && /^[0-9a-f]*$/gi.test(e2)) {
            
            for (var a2 = (e2.substr(0, 40), 0), r2 = 0, s2 = e2.length - 1; s2 > r2; r2++) a2 += parseInt(parseInt(e2.substr(r2, 1), 16), 10);
            var d2 = 15 & a2;
            d2.toString(16).toUpperCase() == e2.substr(40) && (o2 = {
                hashid: e2.substr(0, 40),
                token: i(t2.token)
            })
        }
        
        o2 || (e2 = null, t2.infohash = t2.infohash_prev, t2.token = t2.token_prev), n2++
    }

    return  o2;
}



function i(t) {
    var i = "",
        n = "",
        a = "";
    return 28 == t.length && t.lastIndexOf("0") == t.length - 1 ? (i = t.substr(0, 27) + "=", n = e(i), a = o(n), r(a)) : (i = t.substr(2, t.length - 2), n = e(i), o(n))
}

function e(t4) {
    for (var e4, i4, o4, n4, a4 = new Array(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1), r4 = 0, s4 = t4.length, d4 = ""; s4 > r4;) {
        do e4 = a4[255 & t4.charCodeAt(r4++)]; while (s4 > r4 && -1 == e4);
        if (-1 == e4) break;
        do i4 = a4[255 & t4.charCodeAt(r4++)]; while (s4 > r4 && -1 == i4);
        if (-1 == i4) break;
        d4 += String.fromCharCode(e4 << 2 | (48 & i4) >> 4);
        do {
            if (o4 = 255 & t4.charCodeAt(r4++), 61 == o4) return d4;
            o4 = a4[o4]
        } while (s4 > r4 && -1 == o4);
        if (-1 == o4) break;
        d4 += String.fromCharCode((15 & i4) << 4 | (60 & o4) >> 2);
        do {
            if (n4 = 255 & t4.charCodeAt(r4++), 61 == n4) return d4;
            n4 = a4[n4]
        } while (s4 > r4 && -1 == n4);
        if (-1 == n4) break;
        d4 += String.fromCharCode((3 & o4) << 6 | n4)
    }

    return d4
}

function o(t5) {
    for (var e5 = "", i5 = 0; i5 < t5.length;) {
        var o5 = t5.charCodeAt(i5++),
            r5 = t5.charCodeAt(i5++);
        if (e5 += a(noon(o5, r5).z1), e5 += a(noon(o5, r5).z2), i5 == t5.length - 1) {
            e5 += a(t5.charCodeAt(i5));
            break
        }
    }
    return e5
}
function a(t6) {
    return String.fromCharCode(t6)
}

function noon(t, e) {

    //todo document.mozEcName

    
    var i = globalRandoms,
        o = 256,
        n = [0, 0, 0, 0],
        a = [],
        r = {};
    // $.each(i, function(t, e) {
    //     r[e] || (r[e] = !0, a.push(e))
    // });

    //
    // for(var index=0;index<index.length;index++){
    //
    //     r[ i[index]] || (r[ i[index]] = !0, a.push(i[index]))
    // }
 

    i.forEach(function(e, t){
        r[e] || (r[e] = !0, a.push(e))
    });
    for (var s = 0, d = a.length; d > s; s++) n[parseInt(a[s].substr(a[s].length - 1))] = parseInt(a[s].substr(0, a[s].length - 1), 16);
    return {
        z1: (t * n[0] + e * n[2]) % o,
        z2: (t * n[1] + e * n[3]) % o
    }
}




var mp4info = {
codec: "h.264",
filename: "",
filesize: 185825303,
infohash: "21JNfhv5MTKhrGrkRuRGfU/rqxAFDtTAfOR4xSXF212kiJd7AVS3VZzEM=",
infohash_prev: "21OGm9iW+F8jbegkxyGNkcYva/wLBlvKcKj4yy9GEzCshFEcxLv6MxHEM=",
token: "21vzCaikt1I6OlU9T+dzdZY9e60KzwcSTQLtxdtdcqLtz7OpDnPMPCvu7JTvGFbrRnNhC/MFG/tGc=",
token_prev: "219ygiRr+jP3URFRxio4ElJYce4FTcbwTQbtRhM8eubtTLPsQ5oL160urHKp+VGghZpmD3KK0JCFk=",
vf: "MCw2MTM2RA==",
    
    //var i =  ["200", "d91", "272", "b73"],
}




function getVid(origUrl) {
    var vid="";
    if(- 1 !=origUrl.indexOf('m.fun.tv')){
        vid  = origUrl.substring(origUrl.indexOf("mid="))
        log(11111)
    }else if(- 1 !=origUrl.indexOf('.fun.tv')){
        var cont = getUrlContent(origUrl);
       cont = cont.substring(cont.indexOf("download_pc tool_cli_link"));
       vid=  cont.substring(cont.indexOf("data-vid=\"")+10);
       vid=  vid.substring(0,vid.indexOf("\""));
    }else {

    }
    return vid;
}

function getPlaylist(mediainfo) {
    if(mediainfo.retcode == 200){
        
        log("mediainfo.retcode == 200")
        return mediainfo.playlist;
    }
    return "";
}

function getCdn(Vid,isMedia) {
    var cdnarr = new Array();
	if(isMedia){
		var result= urlRequest("http://pm.funshion.com/v5/media/play?id="+Vid+"&cl=apad&ve=2.1.0.1");
	}else{
		var result= urlRequest("http://pv.funshion.com/v5/video/play?id="+Vid+"&cl=apad&ve=2.1.0.1");
	}

    result = eval('('+result+')');

    if(result.retcode == 200){
        
      var  mp4list =  result.mp4;
        for(var i = 0 ;i<mp4list.length;i++){
           var mp4 = mp4list[i];
   
          var cdnjson = urlRequest(mp4.http);
            
          cdnjson = eval('('+cdnjson+')');
        
          cdnarr.push( cdnjson.playlist[0].urls[0]);
      }

    }
    
    return cdnarr
}

function getMozEcName(weburl, number) {
  
    var data = urlRequest(weburl);
    var evalContent = data.match(/eval\((.*)\)/)[1];
    eval("globalScriptContent[" + number + "]= " + evalContent + ";");
    var mozEcName = globalScriptContent[number].match(/document\.mozEcName\.push\(.*?\)/ig);
    for (var i = 0; i < mozEcName.length; i++) {
        var matchnum = mozEcName[i].match(/document\.mozEcName\.push\((.*)\)/i)[1];
        matchnum =  matchnum.substring(matchnum.indexOf("\"")+1, matchnum.lastIndexOf("\""));
        globalRandoms.push(matchnum);
    }
    log("globalRandoms:" + globalRandoms);
    
   
}

function getMozecNameNum(){
    url = "http://m.fun.tv/mplay/?mid=315223&vid=953273"
    data = urlRequest(url);
    var scripts = data.match(/<script type=\"text\/javascript\" charset=\"utf-8\" src=\"(.*?)\"><\/script>/g);
    var scriptUrls = [];
    for (var i = 0; i < scripts.length; i++) {
            scriptUrls.push(scripts[i].match(/<script type=\"text\/javascript\" charset=\"utf-8\" src=\"(.*?)\"><\/script>/)[1]);
      }
    scriptUrls.forEach(function(scriptUrl, index){
         var number = scriptUrls.indexOf(scriptUrl);
        getMozEcName( 'http://m.fun.tv' + scriptUrl, number)
    });
}

function FunVodParsePlay(origUrl){
   if(origUrl) {
       getMozecNameNum();
   }
	var isMedia = true;
    var urlArray = new Array();
   var  Vid = getVid(origUrl);
    log("vid:" + Vid);
    if(Vid == ""){
        return "";
    }
    var mediainfo=  urlRequest("http://pm.funshion.com/v7/media/play/?id="+Vid+"&cl=mweb&uc=223&user_id=0&token=&fudid=1505894398ea40b");
    // JS- mediainfo:{"retcode":"404","retmsg":"resource not exist"}
    log("mediainfo:" + mediainfo);
    mediainfo = eval('('+mediainfo+')');
	if(mediainfo.retcode == 404){
        return ["error://funtv_404"];
    }
	if(mediainfo.retcode!=200){
		 var mediainfo=  urlRequest("http://pv.funshion.com/v7/video/play/?id="+Vid+"&cl=mweb&uc=223&user_id=0&token=&fudid=1505894398ea40b");
         log("mediainfo2:" + mediainfo);
         mediainfo = eval('('+mediainfo+')');
		 isMedia =false;
	}
	   cdnarr = getCdn(Vid,isMedia);
    var playList  =  getPlaylist(mediainfo);
   
    if(playList==""){
        return "";
    }

    var mp4Infos  = new Array();
   

    for(var index = 0;index<playList.length;index++){
        
        var playinfo = playList[index].playinfo;
        
        mp4info.filename = playinfo[0].filename;
        mp4info.codec = playinfo[0].codec;
        mp4info.filesize = playinfo[0].filesize;
        mp4info.infohash = playinfo[0].infohash;
        mp4info.infohash_prev = playinfo[0].infohash_prev;
        mp4info.token = playinfo[0].token;
        mp4info.token_prev = playinfo[0].token_prev;
        mp4info.vf = playinfo[0].vf;
 
        var webtoken  = decodeWebToken(mp4info);
        
        var str = JSON.stringify(webtoken);
     
        var token = gettoken(webtoken.token);
        mp4info.token = token;

        var cdn = cdnarr[index];

        urlArray.push( cdn+"?token="+encodeURIComponent(token)+"&user_id=0&user_token=&vf="+encodeURIComponent(mp4info.vf)+"&fudid=1505894398ea40b&app_code=mweb#"+mediainfo.playlist[index].name);
    }



   return urlArray;
}
