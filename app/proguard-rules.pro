# Keep the TensorFlow Lite runtime classes (JNI backed, reflection is used by
# the delegates). Shrinking is disabled by default in this sample, but these
# rules keep release builds working if you turn minification on.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# TFLite's NNAPI delegate loads its native library through JNI.
-keep class org.tensorflow.lite.nnapi.** { *; }
