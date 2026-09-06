#include <jni.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "UdbAuthStub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT void JNICALL
Java_com_huyaudb_HuyaAuthCore_init(JNIEnv *env, jobject thiz) {
    LOGI("[STUB] HuyaAuthCore.init() - noop");
}

JNIEXPORT void JNICALL
Java_com_huyaudb_HuyaAuthCore_unInit(JNIEnv *env, jobject thiz) {
    LOGI("[STUB] HuyaAuthCore.unInit() - noop");
}

JNIEXPORT jbyteArray JNICALL
Java_com_huyaudb_HuyaAuthCore_sendMsg(JNIEnv *env, jobject thiz, jlong handle, jbyteArray data) {
    LOGI("[STUB] HuyaAuthCore.sendMsg(handle=%lld, len=%d) - returning empty",
         (long long)handle, data ? env->GetArrayLength(data) : 0);
    return env->NewByteArray(0);
}

JNIEXPORT void JNICALL
Java_com_huyaudb_HuyaAuthCore_receiveNet(JNIEnv *env, jobject thiz, jbyteArray data, jint len, jint a, jint b) {
    LOGI("[STUB] HuyaAuthCore.receiveNet(len=%d, a=%d, b=%d) - noop", len, a, b);
}

}
