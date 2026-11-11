-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.difft.android.p2p.engine.model.**$$serializer { *; }
-keepclassmembers class com.difft.android.p2p.engine.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.difft.android.p2p.engine.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
