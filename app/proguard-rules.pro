# kotlinx.serialization：保留序列化器生成的伴生实现
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.sparkhoward.nagomiani.** { kotlinx.serialization.KSerializer serializer(...); }
