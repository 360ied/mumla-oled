# ProGuard / R8 Rules for Mumla OLED

# Preserve native method entrypoints and JNI bindings
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class se.lublin.humla.audio.NativeAudioInputEngine { *; }
-keep class se.lublin.humla.audio.javacpp.** { *; }
-keep class com.googlecode.javacpp.** { *; }
-dontwarn com.googlecode.javacpp.BuildMojo
-dontwarn org.apache.maven.plugin.**

# Preserve Protobuf generated message classes
-keep class se.lublin.humla.protobuf.Mumble** { *; }
-keep class com.google.protobuf.** { *; }

# Preserve SpongyCastle Security Providers and crypto engines
-keep class org.spongycastle.jce.provider.** { *; }
-keep class org.spongycastle.jcajce.provider.** { *; }
-dontwarn org.spongycastle.**

# MiniDNS & GuardianProject Netcipher
-keep class org.minidns.** { *; }
-dontwarn info.guardianproject.netcipher.**
-dontwarn org.jsoup.**

# Preserve Parcelable CREATOR fields
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Preserve Preference fragments and custom views referenced via XML
-keep public class * extends androidx.preference.Preference {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keep public class * extends androidx.preference.PreferenceFragmentCompat {
    public <init>();
}
