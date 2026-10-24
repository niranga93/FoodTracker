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

# ML Kit barcode scanning: its components are found by reflection at runtime (Firebase component
# registrars). Shrinking or merging them makes BarcodeScanning.getClient() crash with a null component.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class com.google.firebase.components.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
