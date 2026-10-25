# karoo-ext: modele przesyłane jako JSON przez AIDL (kotlinx.serialization) i interfejsy AIDL.
-keep class io.hammerhead.karooext.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }

# Czytelne stack trace w lokalnym logu
-keepattributes SourceFile,LineNumberTable
