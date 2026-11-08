# Default Android ProGuard rules apply via proguard-android-optimize.txt.

# Keep Compose runtime metadata.
-keepclassmembers class androidx.compose.** { *; }

# Telephony / SMS framework classes referenced by reflection in some OEM ROMs.
-keep class android.provider.Telephony** { *; }

# Keep all manifest-registered & SMS/MMS/Notification/Schedule components and models.
-keep class com.realme.samurai.messages.MainActivity { *; }
-keep class com.realme.samurai.messages.MessagesApplication { *; }
-keep class com.realme.samurai.messages.data.receiver.** { *; }
-keep class com.realme.samurai.messages.data.service.** { *; }
-keep class com.realme.samurai.messages.data.mms.** { *; }
-keep class com.realme.samurai.messages.data.schedule.** { *; }
-keep class com.realme.samurai.messages.notification.** { *; }
-keep class com.realme.samurai.messages.data.model.** { *; }
-keep class com.realme.samurai.messages.data.local.** { *; }

# Keep enum entries used in settings / persistence.
-keepclassmembers enum com.realme.samurai.messages.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Kotlin coroutines & OkHttp/Coil safe rules.
-keepnames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-dontwarn okhttp3.**
-dontwarn okio.**
