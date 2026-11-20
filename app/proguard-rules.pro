# HarborTV ProGuard / R8 Rules

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve core canonical models and error taxonomy
-keep class com.harbor.tv.core.model.** { *; }
-keep class com.harbor.tv.engine.definition.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.harbor.tv.**$$serializer { *; }
-keepclassmembers class com.harbor.tv.** {
    *** Companion;
}
-keepclasseswithmembers class com.harbor.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# jsoup HTML parser
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Media3 ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Room database and generated DAOs / Impl classes
-keep class com.harbor.tv.core.storage.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Jetpack DataStore
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# Coil image loader
-keep class coil.** { *; }
-dontwarn coil.**
