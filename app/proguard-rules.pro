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
# JS 桥（内置源6 咪咕等）：addJavascriptInterface(obj, "client")
#   assets/js/native_layer.js 以 client.getUrlContent(...) 等形式**按方法名**调用。
#
#   ⚠️ 只需保「@JavascriptInterface 标注的方法名」—— 这正是 Android 官方给出的规则形态。
#      · 类名无需保留：JS 只认注入名 "client"，与 Java 类名无关；
#      · 构造器无需保留：`new JsBridge(...)` 是普通 Java 调用，可达构造器 R8 自会保留；
#      · 注解必须存活：依赖上面的 `-keepattributes *Annotation*` ——
#        现代 WebView 靠反射读 @JavascriptInterface 枚举可调用方法，注解被裁则方法对 JS 不可见。
#   【2026-10-15】原另有 `-keep class ...JsBridge { <init>(...); }` 与类限定版 keepclassmembers，
#   现按官方形态收敛为通配一条。
# ============================================================
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
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
# ============================================================
# 【2026-10-15 审计修复 #6】精简 com.tv.live 的「整类 -keep」。
#
# 原 30 条整类保名，把「安全核心 + 源配置 + 解析器」的符号表直接公开，
# 抵消了 secstore 常量密文化的收益。逐条核实后只保留真正必要的：
#   · 死规则（目标类/包已不存在）→ 删除；
#   · 仅被普通 Java 代码引用（无 Class.forName、无清单/XML/assets/.so 按名引用）→ 删除，交回 R8 混淆；
#   · JNI 符号名依赖、清单内嵌类（$）→ 保留。
# 核实依据：全仓库 grep 无指向这些类的反射；assets/res/ 无按名引用；
#           .so 仅依赖 SecurityCore 的 JNI 符号名（见下）。
# 注：SecurityCore.TamperException 里出现的 "com.tv.live.security.TamperReporter" 等
#     是 setStackTrace 的【伪造堆栈】，仅作输出，不构成按名依赖。
# ============================================================

# —— 保留：JNI 符号名依赖 ——
# libtvlive_security.so 导出 Java_com_tv_live_security_SecurityCore_nativeConst 等，
# ART 按「运行时类名」拼符号 → 外层类名不可混淆/不可 repackage。
# native 方法名本身由下方 -keepclasseswithmembers ... native <methods> 规则保住。
-keepnames class com.tv.live.security.SecurityCore

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
# —— 【2026-10-15】原此处两条 keep 已删除 ——
#   · LogBridge$CrashActivity：清单里声明为 android:name="com.tv.live.util.LogBridge$CrashActivity"，
#     AGP 会**自动**生成完全相同的 keep —— 实测见
#     app/build/intermediates/aapt_proguard_file/release/processReleaseResources/aapt_rules.txt 第 12 行：
#       `-keep class com.tv.live.util.LogBridge$CrashActivity { <init>(); }`
#     故本工程内重复，删除（清单组件改名会直接 ClassNotFound，但 AGP 的这条会自动跟随清单）。
#   · LogBridge$CrashHandler：**不在清单里**，仅由 `CrashHandler.getInstance().init(this)` 等普通 Java 调用
#     （构造器 private、单例自建）→ 无反射、无清单依赖，可达成员 R8 自会保留，删除。

# —— 已删除规则（目标已不存在，留档备查） ——
#   -keep class com.tv.live.model.** { *; }                          ← 无 com.tv.live.model 包
#   -keep class com.tv.live.util.LogBridge$BuglyLogSender { *; }     ← 类已随「去 SDK 化」删除
#   -keep class com.tv.live.BootStartManager$BootReceiver ...        ← 无该内部类（4 条全删）
#   -keep class ...SecurityCore$NativeLibraryCompat { *; }           ← 依赖的 ASM 插件已删除
#   -keep class com.tv.live.loader.LiveSourceLoader { *; }           ← 与 loader.** 重复
#
# —— 已降级为「交回 R8 混淆」（普通 Java 引用，无按名依赖） ——
#   UrlConfig / AppConfig / PlaylistParser / Channel（仅在布局注释里出现）/ NetUtil /
#   CacheManager / MainActivity（清单组件，AGP 自动 keep 类名）/ BootStartManager /
#   HuyaStreamPlayer$HuyaParser / loader.** /
#   SecurityCore$SecurityCheck|IntegrityCheck|DexProtector|SecurityGuard|AntiDebug|
#   TamperReporter|StringObfuscator / LogBridge$ExceptionReporter

-renamesourcefileattribute SourceFile
-flattenpackagehierarchy ''
-mergeinterfacesaggressively
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions
# 【2026-10-15 体积排查·否证记录】不要试图用 -keepattributes !LineNumberTable,!SourceFile 剥离调试信息：
#   实测 R8 忽略该否定语法（dex 字节完全不变，md5 一致）。
#   且调试信息本身占比极小：classes.dex 内 debug_info 合计仅 30,830 B（占 dex 1.1%，
#   677 个唯一条目被 13,158 个方法共享）—— 即便能剥也只有 ~10KB 压缩收益，不值得折腾。
#   （注意 dexdump -l 输出的 691,933 条 line= 是「按方法重复计数」，不是真实字节数。）
-allowaccessmodification
-repackageclasses 'o'

# ============================================================
# 【2026-10-16】腾讯 Bugly 混淆规则
# ============================================================
-keep public class com.tencent.bugly.** {*;}
-dontwarn com.tencent.bugly.**
-keep class android.webkit.WebView {*;}
-keep class android.webkit.WebViewClient {*;}
