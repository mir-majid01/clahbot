# Gson reads and writes these classes by field name.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.clahbot.data.models.** { *; }
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
