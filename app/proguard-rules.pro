# Stellar AutoHub. Keep all app classes as-is (small, keeps crash logs readable); shrink libraries.
-keep class com.stellar.autohub.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-dontnote kotlinx.serialization.**

# BouncyCastle: only used directly (no provider registration), so silence optional deps
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-keep class org.bouncycastle.asn1.x500.style.** { *; }

# OkHttp optional platforms
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# SnakeYAML (only the safe node composer is used)
-dontwarn java.beans.**
-keep class org.yaml.snakeyaml.** { *; }
