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

    /**
     * 【2026-10-26 重写】B 站页面结构已变，原实现必然失败：
     *   - `window.__INITIAL_STATE__` 已从页面移除 → extract_cid 的正则 exec 返回 null
     *     → 抛 "Cannot read properties of null (reading '0')"；
     *   - `window.__playinfo__` 从「JSON 字面量」变成「赋值表达式」
     *     （`window.__playinfo__ = playurlSSRData.data`）→ 旧正则 slice 取不到 JSON。
     *
     *   新结构：`const playurlSSRData = {...}`，其中
     *     data.result.video_info.durl[] = 可直接播放的 mp4 直链（含鉴权参数）
     *     data.result.arc.cid / bvid
     *   本实现：括号配对提取 playurlSSRData → 读 video_info.durl；
     *   找不到时回退旧结构（旧 __playinfo__ / __INITIAL_STATE__）。
     *
     *   返回格式沿用原版约定「地址@@origin=…@@referer=…@@user-agent=…」，
     *   由 Java 侧 JzUrlResolver.splitPlayHeaders 拆出播放头。
     */
    function extract_balanced_json(text, marker){
        var i = text.indexOf(marker);
        if (i < 0) return null;
        var j = text.indexOf('{', i);
        if (j < 0) return null;
        var depth = 0, inStr = false, esc = false;
        for (var k = j; k < text.length; k++) {
            var c = text.charAt(k);
            if (inStr) {
                if (esc) { esc = false; }
                else if (c === '\\') { esc = true; }
                else if (c === '"') { inStr = false; }
                continue;
            }
            if (c === '"') { inStr = true; }
            else if (c === '{') { depth++; }
            else if (c === '}') {
                depth--;
                if (depth === 0) return text.substring(j, k + 1);
            }
        }
        return null;
    }

    function try_new_ssr(content){
        var blob = extract_balanced_json(content, 'playurlSSRData');
        if (!blob) return null;
        var root;
        try { root = JSON.parse(blob); } catch (e) { return null; }
        var result = root && root.data && root.data.result;
        if (!result) return null;
        var vi = result.video_info;
        if (!vi || !vi.durl || vi.durl.length === 0) return null;
        var url = vi.durl[0].url;
        if (!url) return null;
        return {url: url, cid: (result.arc && result.arc.cid) ? result.arc.cid : '0'};
    }

    function extract_play_info(content) {
        // 旧结构兜底：<script>window.__playinfo__ = {...}</script>
        var pattern = new RegExp(/\<script\>window\.__playinfo__\s*=\s*(\{[\s\S]*?\})\s*\<\/script\>/);
        var r = pattern.exec(content);
        if (!r) return null;
        try { return JSON.parse(r[1]); } catch (e) { return null; }
    }

    function extract_cid(content){
        var pattern = new RegExp((/window\.__INITIAL_STATE__=(.*\]\})\;/));
        var r = pattern.exec(content);
        if (!r) return '0';
        try {
            var result = JSON.parse(r[0].slice(25,-1));
            return (result['epInfo'] && result['epInfo']['cid']) ? result['epInfo']['cid'] : '0';
        } catch (e) { return '0'; }
    }

    function parse_play_info_data(data){
        var result = [];
        var durls_arr = data['durl'];
        if (!durls_arr) return result;
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
    var UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/71.0.3578.98 Chrome/71.0.3578.98 Safari/537.36';

    // ① 新结构（当前线上版本）：直接给 mp4 直链
    var neu = try_new_ssr(html_content);
    if (neu) {
        var out = neu.url + "@@";
        out += 'origin=https://www.bilibili.com@@';
        out += 'referer=' + page_url + "@@";
        out += 'user-agent=' + UA + "@@";
        out += "#480p";
        var res = [];
        res.push(out);
        return res;
    }

    // ② 旧结构兜底
    var cid = extract_cid(html_content);
    var pi = extract_play_info(html_content);
    if (!pi) return [];
    var play_info_data = pi['data'];
    if (!play_info_data) return [];
    var parsed_arr = parse_play_info_data(play_info_data);
    if (!parsed_arr || parsed_arr.length === 0) return [];
    var filePath = make_lbk(parsed_arr, cid);

    var result = [];
    var r = filePath + "@@";
    r += 'origin=https://www.bilibili.com@';
    r += 'referer=' + page_url + "@@";
    r += 'user-agent=' + UA + "@@";
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
