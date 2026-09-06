/*
 * libmmkv.so JNI stub
 * 背景：本应用 isNeedPlay(false)，SDK 播放器（含弹幕配置）不会真正读写 MMKV 数据，
 *       但 kiwi-barrage 弹幕 SDK 静态初始化强制 System.loadLibrary("mmkv")，
 *       且 MmkvConfigImpl 的调用链无 try-catch（空实现/剔除 → UnsatisfiedLinkError → 崩溃）。
 * 方案：提供极简 stub，所有 JNI 函数返回安全默认值（handle=1，decode 返回默认值，encode 返回 true）。
 *       已验证全工程仅 MmkvConfigImpl 引用 MMKV，且 MMKVContentProvider.onCreate 不触 native。
 */
#include <jni.h>

#define FAKE_HANDLE ((jlong)1)

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm; (void)reserved;
    return JNI_VERSION_1_6;
}

/* ================= 静态方法 ================= */
JNIEXPORT void JNICALL Java_com_tencent_mmkv_MMKV_initialize(JNIEnv* env, jclass clazz, jstring root) {
    (void)env; (void)clazz; (void)root;
}

JNIEXPORT void JNICALL Java_com_tencent_mmkv_MMKV_onExit(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
}

JNIEXPORT jlong JNICALL Java_com_tencent_mmkv_MMKV_getMMKVWithID(JNIEnv* env, jclass clazz, jstring id, jint mode, jstring crypt) {
    (void)env; (void)clazz; (void)id; (void)mode; (void)crypt;
    return FAKE_HANDLE;
}

JNIEXPORT jlong JNICALL Java_com_tencent_mmkv_MMKV_getMMKVWithIDAndSize(JNIEnv* env, jclass clazz, jstring id, jint size, jint mode, jstring crypt) {
    (void)env; (void)clazz; (void)id; (void)size; (void)mode; (void)crypt;
    return FAKE_HANDLE;
}

JNIEXPORT jlong JNICALL Java_com_tencent_mmkv_MMKV_getDefaultMMKV(JNIEnv* env, jclass clazz, jint mode, jstring crypt) {
    (void)env; (void)clazz; (void)mode; (void)crypt;
    return FAKE_HANDLE;
}

JNIEXPORT jlong JNICALL Java_com_tencent_mmkv_MMKV_getMMKVWithAshmemFD(JNIEnv* env, jclass clazz, jstring id, jint fd, jint metaFd, jstring crypt) {
    (void)env; (void)clazz; (void)id; (void)fd; (void)metaFd; (void)crypt;
    return FAKE_HANDLE;
}

JNIEXPORT jint JNICALL Java_com_tencent_mmkv_MMKV_pageSize(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return 4096;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_mmkv_MMKV_isFileValid(JNIEnv* env, jclass clazz, jstring id) {
    (void)env; (void)clazz; (void)id;
    return JNI_TRUE;
}

/* ================= 实例方法 ================= */
JNIEXPORT jstring JNICALL Java_com_tencent_mmkv_MMKV_cryptKey(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return NULL;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_mmkv_MMKV_reKey(JNIEnv* env, jobject thiz, jstring crypt) {
    (void)env; (void)thiz; (void)crypt;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_tencent_mmkv_MMKV_checkReSetCryptKey(JNIEnv* env, jobject thiz, jstring crypt) {
    (void)env; (void)thiz; (void)crypt;
}

JNIEXPORT jstring JNICALL Java_com_tencent_mmkv_MMKV_mmapID(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return NULL;
}

JNIEXPORT void JNICALL Java_com_tencent_mmkv_MMKV_lock(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
}

JNIEXPORT void JNICALL Java_com_tencent_mmkv_MMKV_unlock(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_mmkv_MMKV_tryLock(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_mmkv_MMKV_decodeBool(JNIEnv* env, jobject thiz, jlong handle, jstring key, jboolean def) {
    (void)env; (void)thiz; (void)handle; (void)key;
    return def;
}

JNIEXPORT jint JNICALL Java_com_tencent_mmkv_MMKV_decodeInt(JNIEnv* env, jobject thiz, jlong handle, jstring key, jint def) {
    (void)env; (void)thiz; (void)handle; (void)key;
    return def;
}

JNIEXPORT jlong JNICALL Java_com_tencent_mmkv_MMKV_decodeLong(JNIEnv* env, jobject thiz, jlong handle, jstring key, jlong def) {
    (void)env; (void)thiz; (void)handle; (void)key;
    return def;
}

JNIEXPORT jfloat JNICALL Java_com_tencent_mmkv_MMKV_decodeFloat(JNIEnv* env, jobject thiz, jlong handle, jstring key, jfloat def) {
    (void)env; (void)thiz; (void)handle; (void)key;
    return def;
}

JNIEXPORT jdouble JNICALL Java_com_tencent_mmkv_MMKV_decodeDouble(JNIEnv* env, jobject thiz, jlong handle, jstring key, jdouble def) {
    (void)env; (void)thiz; (void)handle; (void)key;
    return def;
}

JNIEXPORT jstring JNICALL Java_com_tencent_mmkv_MMKV_decodeString(JNIEnv* env, jobject thiz, jlong handle, jstring key, jstring def) {
    (void)env; (void)thiz; (void)handle; (void)key;
    return def;
}

JNIEXPORT jbyteArray JNICALL Java_com_tencent_mmkv_MMKV_decodeBytes(JNIEnv* env, jobject thiz, jlong handle, jstring key) {
    (void)env; (void)thiz; (void)handle; (void)key;
    return NULL;
}

JNIEXPORT jobjectArray JNICALL Java_com_tencent_mmkv_MMKV_decodeStringSet(JNIEnv* env, jobject thiz, jlong handle, jstring key) {
    (void)env; (void)thiz; (void)handle; (void)key;
    return NULL;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_mmkv_MMKV_encodeBool(JNIEnv* env, jobject thiz, jlong handle, jstring key, jboolean value) {
    (void)env; (void)thiz; (void)handle; (void)key; (void)value;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_mmkv_MMKV_encodeInt(JNIEnv* env, jobject thiz, jlong handle, jstring key, jint value) {
    (void)env; (void)thiz; (void)handle; (void)key; (void)value;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_mmkv_MMKV_encodeLong(JNIEnv* env, jobject thiz, jlong handle, jstring key, jlong value) {
    (void)env; (void)thiz; (void)handle; (void)key; (void)value;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_mmkv_MMKV_encodeFloat(JNIEnv* env, jobject thiz, jlong handle, jstring key, jfloat value) {
    (void)env; (void)thiz; (void)handle; (void)key; (void)value;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_mmkv_MMKV_encodeDouble(JNIEnv* env, jobject thiz, jlong handle, jstring key, jdouble value) {
    (void)env; (void)thiz; (void)handle; (void)key; (void)value;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_mmkv_MMKV_encodeString(JNIEnv* env, jobject thiz, jlong handle, jstring key, jstring value) {
    (void)env; (void)thiz; (void)handle; (void)key; (void)value;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_mmkv_MMKV_encodeBytes(JNIEnv* env, jobject thiz, jlong handle, jstring key, jbyteArray value) {
    (void)env; (void)thiz; (void)handle; (void)key; (void)value;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_mmkv_MMKV_encodeSet(JNIEnv* env, jobject thiz, jlong handle, jstring key, jobjectArray value) {
    (void)env; (void)thiz; (void)handle; (void)key; (void)value;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_mmkv_MMKV_containsKey(JNIEnv* env, jobject thiz, jlong handle, jstring key) {
    (void)env; (void)thiz; (void)handle; (void)key;
    return JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_tencent_mmkv_MMKV_count(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env; (void)thiz; (void)handle;
    return 0;
}

JNIEXPORT jlong JNICALL Java_com_tencent_mmkv_MMKV_totalSize(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env; (void)thiz; (void)handle;
    return 0;
}

JNIEXPORT jobjectArray JNICALL Java_com_tencent_mmkv_MMKV_allKeys(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return NULL;
}

JNIEXPORT void JNICALL Java_com_tencent_mmkv_MMKV_removeValueForKey(JNIEnv* env, jobject thiz, jlong handle, jstring key) {
    (void)env; (void)thiz; (void)handle; (void)key;
}

JNIEXPORT void JNICALL Java_com_tencent_mmkv_MMKV_removeValuesForKeys(JNIEnv* env, jobject thiz, jobjectArray keys) {
    (void)env; (void)thiz; (void)keys;
}

JNIEXPORT void JNICALL Java_com_tencent_mmkv_MMKV_clearAll(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
}

JNIEXPORT void JNICALL Java_com_tencent_mmkv_MMKV_clearMemoryCache(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
}

JNIEXPORT void JNICALL Java_com_tencent_mmkv_MMKV_sync(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
}

JNIEXPORT jint JNICALL Java_com_tencent_mmkv_MMKV_ashmemFD(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return -1;
}

JNIEXPORT jint JNICALL Java_com_tencent_mmkv_MMKV_ashmemMetaFD(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz;
    return -1;
}
