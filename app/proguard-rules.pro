# Vyze ProGuard / R8 Rules
# ─────────────────────────────────────────────────────────────────────────────

# ── Sherpa-ONNX JNI Bindings ────────────────────────────────────────────────
# (Keep rules REMOVED — TTS pivoted to platform android.speech.tts.TextToSpeech.
# No com.k2fsa.sherpa.onnx classes remain in the APK.)

# ── LiteRT-LM (Gemma on-device VLM runtime) ─────────────────────────────────
# JNI-backed: liblitertlm_jni.so resolves Java methods/fields BY NAME via
# GetMethodID/GetFieldID. R8 renaming these classes broke every native lookup
# and aborted the runtime at first inference:
#   "JNI DETECTED ERROR IN APPLICATION: mid == null in call to CallIntMethodV
#    from LiteRtLmJni.nativeCreateConversation(...)"
# Debug builds were unaffected (no minification) — release only. Same class of
# bug the ML Kit / MediaPipe / TFLite rules below already guard against.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# ── ML Kit Text Recognition ──────────────────────────────────────────────────
# No app keep (P3 #95): every ML Kit AAR ships its own consumer
# proguard.txt, which R8 applies automatically — the old whole-tree
# keep only pinned ~2000 classes unrenamed. Proven by
# :app:minifyReleaseWithR8 + the mapping diff that removed it.
-dontwarn com.google.mlkit.**

# ── TensorFlow Lite ──────────────────────────────────────────────────────────
# STAYS whole-tree: JNI-backed like LiteRT above — the documented
# GetMethodID-by-name failure class. Same rationale, same caution.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ── CameraX ──────────────────────────────────────────────────────────────────
# No app keep (P3 #95): camera-core ships its own consumer proguard.txt
# (same proof as ML Kit above — ~11000 classes released to R8).
-dontwarn androidx.camera.**

# ── App entities + ADK tools: verified keep-free (P3 #95) ────────────────────
# Room ships its own consumer rules and works through generated code
# (no reflective entity access anywhere — export is manual JSON); the
# ADK lane passes tools explicitly, feeds Map<String, Any> args, and
# stores string-only session state, so neither needs app keeps. If a
# release-only NoSuchMethodError ever points here, add the narrowest
# rule that names it — never a whole tree.

# ── Google Play Services (used by ML Kit) ────────────────────────────────────
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ── Preserve line numbers for crash reports ───────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── General Android ──────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod

# ── Kotlin coroutines ────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Data binding / View binding ──────────────────────────────────────────────
-keep class com.vyze.app.databinding.** { *; }

# ── Annotation processing (AutoValue / JavaPoet) ────────────────────────────
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8