-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
# 2026-09-14 体积优化: 以下三个 androidx 包原为整包 keep(合计 APK 内 ~298 类),
# 改为 allowshrinking —— 名字仍保留(反射安全), 仅允许 R8 裁掉静态不可达的类.
# 注意 AppCompatViewInflater / AppCompatDelegate 仍为整包 keep(它们是被反射构造的入口).
-keep,allowshrinking class androidx.vectordrawable.** { *; }
-keep class androidx.appcompat.widget.AppCompatViewInflater { *; }
-keep class androidx.appcompat.app.AppCompatDelegate { *; }
-keep,allowshrinking class androidx.activity.** { *; }
-keep,allowshrinking class androidx.fragment.** { *; }
-dontwarn androidx.vectordrawable.**
-dontwarn androidx.appcompat.**
-renamesourcefileattribute SourceFile
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
# 2026-10-08 去SDK化: 原「虎牙重打包 View 类包名取反」规则随 SDK 一并删除，恢复为全局规则。
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

-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, java.lang.String);
}

# ============================================================
# 内置源6（咪咕）JS 引擎
#   JsBridge 通过 addJavascriptInterface(obj, "client") 注入 WebView，
#   assets/js/native_layer.js 里以 client.getUrlContent(...) 等形式按名调用。
#   R8 若混淆或裁掉这些方法名，JS 侧会直接抛「方法不存在」导致解析中断。
# ============================================================
-keepclassmembers class com.tv.live.migu.js.JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.tv.live.migu.js.JsBridge { <init>(...); }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn com.squareup.okhttp.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
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
# 2026-09-14 体积优化: gson 原为整包 -keep(APK 内 217 类全留). 改为 allowshrinking:
#   R8 仍保留可达类及其名字(反射安全), 不可达类可裁.
#   反射要点(TypeToken 子类 / @SerializedName 字段)已由下面几条单独覆盖.
-keep,allowshrinking class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.tv.live.model.** { *; }
-keep class com.tv.live.config.AppConfig { *; }
-keep class com.tv.live.UrlConfig { *; }
-keep class com.tv.live.security.SecurityCore$SecurityCheck { *; }
-keep class com.tv.live.security.SecurityCore { *; }
# ASM 插桩重定向目标（OptionalNativeLoadClassVisitorFactory 硬编码引用）
-keep class com.tv.live.security.SecurityCore$NativeLibraryCompat { *; }
-keep class com.tv.live.security.SecurityCore$IntegrityCheck { *; }
-keep class com.tv.live.security.SecurityCore$StringProtector { *; }
-keep class com.tv.live.security.SecurityCore$DexProtector { *; }
-keep class com.tv.live.security.SecurityCore$SecurityGuard { *; }
-keep class com.tv.live.security.SecurityCore$AntiDebug { *; }
-keep class com.tv.live.security.SecurityCore$TamperReporter { *; }
-keep class com.tv.live.security.SecurityCore$StringObfuscator { *; }
-keep class com.tv.live.util.LogBridge$BuglyLogSender { *; }
-keep class com.tv.live.util.LogBridge$ExceptionReporter { *; }
-keep class com.tv.live.MainActivity { *; }
# 开机自启：4 个系统组件以 BootStartManager 内嵌类声明，manifest 名称含 $，R8 需保住类名与无参构造
-keep class com.tv.live.BootStartManager { *; }
-keep class com.tv.live.BootStartManager$BootReceiver { <init>(); }
-keep class com.tv.live.BootStartManager$BootStartReceiver { <init>(); }
-keep class com.tv.live.BootStartManager$BootJobService { <init>(); }
-keep class com.tv.live.BootStartManager$BootStartForegroundService { <init>(); }
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
-keepclassmembers class com.tv.live.util.LogBridge$LogCollector$LogEntry {
    <fields>;
}
# 崩溃组件以 LogBridge 内嵌类声明，manifest 名称含 $，R8 需保住类名与无参构造
-keep class com.tv.live.util.LogBridge$CrashActivity { <init>(); }
-keep class com.tv.live.util.LogBridge$CrashHandler { <init>(); }
# 自研虎牙解析器（HuyaStreamPlayer 内嵌类，供一起看线路/换线路逻辑使用）
-keep class com.tv.live.util.HuyaStreamPlayer$HuyaParser { *; }
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
