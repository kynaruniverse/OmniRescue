# TensorFlow Lite — prevent obfuscation of all native-bound classes
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Keep all app classes (avoids stripping callbacks and model classes)
-keep class com.omni.rescue.** { *; }