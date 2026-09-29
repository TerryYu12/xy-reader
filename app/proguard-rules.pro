# 保守混淆规则：应用代码与压缩包解析库整体保留，避免反射/服务加载被裁。
# Compose、Room、OkHttp、Coil 均自带 consumer rules，无需额外配置。

-keep class com.xyreader.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class com.github.junrar.** { *; }

-dontwarn org.apache.commons.compress.**
-dontwarn com.github.junrar.**
-dontwarn org.slf4j.**
