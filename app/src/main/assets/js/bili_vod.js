function make_session(){
    return String(Math.floor(1e5 * Math.random()).toString(16) + Date.now())
}

// https://www.bilibili.com/bangumi/play/ep253167
function bangumi_play_ep_parse(page_url){

    function extract_ep_id(url){
        var pattern = new RegExp(/\d+\/?/);
        var result = pattern.exec(url)[0];
        return result;
    }

    function get_page_content(){
        var headers = {
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/71.0.3578.98 Chrome/71.0.3578.98 Safari/537.36",
            "Host": 'www.bilibili.com'
        };
        var arr = [];
        for (var k in headers) {
            arr.push({'header':k, 'value':headers[k]});
        };
        var encode = "utf-8";
        var html_content = urlRequestWithParaAndCoding(page_url, arr, encode);
        return html_content;
    }

    function extract_play_info(content) {
        var pattern = new RegExp(/\<script\>window\.__playinfo__(.*?)\<\/script\>/);
        var r = pattern.exec(content)[0];
        var result = JSON.parse(r.slice(28, -9));
        return result;
    }

    function extract_cid(content){
        var pattern = new RegExp((/window\.__INITIAL_STATE__=(.*\]\})\;/));
        var r = pattern.exec(content)[0].slice(25,-1);
        var result = JSON.parse(r);
        return result['epInfo']['cid'];
    }

    function parse_play_info_data(data){
        var result = [];
        var durls_arr = data['durl'];
        for(var i = 0; i < durls_arr.length; i++) {
            var current_item = durls_arr[i];
            var d = [];
            d.push(current_item['url']);
            d.push(current_item['length']);
            result.push(d);
        }
        return result;
    }

    function make_lbk(parsed_arr, cid){
        var file_path = '';
        var fileContent = "ffconcat version 1.0\n";
        var for_ios = [];
        for (var i = 0; i < parsed_arr.length; i++) {
            var url = parsed_arr[i][0];
            fileContent += "file " + url + '\n';
            var duration = Math.floor(parsed_arr[i][1]/1000) +1;
            fileContent += "duration " + duration + '\n';
            var o = {};
            o['url'] = url;
            o['duration'] = duration;
            for_ios.push(o);
        }
        if(typeof client!= 'undefined'){
            filePath = writeFileWithName(fileContent, "bilibili_" + cid + "default.lbk");
        } else {
            // iOS 客户端不支持播放本地文件，生成在线的 lbk 文件来播放
            var fileApi = "http://47.98.87.165/config/get_lbk?content=" + encodeURIComponent(JSON.stringify(for_ios)) + "&urlPrefix=" + "&filename=" + cid + "__default.lbk";
            backRs = urlRequest(fileApi);
            obj = JSON.parse(backRs);
            if (obj.code==0) {
                return obj.data;
            }
            else {
                return "error://bili_vod_fileapi_error";
            }
        }
        return filePath;
    }


    var html_content = get_page_content();
    var cid = extract_cid(html_content);
    var play_info_data = extract_play_info(html_content)['data'];
    var parsed_arr = parse_play_info_data(play_info_data);
    var filePath = make_lbk(parsed_arr, cid);

    var result = [];
    var r = filePath + "@@";
    r += 'origin=https://www.bilibili.com@';
    r += 'referer=' + page_url + "@";
    r += 'user-agent=Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/71.0.3578.98 Chrome/71.0.3578.98 Safari/537.36@@';
    r += "#480p";
    result.push(r);

    return result;
}

function video_av_play(page_url){
    function get_page_content(){
        var headers = {
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/71.0.3578.98 Chrome/71.0.3578.98 Safari/537.36",
            "Host": 'www.bilibili.com'
        };
        var arr = [];
        for (var k in headers) {
            arr.push({'header':k, 'value':headers[k]});
        };
        var encode = "utf-8";
        var html_content = urlRequestWithParaAndCoding(page_url, arr, encode);
        return html_content;
    }

    function get_mobile_page_content(){
        var headers = {
            "User-Agent": "Mozilla/5.0 (Linux; Android 7.0; PLUS Build/NRD90M) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/61.0.3163.98 Mobile Safari/537.36",
            "Host": 'm.bilibili.com'
        };
        var arr = [];
        for (var k in headers) {
            arr.push({'header':k, 'value':headers[k]});
        };
        var aid = extract_aid(page_url);
        var mobile_url = 'https://m.bilibili.com/video/av'+ aid +'.html';
        var p_num = extract_pnum(page_url);
        if (p_num) {
            mobile_url = mobile_url + '?p=' + p_num;
        }
        var encode = "utf-8";
        var html_content = urlRequestWithParaAndCoding(mobile_url, arr, encode);
        return html_content
    }

    function extract_play_info(content) {
        var pattern = new RegExp(/\<script\>window\.__playinfo__(.*?)\<\/script\>/);
        var r = pattern.exec(content)[0];
        var result = JSON.parse(r.slice(28, -9));
        return result;
    }

    function extract_cid(content){
        var pattern = new RegExp((/window\.__INITIAL_STATE__=(.*\]\})\;/));
        var r = pattern.exec(content)[0].slice(25,-1);
        var result = JSON.parse(r);
        return result['epInfo']['cid'];
    }

    function extract_mobile_video_info(content){
        var pattern = new RegExp((/window\.__INITIAL_STATE__=(.*\"\})\;/));
        var r = pattern.exec(content)[0].slice(25,-1);
        var result = JSON.parse(r);
        return result
    }

    function extract_aid(url){
        var pattern = new RegExp(/av\d+/);
        var result = pattern.exec(url)[0].slice(2)
        return result;
    }

    function extract_pnum(url){
        var pattern = new RegExp(/\?p\=\d+/);
        var result = pattern.exec(url);
        if (result) {
            return result[0].slice(3);
        } else {
            return null;
        }
    }

    function parse_play_info_data(data){
        var result = [];
        var durls_arr = data['durl'];
        for(var i = 0; i < durls_arr.length; i++) {
            var current_item = durls_arr[i];
            var d = [];
            d.push(current_item['url']);
            d.push(current_item['length']);
            result.push(d);
        }
        return result;
    }

    function make_lbk(parsed_arr, cid){
        var file_path = '';
        var fileContent = "ffconcat version 1.0\n";
        var for_ios = [];
        for (var i = 0; i < parsed_arr.length; i++) {
            var url = parsed_arr[i][0];
            fileContent += "file " + url + '\n';
            var duration = Math.floor(parsed_arr[i][1]/1000) +1;
            fileContent += "duration " + duration + '\n';
            var o = {};
            o['url'] = url;
            o['duration'] = duration;
            for_ios.push(o);
        }
        if(typeof client!= 'undefined'){
            filePath = writeFileWithName(fileContent, "bilibili_default.lbk")
        } else {
            // iOS 客户端不支持播放本地文件，生成在线的 lbk 文件来播放
            var fileApi = "http://47.98.87.165/config/get_lbk?content=" + encodeURIComponent(JSON.stringify(for_ios)) + "&urlPrefix=" + "&filename=" + cid + "__default.lbk";
            backRs = urlRequest(fileApi);
            obj = JSON.parse(backRs);
            if (obj.code==0) {
                return obj.data;
            }
            else {
                return "error://bili_vod_fileapi_error";
            }
        }
        return filePath;
    }


    // var html_content = get_page_content();
    // var aid = extract_aid(html_content);
    // var play_info_data = extract_play_info(html_content)['data'];
    // var parsed_arr = parse_play_info_data(play_info_data);
    // var filePath = make_lbk(parsed_arr, aid);
    //
    // var result = [];
    // var r = filePath + "@@";
    // r += 'origin=https://www.bilibili.com@';
    // r += 'referer=' + page_url + "@";
    // r += 'user-agent=Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/71.0.3578.98 Chrome/71.0.3578.98 Safari/537.36@@';
    // r += "#480p";
    // result.push(r);
    // return result;

    var html_content = get_mobile_page_content();
    var mobile_video_info = extract_mobile_video_info(html_content);
    var video_url = 'http:' + mobile_video_info['reduxAsyncConnect']['videoInfo']['initUrl'];
    var aid = extract_aid(page_url);
    var mobile_url = 'https://m.bilibili.com/video/av'+ aid +'.html';
    var result = [];
    var r = video_url + '@@user-agent=Mozilla/5.0 (Linux; Android 7.0; PLUS Build/NRD90M) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/61.0.3163.98 Mobile Safari/537.36';
    r = r + '@referer=' + mobile_url;
    r = r + '@@#标清'
    result.push(r)
    return result
}
