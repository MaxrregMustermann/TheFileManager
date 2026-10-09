# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Native methods
# https://www.guardsquare.com/en/products/proguard/manual/examples#native
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The archive support calls these by name from Rust, so R8 must not rename or remove them. The
# methods themselves are covered by the rule above, but the class has to survive too, and the ones
# it takes and returns are part of that.
-keep class me.zhanghai.android.files.provider.archive.archiver.RustArchive {
    *;
}
-keep class * extends java8.nio.channels.SeekableByteChannel {
    # Reached from Rust by name, with their exact signatures. Renaming any of these breaks the
    # archive support at runtime rather than at build time, which is why each is written out.
    read(Ljava/nio/ByteBuffer;)I;
    write(Ljava/nio/ByteBuffer;)I;
    position(J)J;
    size()J;
}

# App
-keep class me.zhanghai.android.files.** implements androidx.appcompat.view.CollapsibleActionView { *; }
-keep class me.zhanghai.android.files.provider.common.ByteString { *; }
-keep class me.zhanghai.android.files.provider.linux.syscall.** { *; }
-keepnames class * extends java.lang.Exception
# For Class.getEnumConstants()
-keepclassmembers enum * {
    public static **[] values();
}
-keepnames class me.zhanghai.android.files.** implements android.os.Parcelable

# Apache FtpServer
-keepclassmembers class * implements org.apache.mina.core.service.IoProcessor {
    public <init>(java.util.concurrent.ExecutorService);
    public <init>(java.util.concurrent.Executor);
    public <init>();
}

# Bouncy Castle
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# SMBJ
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.x509.X509Key

# SMBJ-RPC
-dontwarn java.rmi.UnmarshalException
