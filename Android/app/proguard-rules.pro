# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class at.heuriger.kassa.**$$serializer { *; }
-keepclassmembers class at.heuriger.kassa.** {
    *** Companion;
}

# --- Ktor / OkHttp ---
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Logging entfernen (Release) ---
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
