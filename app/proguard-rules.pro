# Keep generated protobuf classes (lite runtime relies on reflection on a few methods).
-keep class com.google.protobuf.** { *; }
-keep class com.quickshare.tv.proto.** { *; }

# Coroutines / kotlin metadata
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn kotlinx.coroutines.debug.**
