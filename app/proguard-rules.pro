-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-keep class androidx.vectordrawable.** { *; }
-keep class androidx.appcompat.widget.AppCompatViewInflater { *; }
-keep class androidx.appcompat.app.AppCompatDelegate { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.fragment.** { *; }
-dontwarn androidx.vectordrawable.**
-dontwarn androidx.appcompat.**
-renamesourcefileattribute SourceFile
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
-keep,allowshrinking class com.huya.** { *; }
-keep class * extends com.duowan.taf.jce.JceStruct { *; }
-keep class com.duowan.taf.** { *; }
-keep class com.duowan.kiwi.** { *; }
-keep class com.duowan.networkmars.** { *; }
-keep,allowshrinking class com.duowan.** { *; }
-keep,allowshrinking class com.huyaudbunify.** { *; }
-keep,allowshrinking class com.huyaudb.** { *; }
-keep class com.huya.security.** { *; }
-keep,allowshrinking class com.duowan.live.one.module.uploadLog.FeedBackModule { <init>(); }
-keep class com.huya.berry.module.HysignalPushModule { <init>(); }
-keep class com.huya.berry.gamesdk.module.CommonService { <init>(); }
-keep class com.huya.berry.module.live.SdkLiveService { <init>(); }
-keep class com.huya.berry.sdklive.LiveService { <init>(); }
-keep class com.huya.berry.client.ServerStartManager { <init>(); }
-keep class com.huya.berry.module.live.SdkLiveService { <init>(); }
-keep class com.huya.berry.sdklive.LiveService { <init>(); }
-keep class com.huya.hysignal.jce.** { *; }
-keep class com.huya.hyhttpdns.jce.** { *; }
-keep class com.huya.statistics.jce.** { *; }
-keep class com.huya.mtp.hyns.miniprogram.jce.** { *; }
-keep class com.huya.mtp.hyns.api.** { *; }
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, java.lang.String);
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn com.squareup.okhttp.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn com.duowan.ark.util.ThreadUtils
-dontwarn com.duowan.ark.util.pack.**
-dontwarn com.duowan.ark.**
-dontwarn com.duowan.auk.share.**
-dontwarn com.duowan.**
-dontwarn com.huya.mtp.hyns.volley.**
-dontwarn com.duowan.live.**
-dontwarn com.duowan.kiwi.**
-dontwarn com.huya.force.**
-dontwarn com.huya.berry.**
-dontwarn com.huya.component.**
-dontwarn com.huya.mtp.**
-dontwarn com.huya.security.**
-dontwarn com.huya.encrypt.**
-dontwarn com.huya.stats.**
-dontwarn com.huya.**
-dontwarn com.duowan.**
-dontwarn retrofit2.**
-dontwarn org.greenrobot.eventbus.**
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations, AnnotationDefault
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowshrinking class com.huya.berry.module.live.** { *; }
-keep class com.huya.berry.module.** { *; }
-keep class com.huya.mtp.hyns.retrofit.** { *; }
-keep class com.huya.mtp.hyns.** { *; }
-keep class com.huya.mtp.** { *; }
-dontwarn rx.**
-dontwarn io.reactivex.**
-keep,allowshrinking class rx.** { *; }
-keep,allowshrinking class io.reactivex.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.tv.live.model.** { *; }
-keep class com.tv.live.config.AppConfig { *; }
-keep class com.tv.live.UrlConfig { *; }
-keep class com.tv.live.SecurityCheck { *; }
-keep class com.tv.live.security.SecurityCore { *; }
-keep class com.tv.live.security.IntegrityCheck { *; }
-keep class com.tv.live.security.StringProtector { *; }
-keep class com.tv.live.security.DexProtector { *; }
-keep class com.tv.live.security.SecurityGuard { *; }
-keep class com.tv.live.security.AntiDebug { *; }
-keep class com.tv.live.security.TamperReporter { *; }
-keep class com.tv.live.security.StringObfuscator { *; }
-keep class com.tv.live.util.BuglyLogSender { *; }
-keep class com.tv.live.util.ExceptionReporter { *; }
-keep class com.tv.live.util.HuyaSDKLogger { *; }
-keep class com.tv.live.util.NoOpReportApi { *; }
-keep class com.tv.live.util.NoOpCrashService { <init>(); }
-keep class com.tv.live.util.NoOpHuyaStatisApi { <init>(...); *; }
-keep class com.tv.live.MainActivity { *; }
-keepclassmembers class * {
    @com.google.inject.Inject <init>(...);
    @javax.inject.Inject <init>(...);
    @dagger.Inject <init>(...);
}
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int println(...);
}
-keepclassmembers class com.tv.live.util.LogCollector$LogEntry {
    <fields>;
}
-keep class com.tv.live.util.HuyaParser { *; }
-keep class com.tv.live.loader.LiveSourceLoader { *; }
-keep class com.tv.live.loader.** { *; }
-keep class com.tv.live.PlaylistParser { *; }
-keep class com.tv.live.Channel { *; }
-keep class com.tv.live.util.NetUtil { *; }
-keep class com.tv.live.util.CacheManager { *; }
-renamesourcefileattribute SourceFile
-flattenpackagehierarchy ''
-mergeinterfacesaggressively
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions
-allowaccessmodification
-repackageclasses 'o'
-dontwarn com.duowan.ark.api.ApiHolder
-dontwarn com.duowan.ark.api.DebugApi
-dontwarn com.duowan.ark.api.DebugApiDelegate
-dontwarn com.duowan.ark.api.LogApi
-dontwarn com.duowan.ark.api.LogApiDelegate
-dontwarn com.duowan.ark.asignal.notify.PropertySet
-dontwarn com.duowan.ark.util.BitmapUtils
-dontwarn com.duowan.ark.util.ConfigWithTimeout
-dontwarn com.duowan.ark.util.StringUtils
-dontwarn com.duowan.ark.util.json.JsonUtils
