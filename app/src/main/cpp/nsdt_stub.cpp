#include <jni.h>
#include <android/log.h>

#define LOG_TAG "NSDTStub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT void JNICALL
Java_com_huya_mtp_nsdt_NSDT_init(JNIEnv *env, jclass clazz) {
    LOGI("[STUB] NSDT.init() - noop");
}

JNIEXPORT jint JNICALL
Java_com_huya_mtp_nsdt_NSDT_ping(JNIEnv *env, jclass clazz, jint taskId,
                                 jstring host, jstring ip, jint count,
                                 jint interval, jint timeout) {
    LOGI("[STUB] NSDT.ping(taskId=%d, count=%d) - disabled", (int)taskId, (int)count);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_huya_mtp_nsdt_NSDT_traceroute(JNIEnv *env, jclass clazz, jint taskId,
                                       jobjectArray cmds) {
    LOGI("[STUB] NSDT.traceroute(taskId=%d) - disabled", (int)taskId);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_huya_mtp_nsdt_NSDT_tcp(JNIEnv *env, jclass clazz, jint taskId,
                                jstring host, jstring ip, jint port,
                                jint timeout, jstring body) {
    LOGI("[STUB] NSDT.tcp(taskId=%d, port=%d) - disabled", (int)taskId, (int)port);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_huya_mtp_nsdt_NSDT_detectIP(JNIEnv *env, jclass clazz, jobjectArray ips,
                                     jintArray ports, jint a, jint b, jint c,
                                     jint d, jint e, jint f, jint g, jint h,
                                     jint i, jstring body) {
    LOGI("[STUB] NSDT.detectIP() - disabled");
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_huya_mtp_nsdt_NSDT_getIPStatus(JNIEnv *env, jclass clazz, jstring ip) {
    LOGI("[STUB] NSDT.getIPStatus() - disabled");
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_huya_mtp_nsdt_NSDT_getIPRtt(JNIEnv *env, jclass clazz, jstring ip) {
    LOGI("[STUB] NSDT.getIPRtt() - disabled");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_huya_mtp_nsdt_NSDT_test(JNIEnv *env, jclass clazz) {
    LOGI("[STUB] NSDT.test() - noop");
}

}
