# 通用Android混淆规则
-keep class android.support.** { *; }
-keep interface android.support.** { *; }

# Kotlin相关规则
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# Kotlin协程
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Jetpack Compose相关规则
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Compose UI工具
-keep class androidx.compose.ui.tooling.** { *; }
-dontwarn androidx.compose.ui.tooling.**

# AndroidX Lifecycle
-keep class androidx.lifecycle.** { *; }
-keep interface androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# Navigation Compose
-keep class androidx.navigation.** { *; }
-keep interface androidx.navigation.** { *; }
-dontwarn androidx.navigation.**

# Activity Compose
-keep class androidx.activity.** { *; }
-keep interface androidx.activity.** { *; }
-dontwarn androidx.activity.**

# Material 3
-keep class androidx.compose.material3.** { *; }
-keep interface androidx.compose.material3.** { *; }
-dontwarn androidx.compose.material3.**

# Bluetooth相关规则
-keep class android.bluetooth.** { *; }
-keep interface android.bluetooth.** { *; }
-dontwarn android.bluetooth.**

# BleManager单例类保持
-keep class com.xmhzj.dlgclockble.BleManager { *; }
-keep class com.xmhzj.dlgclockble.BleManager$** { *; }

# 枚举类保持
-keepclassmembers enum com.xmhzj.dlgclockble.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable类
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Serializable类
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 第三方库：tyme4kt（农历日历库）
-keep class cn.6tail.tyme.** { *; }
-dontwarn cn.6tail.tyme.**

# Composable函数（@Composable注解的方法）
-keep class * {
    @androidx.compose.runtime.Composable public *(...);
    @kotlin androidx.compose.runtime.ComposableInvalidateGroup public *(...);
}

# 内联函数优化
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

# 移除日志
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}

# 保留Native方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留构造函数
-keepclasseswithmembernames class * {
    public <init>(android.content.Context);
}

# 保留自定义View
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# 保留R文件
-keep class **.R
-keep class **.R$* {
    public static final int *;
}

# 保留BuildConfig
-keep class **.BuildConfig { *; }

# 优化建议
-optimizationpasses 5
-overloadaggressively
-repackageclasses ''
-allowaccessmodification
-mergeinterfacesaggressively