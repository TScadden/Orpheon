# Rhino (the extensions' JavaScript engine, run on the watch too): reflection of its engine.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn java.lang.instrument.**
-keepclassmembers @kotlinx.serialization.Serializable class ** { *; }
-dontwarn jdk.dynalink.**
# Scadify v18 (the shipped watch build is a minified release now): the same generic
# keeps the phone's release carries — enums and Parcelables by shape, the Play
# services classes the Data Layer link is built on, and the attributes the
# serialization and stack traces read. The script bridge itself is a name table
# built in code (ExtensionRuntime's host object), so it needs no name keeps.
-keepattributes *Annotation*,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepclassmembers enum * { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keep class com.google.android.gms.** { *; }
# Scadify v19: ExtensionRuntime's static initializer loads the script prelude by a
# package-relative resource name — ExtensionRuntime::class.java.getResource("prelude.js")
# resolves against the class's runtime package. R8 renamed the class into the default
# package in v18 (it was "an0" in the shipped mapping), the lookup found nothing, the
# initializer threw, and every extension failed to load on the watch ("extension … did
# not load: an0"). Keep the class's name so the lookup resolves as written; the class
# is the only one in the shipped watch build with this pattern (the catalog module's
# two like it do not ship on the watch).
-keepnames class app.myspoti.extensions.ExtensionRuntime
