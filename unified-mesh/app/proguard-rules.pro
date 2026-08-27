# Protobuf lite keeps field metadata in generated classes; R8 must not strip it.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keep class org.meshtastic.proto.** { *; }
