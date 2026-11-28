# Core OCR logic is plain Kotlin; keep class names readable in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp optional platform integrations
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization (only the JsonElement API is used, no generated serializers)
-dontwarn kotlinx.serialization.**
