# Keep kotlinx.serialization generated serializers for the P2P wire DTOs so the
# JSON field names stay byte-exact under R8 (LocalSend wire-compat oracle).
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.difft.android.p2p.engine.model.**$$serializer { *; }
-keepclassmembers class com.difft.android.p2p.engine.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.difft.android.p2p.engine.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
