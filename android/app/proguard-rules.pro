# Tink references optional annotation classes that aren't on the classpath.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# Nothing Glyph SDK: talks to the system Glyph service over AIDL.
-keep class com.nothing.** { *; }
-dontwarn com.nothing.**
-dontwarn com.nothinglondon.**
