# Keep Firebase model reflection paths used by Realtime Database / Firestore.
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class com.manacinema.app.models.** { *; }
-dontwarn com.google.firebase.**
