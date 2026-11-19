# Keep defaults; the app does not use reflection-based serialization.

# Samsung Health Sensor SDK binds to Samsung Health Platform via AIDL/parcelables.
-keep class com.samsung.android.service.health.tracking.** { *; }
-dontwarn com.samsung.android.service.health.tracking.**

# EdDSA provider resolves its JCA engine classes by name.
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn sun.security.x509.X509Key
