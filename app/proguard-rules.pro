# ProGuard / R8 Rules for Mumla OLED

# Preserve native method entrypoints and JNI bindings
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class se.lublin.humla.audio.NativeAudioInputEngine { *; }
-keep class se.lublin.humla.audio.NativeAudioInputEngine$* { *; }
-keepclassmembers class * implements se.lublin.humla.audio.NativeAudioInputEngine$AudioInputEngineListener {
    public void onAudioPacketEncoded(byte[], int, int, boolean, long);
    public void onTalkingStateChanged(boolean, float);
}
-keep class se.lublin.humla.audio.javacpp.** { *; }
-keep class com.googlecode.javacpp.** { *; }
-dontwarn com.googlecode.javacpp.BuildMojo
-dontwarn org.apache.maven.plugin.**

# Preserve Protobuf Lite generated message classes
-keep class se.lublin.humla.protobuf.Mumble** { *; }
-keep class com.google.protobuf.GeneratedMessageLite { *; }

# Preserve BouncyCastle Security Providers, certificate builders, and crypto engines
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-dontwarn org.bouncycastle.**

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
