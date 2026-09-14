# Guardian release (R8) rules.
#
# Only keep what reflection-based libraries genuinely need. Model classes carry
# Moshi's generated adapters, so the adapters (not the models) are what must
# survive shrinking.

# ---------------------------------------------------------------------------
# Kotlin / coroutines
# ---------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---------------------------------------------------------------------------
# Moshi (JSON): keep generated adapters and the annotated models they serve.
# ---------------------------------------------------------------------------
-keep class **JsonAdapter { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonQualifier interface *
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep class com.guardian.safety.remote.model.** { <init>(...); <fields>; }
-keep,allowobfuscation,allowshrinking @com.squareup.moshi.JsonClass class *
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# ---------------------------------------------------------------------------
# Retrofit / OkHttp
# ---------------------------------------------------------------------------
-dontwarn retrofit2.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes *Annotation*
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Room + SQLCipher
# ---------------------------------------------------------------------------
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# ---------------------------------------------------------------------------
# RevenueCat (subscriptions)
# ---------------------------------------------------------------------------
-keep class com.revenuecat.purchases.** { *; }
-dontwarn com.revenuecat.purchases.**

# ---------------------------------------------------------------------------
# Android components declared in the manifest
# ---------------------------------------------------------------------------
-keep class com.guardian.safety.GuardianApplication { *; }
-keep class com.guardian.safety.MainActivity { *; }
-keep class com.guardian.safety.worker.** { *; }

# Keep source information for readable production stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
