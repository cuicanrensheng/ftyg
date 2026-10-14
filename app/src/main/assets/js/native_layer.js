
function getParser(url) {
    var parserDef =  JSON.stringify(RootFunction(url));
    console.log("getParser: "+parserDef)
    return parserDef;
}

function getParseResult(methodName, url) {
    console.log("methodName: "+methodName + ", url: " + url);
    var fn = window[methodName];
    var rs = JSON.stringify(fn(url));
    console.log("getParseResult: "+rs);
    return rs;
}

function getMD5(str) {
    return client.getMD5(str);
}

function writeFileWithName(fileContent, fileName) {
    return client.writeFile(fileContent, fileName);
}

function getRegex(attributes, pattern) {
    // new RegExp(pattern, attributes);
    var result = attributes.match(pattern);
    console.log("getRegex: " + result);
    return result;
}

function log(content) {
	console.log(content);
}

function AESDecrypt128(content, key) {
    return client.AESDecrypt128(content, key);
}

// 1 encode, 2 decode
function aes(mode, contentHexString, keyHexString, cipherType) {
    return client.aes(mode, contentHexString, keyHexString, cipherType)
}

function readFileToHex(fileName) {
    return client.readFileToHex(fileName);
}

function encC20Ep(content, key) {
    return client.encC20Ep(content, key);
}

function urlRequest(url) {
    return urlRequestWithPara(url, "Youku;5.2.1;iPhone OS;8.4.1;iPhone6,2", "User-Agent");
}

function getUrlContent(url) {
    var header = "{}"
    var content = client.getUrlContent(url, header);
    return content;
}

function urlRequestWithPara(url, headerVal, headerKey) {
    var header = "{'"+headerKey+"':'"+headerVal+"'}";
    var content = client.getUrlContent(url, header);
    return content;
}

function urlRequestWithParaAndCoding(url, arr, encode) {
    var content = client.getUrlContent(url, buildHeader(arr));
    return content;
}

function getRespAndHeaders(url, arr) {
    var content = client.getRespAndHeaders(url, buildHeader(arr));
    return content;
}

function postUrl(url, post, mediaType) {
    var header = "{}"
    var content = client.postUrlContent(url, post, header, mediaType);
    return content;
}

function postWithHeaders(url, post, arr, mediaType) {
    var content = client.postUrlContent(url, post, buildHeader(arr), mediaType);
    return content;
}

function getUtdid() {
    return client.getUtdid();
}

function getBstFk(c, key) {
    return client.getBstFk(c, key);
}

function getLocation(path) {
    return client.getLocation(path);
}

function buildHeader(arr) {
    var header = {};
    for (i=0; i<arr.length; i++) {
        header[arr[i].header] = arr[i].value;
    }
    return JSON.stringify(header);
}

//获取应用版本
function getVersion(){
     return client.getVersion();
}

//获取设备id,如果获取不到就返回none
function getDeviceId(){
    return client.getDeviceId();
}

//获取ip地址
function getIPAddress(){
    return client.getIPAddress();
}

//获取云图id
function getYuntvId(){
    return client.getYuntvId();
}

//获取设备型号
function getDeviceModel(){
    return client.getDeviceModel();
}

//获取是否是wifi信号true是wifi,false不是wifi
function getWifiStatus(){
    return client.getWifiStatus();
}

// 返回平台 安卓 "android" iPhone "iPhone" iPad "iPad"
function getPlatform() {
    return client.getPlatform();
}

// 返回系统版本号 如 "8.1.0"
function getSysVersion() {
    return client.getSysVersion();
}

// 返回包名 如 "com.fungo.yuntutv"
function getPackageName() {
    return client.getPackageName();
}

/**
*获取屏蔽结果
*
*/
function getBlockResult(methodName,confs,fromPage) {
    var fn = window[methodName];
    var rs = JSON.stringify(fn(confs,fromPage));
    return rs;
}

/**
*获取回看屏蔽结果
*
*/
function getBackBlockResult(methodName,confs,channelId) {
    var fn = window[methodName];
    var rs = JSON.stringify(fn(confs,channelId));
    return rs;
}

/**
 * 获取某个频道的屏蔽配置信息
 *
 * @param channelId
 * @return
 */
function getBlockConfs(channelId){
    return client.getBlockConfs(channelId);
}

/**
 * 获取某个频道的回看屏蔽配置信息
 *
 * @param channelId
 * @return
 */
function getBlockBackConfs(channelId){
    return client.getBlockBackConfs(channelId);
}

/**
 * 获取用户信息
 *
 * @return
 */
function getUserProfile(){

    return client.getUserProfile();
}

console.log = (function(oriLogFunc){
    return function(str) {
        client.setLog(str);
    }
})(console.log);
// ===========

