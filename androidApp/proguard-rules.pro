# kotlinx.serialization: keep generated serializers for protocol and persistence classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.transferx.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.transferx.**$$serializer { *; }

# BouncyCastle is used only to build the self-signed device certificate.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
