# 激进优化配置
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
-overloadaggressively
-mergeinterfacesaggressively
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses

# Kotlin协程优化
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# 核心类保持（最小化保护）
-keep class com.xmhzj.dlgclockble.BleManager { *; }
-keep class com.xmhzj.dlgclockble.ConnectionState { *; }

# 枚举类保持
-keepclassmembers enum com.xmhzj.dlgclockble.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 第三方库：tyme4kt（农历日历库）
-keep class cn.6tail.tyme.** { *; }
-dontwarn cn.6tail.tyme.**

# Compose基础规则（精简版）
-keep,allowobfuscation class * {
    @androidx.compose.runtime.Composable public *(...);
}

# 移除所有日志输出
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int println(...);
}

# 移除Log调用
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# 保留必要的系统类
-keep class androidx.activity.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.navigation.** { *; }

# 警告抑制
-dontwarn androidx.compose.**
-dontwarn androidx.activity.**
-dontwarn androidx.navigation.**
-dontwarn android.bluetooth.**