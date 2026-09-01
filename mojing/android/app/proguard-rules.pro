# ProGuard rules for mojing

# ── Kotlin Serialization ──
-keepattributes *Annotation*, Signature, Exception, InnerClasses, EnclosingMethod
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-keep,includedescriptorclasses class com.mojing.app.**$$serializer { *; }
-keepclassmembers class com.mojing.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.mojing.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Room ──
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**
-keep class androidx.sqlite.** { *; }

# ── Hilt / Dagger ──
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-dontwarn dagger.**

# ── Gson ──
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.mojing.app.data.local.entity.** { *; }
-keep class com.mojing.app.domain.model.** { *; }
-keep class com.mojing.app.data.remote.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── OkHttp / OkHttp SSE ──
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keepclassmembers class okhttp3.** { *; }
-keep class okhttp3.internal.** { *; }

# ── Coil ──
-dontwarn coil.**
-keep class coil.** { *; }

# ── Compose ──
-keep class androidx.compose.** { *; }

# ── Media3 / ExoPlayer ──
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# ── CameraX ──
-dontwarn androidx.camera.**
-keep class androidx.camera.** { *; }

# ── Coroutines ──
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Keep app classes ──
-keep class com.mojing.app.** { *; }
-keepclassmembers class com.mojing.app.** { *; }

# ── Keep R classes ──
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ── General ──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-repackageclasses
