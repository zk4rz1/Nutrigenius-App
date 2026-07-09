# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in in the default proguard-android-optimize.txt file.

# Keep Health Connect classes for generic reflection serialization
-keep class androidx.health.connect.client.records.** { *; }
-keep class androidx.health.connect.client.units.** { *; }

# Keep JavaScript Interface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
