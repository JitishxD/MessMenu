# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- MessMenu app rules ---

# Compose: keep Compose runtime metadata used by tooling/inspection in some cases.
# (Most Compose is R8-friendly; these are conservative and safe.)
-keepattributes *Annotation*

# DataStore uses Kotlin coroutines; keep coroutine debug metadata not needed.
# R8 will strip it in release anyway, but be explicit.
-dontwarn kotlinx.coroutines.**

# JSONObject is used directly; no reflection-based model parsing in this app.
# Nothing special required, but keep org.json around if future builds shrink too aggressively.
-keep class org.json.** { *; }

# If you later add serialization/reflection, add keep rules here.