# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /opt/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}
# Keep names and members used by libtorrent JNI and ACRA reflection.
-keep class org.libtorrent4j.swig.** { *; }
-keepclassmembers class org.proninyaroslav.libretorrent.BuildConfig { public static <fields>; }
-keep class org.nanohttpd.** {*;}

# Navigation loads custom argument types by their XML class names.
-keep,allowoptimization class org.proninyaroslav.libretorrent.core.model.data.entity.TagInfo { *; }
-keep,allowoptimization class org.proninyaroslav.libretorrent.core.model.filetree.FilePriority { *; }
-keep,allowoptimization class org.proninyaroslav.libretorrent.ui.detailtorrent.SpeedLimitDialog$Speed { *; }
-keep,allowoptimization class org.proninyaroslav.libretorrent.ui.filemanager.FileManagerConfig { *; }

# Existing preferences and JSON data store enum constants and Java field names.
-keep enum org.proninyaroslav.libretorrent.** { *; }
-keep,allowoptimization class org.proninyaroslav.libretorrent.core.model.data.** { *; }

# GeckoRuntime loads SnakeYAML, which uses reflective type metadata and package names.
-keep class org.yaml.snakeyaml.** { *; }
