# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class org.amrit.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class org.amrit.**$$serializer { *; }
-keepclassmembers class org.amrit.** { *** Companion; }
-dontwarn org.slf4j.**
