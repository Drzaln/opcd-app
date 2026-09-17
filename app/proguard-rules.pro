# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.opencode.mobile.data.model.**$$serializer { *; }
-keepclassmembers class dev.opencode.mobile.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class dev.opencode.mobile.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Prism4j loads generated grammar classes reflectively — keep everything.
-keep class io.noties.prism4j.** { *; }
-keepclassmembers class io.noties.prism4j.** { *; }
-keep class * implements io.noties.prism4j.GrammarLocator { *; }
-keepnames class * implements io.noties.prism4j.GrammarLocator
-dontwarn org.jetbrains.annotations.**

# Retrofit / OkHttp / Okio
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# Kotlin / coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlin.Metadata { *; }

# Room ships its own consumer rules; nothing extra needed here.
