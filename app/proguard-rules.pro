# Keep kotlinx.serialization generated serializers.
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
