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

# Preserve BouncyCastle Security Provider and active Mumble algorithms (X.509, RSA, EC, PKCS#12)
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.ec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.x509.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.** { *; }
-keep class org.bouncycastle.jcajce.provider.keystore.pkcs12.** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.AES** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.DESede** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.PBE** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.util.** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.MD5** { *; }
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
