# ProGuard / R8 Rules for Nourio Release Build

# Keep Compose & Material3 UI classes
-keep class androidx.compose.** { *; }
-keep class androidx.compose.material3.** { *; }

# Keep Room DAOs and Database entities
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# Keep OkHttp & Gson/JSON serialization classes
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Keep Health Connect Client classes
-keep class androidx.health.connect.client.** { *; }
-dontwarn androidx.health.connect.client.**

# Keep Room generated code and models
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}
