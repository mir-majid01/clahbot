# Gson reads and writes these classes by field name.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.clahbot.data.models.** { *; }
# Wall and recovery models are Gson schemas. A release build renamed them once:
# walls.json was written as {"a":{"a":true,...}}, so every reload read defaults
# and Auto Walls turned itself off. Keep the field names of both packages.
-keep class com.clahbot.walls.** { *; }
-keep class com.clahbot.recovery.** { *; }
# Image triggers are a Gson schema too. Without this keep, R8 renamed the
# ImageTrigger fields, Gson could not rebuild the List<ImageTrigger> (it fell
# back to LinkedTreeMap), and the dashboard crashed casting it back on scroll.
-keep class com.clahbot.triggers.** { *; }
-keep class com.clahbot.data.AttackRepository$AttackFile { *; }
-keep class com.clahbot.data.MacroRepository$MacroFile { *; }
-keep class com.clahbot.data.SessionRepository$SessionFile { *; }
-keep class com.clahbot.update.** { *; }

# Shizuku starts this class by name inside the shell user-service process.
-keep @androidx.annotation.Keep class * { *; }
-keep class com.clahbot.input.ShizukuInputService { *; }
-keep class com.clahbot.input.IInputEventService { *; }
-keep class com.clahbot.input.IInputEventService$Stub { *; }
-keep class com.clahbot.input.IInputEventCallback { *; }
-keep class com.clahbot.input.IInputEventCallback$Stub { *; }

# Reader implementations load native libraries and models reflectively.
-keep class com.clahbot.ocr.** { *; }
-keep class com.googlecode.tesseract.android.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class com.google.mlkit.** { *; }
