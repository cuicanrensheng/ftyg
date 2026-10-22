// tvpass://AEEast
// https://node-proxy.huarentv.live/tvpass/DisneyChannelWest
function tvpassParsePlay(originUrl) {
    let tvId = originUrl.split('://')[1]
    let fileApi = `https://node-proxy.huarentv.live/tvpass/${tvId}`
    let res = urlRequest(fileApi);
    obj = JSON.parse(res);
    if (obj.code === 0) return [obj.data]
    return ['']
}
