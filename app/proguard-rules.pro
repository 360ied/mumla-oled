# ProGuard / R8 Rules for Mumla OLED

# Preserve native method entrypoints and JNI bindings
-keepclasseswithmembernames class * {
    native <methods>;
}
# Explicit JNI (Java_se_lublin_humla_audio_NativeCrashTest_nativeCrash) needs the class name.
# (Top wildcard already covers native methods; kept explicit as documentation.)
-keepclasseswithmembernames class se.lublin.humla.audio.NativeCrashTest {
    native <methods>;
}
# Step 5a.3b unified voice engine: the JNI entry points embed this class name
# (Java_se_lublin_humla_voice_NativeVoiceEngine_*), and nativeCreate looks up
# onUdpPing/onCryptResync/onUdpUnavailable/onTalkSnapshot/onTunnelPacket/
# onStreamDisconnect on the listener at runtime. Without these, release
# GetMethodID throws NoSuchMethodError
# inside nativeCreate (an Error the Kotlin catch (Exception) does not catch),
# SIGABRTing the transport reader on the first CryptSetup/UDP ping.
-keep class se.lublin.humla.voice.NativeVoiceEngine { *; }
-keep class se.lublin.humla.voice.NativeVoiceEngine$* { *; }
-keepclassmembers class * implements se.lublin.humla.voice.NativeVoiceEngine$Listener {
    public void onUdpPing(long, long, int);
    public void onCryptResync();
    public void onUdpUnavailable();
    public void onTalkSnapshot(int[], int[], long, long, long, boolean);
    public void onTunnelPacket(byte[]);
    public void onStreamDisconnect(long);
}
# Keep line numbers for release crash traces (negligible size cost).
-keepattributes SourceFile,LineNumberTable

# Preserve Protobuf Lite generated message classes
-keep class se.lublin.humla.protobuf.Mumble** { *; }
-keep class com.google.protobuf.GeneratedMessageLite { *; }

# MiniDNS
-keep class org.minidns.** { *; }

# jsoup (used by MumlaService, NotificationSanitizer)
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
