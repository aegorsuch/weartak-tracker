# Keep defaults; the app does not use reflection-based serialization.

# Samsung Health Sensor SDK binds to Samsung Health Platform via AIDL/parcelables.
-keep class com.samsung.android.service.health.tracking.** { *; }
-dontwarn com.samsung.android.service.health.tracking.**
