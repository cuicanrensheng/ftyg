/**
 *
 * @param {*} confs 配置信息
 * @param {*} fromPage 来源页面
 * 搜索页 search
 * 列表页 list
 * 收藏 fav
 * 热门 hot
 * 历史 history
 * 预约 subscribe
 *
 */
function judgeBlock(confs, fromPage) {
    var filtedConfs = new Array();
    // 调用客户端接口getUserProfile()获取用户信息
    var userProfile = getUserProfile();
    for (var i = 0; i < confs.length; i++) {
        var conf = confs[i];
        var channelId = extractChannelId(conf, fromPage);
        if (channelId == null) {
            var result = {};
            result.conf = conf;
            result.gotoView = "";
            filtedConfs.push(result);
            continue;
        }
        // 调用客户端接口getBlockConfs(channelId)获取屏蔽配置
        var blockConfs = getBlockConfs(channelId);

        var validBlockConfs = getCurentBlockConfs(blockConfs);
        //var epg = getChannelConf(channelId);

        if (validBlockConfs == null || validBlockConfs.length < 1) {
            var result = {};
            result.conf = conf;
            result.gotoView = "fungo://player?channelId=" + channelId;
            filtedConfs.push(result);
            continue;
        }
        //屏蔽状态判断 0 普通用户  1 黑名单   2 白名单
        if (userProfile.blockStatus == 2) {
            var result = {};
            result.conf = conf;
            result.gotoView = "fungo://player?channelId=" + channelId;
            filtedConfs.push(result);
            continue;
        }
        // 暂取第一条做判断
        var firstBlockConf = validBlockConfs[0];
        // 若是黑名单用户且需要屏蔽黑名单
        if (userProfile.blockStatus == 1 && firstBlockConf.ifBlockBlacklistUsers == "YES") {
            // 获取展示和跳转
            var result = processConfAndGoView(conf, fromPage, firstBlockConf);
            if (result != null) {
                filtedConfs.push(result);
            }
            continue;
        }
        var location = userProfile.location;
        var isOverseas = false;
        // 当前是否在海外
        if (location.indexOf("中国") == -1) {
            isOverseas = true;
        }
        // 不屏蔽海外用户
        if (firstBlockConf.ifBlockOverseasUsers == "NO") {
            if (isOverseas) {
                var result = {};
                result.epg = epg;
                result.gotoView = "fungo://player?channelId=" + channelId;
                filtedConfs.push(result);
                continue;
            }
        }

        var blockedAreas = firstBlockConf.area.split(",");
        var isInBlockArea = judgeBlockArea(blockedAreas, location);
        if (isInBlockArea) {
            var minExp = parseInt(firstBlockConf.minExp);
            var minPoints = parseInt(firstBlockConf.minPoints);
            var userPoints = userProfile.points;
            var userExp = userProfile.userExp;
            // 经验或云币大于指定值不被屏蔽
            if (userExp >= minExp || userPoints >= minPoints) {
                var result = {};
                result.epg = epg;
                result.gotoView = "fungo://player?channelId=" + channelId;
                filtedConfs.push(result);
            } else {
                var result = processConfAndGoView(conf, fromPage, firstBlockConf);
                if (result != null) {
                    filtedConfs.push(result);
                }
            }
        } else {
            var result = {};
            result.epg = epg;
            result.gotoView = "fungo://player?channelId=" + channelId;
            filtedConfs.push(result);
        }
    }
    return filtedConfs;
}

function judgeBackBlock(confs) {
    // 调用客户端接口getUserProfile()获取用户信息
    var userProfile = getUserProfile();
    var processResult = new Array();
    var blockConfs = getBlockBackConfs(channelId);
    var noBlockConfs = blockConfs == null || blockConfs.length < 1 ? true : false;
    for (var i = 0; i < confs.length; i++) {
        var conf = confs[i];
        if (noBlockConfs) {
            var result = {};
            result.conf = conf;
            result.gotoView = "fungo://backPlayer?";
            processResult.push(result);
        } else {
            for (var j = 0; j < blockConfs.length; j++) {
                var blockConf = blockConfs[j];
                var epgTitle = blockConf.epgTitle;
                
            }
        }
    }
}

function extractChannelId(conf, fromPage) {
    if (fromPage == "list" || fromPage == "hot") {
        return conf.tv_id;
    }

    if (fromPage == "nav") {
        return null;
    }

    if (fromPage == "search") {
        return null;
    }

    return null;
}

function judgeBlockArea(blockedAreas, location) {
    for (var i = 0; i < blockedAreas.length; i++) {
        if (blockedAreas[i] == "全国") {
            return true;
        }
        // 在屏蔽地区内
        if (location.indexOf(blockedAreas[i]) != -1) {
            return true;
        }
    }
    return false;
}

function processConfAndGoView(conf, fromPage, blockConf) {
    // 搜索页 search 列表页 list 收藏 fav 热门 hot 历史 history 预约 subscribe 导航banner nav
    if (fromPage == "nav") {
        var ifHiddenNav = blockConf.ifHiddenNav;
        if (ifHiddenNav == "YES") {
            return null;
        }
        return null;
    }
    if (fromPage == "list") {
        var result = {};
        var ifHiddenList = blockConf.ifHiddenList;
        if (ifHiddenList == "YES") {
            return null; //返回空时客户端直接过滤
        }
        var ifStaticFrame = blockConf.ifStaticFrame;
        if (ifStaticFrame == "YES") {
            conf.frameurl = "http://tvnow-pic.yuntuds.net/copyright_icon/img_stop_play_big.png";
        }
        result.conf = conf;
        // 跳转模式: 0-跳百度, 根据百度跳转模式确定后续动作; 1-跳转到epg中配置的频道主页; 2-跳转到自定义配置; 3-跳转视频播放外站引导页
        var jumpModeList = blockConf.jumpModeList;
        if (jumpModeList == "0") {
            result.gotoView = "fungo://goMaskBaidu?"
        } else if (jumpModeList == "1") {
            var url = conf.web_url;
            result.gotoView = "fungo://openUrlInBrowser?url=" + encodeURIComponent(url);
        } else if (jumpModeList == "2") {
            result.gotoView = "fungo://gotoCustom?";
        } else if (jumpModeList == "3") {
            var channelId = conf.tv_id;
            result.gotoView = "fungo://playerBlocked?channelId=" + channelId;
        }
        return result;
    }

    if (fromPage == "search") {
        // 是否对搜索结果执行过滤
        var switchOnSearch = blockConf.switchOnSearch;
        if (switchOnSearch == "YES") {
            return null;
        }
        return null;
    }

    if (fromPage == "fav") {
        // 是否对收藏进行过滤
        var ifHiddenFav = blockConf.ifHiddenFav;
        if (ifHiddenFav == "YES") {
            return null;
        }
        return null;
    }
}

function getCurentBlockConfs(blockConfs) {
    var filtedBlockConfs = new Array();
    for (var i = 0; i < blockConfs.length; i++) {
        var blockConf = blockConfs[i];
        var validTimeRange = isValidTimeRange(blockConf);
        if (validTimeRange) {
            filtedBlockConfs.push(blockConf);
        }
    }
    return filtedBlockConfs;
}

function isValidTimeRange(blockConf) {
    var startEndTimes = blockConf.startEndTime;
    var startEndTimesArr = startEndTimes.split(",");
    for (var j = 0; j < startEndTimesArr.length; j++) {
        var d = new Date();
        var localTime = d.getTime();
        var localOffset = d.getTimezoneOffset() * 60000;
        // 世界时间
        var utc = localTime + localOffset;
        // 世界时间转北京时间
        var beijingMill = utc + 8 * 60 * 60 * 1000;
        var startEndTime = startEndTimesArr[j];
        // 北京时间
        var startEndTimeArr = startEndTime.split("-");
        var startTimeStr = startEndTimeArr[0];
        var endTimeStr = startEndTimeArr[1];
        var startYmd = startTimeStr.split(' ')[0];
        var startHmi = startTimeStr.split(' ')[1].split(':');
        var startTimeMill = new Date(strToInt(startYmd, 0, 4), strToInt(startYmd, 4, 6) - 1, strToInt(startYmd, 6, 8), strToInt(startHmi[0]), strToInt(startHmi[1]), strToInt(startHmi[2])).getTime();
        var endYmd = endTimeStr.split(' ')[0];
        var endHmi = endTimeStr.split(' ')[1].split(':');
        var endTimeMill = new Date(strToInt(endYmd, 0, 4), strToInt(endYmd, 4, 6) - 1, strToInt(endYmd, 6, 8), strToInt(endHmi[0]), strToInt(endHmi[1]), strToInt(endHmi[2])).getTime();

        if (beijingMill >= startTimeMill && beijingMill <= endTimeMill) {
            return true;
        }
    }
    return false;
}

function strToInt(str, i, j) {
    return parseInt(str.substring(i, j), 10);
}

function rlog(msg) {
    if (true) {
        return;
    }
    var api = 'http://42.121.111.105:3553/monitor/client_rlog.php?msg=' + encodeURIComponent(msg);
    urlRequest(api);
}
