# ProGuard/R8 rules for HyperPILL.
#
# This module is not a normal app: its classes are loaded into OTHER apps' processes by
# LSPosed, and the system instantiates hook entry points reflectively. Anything reached
# only through that reflection must be kept explicitly, because R8 cannot see the edges
# and a bad keep rule fails at hook-install time (a silent no-op) rather than at build
# time.

# --- LSPosed / libxposed entry points -----------------------------------------------
# The framework reads this class name from the module metadata and instantiates it in the
# host process; it must keep its name, its constructor, and its overridden methods.
-keep class com.karin.hyperpill.hook.HookEntry { *; }
-keep class * implements io.github.libxposed.api.XposedModule { *; }

# Hook contexts subclass the framework base and are matched by the framework, so their
# names and members are part of the contract rather than an internal detail.
-keep class * extends com.karin.hyperpill.hook.HookContext { *; }

# --- Reflective access inside the hooks ---------------------------------------------
# The hooks reach into MIUI/Bluetooth internals by field and method NAME via
# getObjectField/callMethod. Those members live in framework classes we do not own, so
# only our own reflection helpers need to survive; the framework classes are excluded
# from R8's input anyway.
-keep class com.karin.hyperpill.hook.HookContext { *; }
-keepclassmembers class com.karin.hyperpill.hook.* {
    <methods>;
    <fields>;
}

# --- Types crossing the process/broadcast boundary ----------------------------------
# Broadcast extras are written and read by (field) name across processes and across
# module versions, and Parcelable/extras keys are stringly typed, so these value carriers
# must not be renamed or have members removed.
-keep class com.karin.hyperpill.pods.BatteryParams { *; }
-keep class com.karin.hyperpill.pods.PodParams { *; }
-keep class com.karin.hyperpill.pods.PillProduct { *; }
-keep class com.karin.hyperpill.pods.PillProducts { *; }

# --- Compose -------------------------------------------------------------------------
# Compose relies on runtime reflection over @Composable signatures in places (notably
# the tooling and the recomposer); the standard rules ship with the Compose artifacts,
# but the UI call roots are kept so a shrunk build cannot drop the settings surface.
-keep class com.karin.hyperpill.MainActivity { *; }
-keep class com.karin.hyperpill.**ComposableSingletons** { *; }
-dontwarn androidx.compose.**

# --- Kotlin / coroutines metadata ----------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn kotlinx.**
-keepclassmembers class kotlin.Metadata { public <methods>; }

# --- Keep names readable in stack traces ---------------------------------------------
# Hooking failures surface as logcat lines naming the class and method being hooked;
# obfuscating our own classes makes those diagnostics unusable.
-keepnames class com.karin.hyperpill.** { *; }
