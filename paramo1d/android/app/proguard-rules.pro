# Release shrinking (R8). Keep only what is called by name from outside the Java code.
# JNI: the Rust and Go libraries export Java_org_parano1d_courier_<Class>_<method>, so these classes and their
# native methods keep their names.
-keepclasseswithmembernames class org.parano1d.courier.** { native <methods>; }
-keep class org.parano1d.courier.NativeCore { *; }
-keep class org.parano1d.courier.NodeCore { *; }
-keep class org.parano1d.courier.WalletCore { *; }
-keep class org.parano1d.courier.NativeTunnel { *; }
# The WebView UI calls the bridge methods by name.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# Readable crash reports without shipping source file names.
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile
