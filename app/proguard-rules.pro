# 保守混淆规则：应用代码与压缩包解析库整体保留，避免反射/服务加载被裁。
# Compose、Room、OkHttp、Coil 均自带 consumer rules，无需额外配置。

-keep class com.xyreader.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class com.github.junrar.** { *; }

-dontwarn org.apache.commons.compress.**
-dontwarn com.github.junrar.**
-dontwarn org.slf4j.**

# Rhino JS 沙箱（书源 @js: 规则）：Android 运行时没有 java.beans.*，
# 仅被 Rhino 中未走到的 JavaToJSONConverters.lambda$static$4 引用（Bean 内省分支），
# 保留 java.beans.* 只会引入 Android 上不存在的类，故静默告警而非保留。
-dontwarn java.beans.**
# Rhino jar 里自带的桌面调试器/命令行工具（org.mozilla.javascript.tools.debugger.*、
# tools.shell.*）依赖 AWT/Swing 做 GUI，Android 上根本不加载这些类，
# 其缺失的 java.awt.*/javax.swing.* 引用无需保留，静默告警即可。
# 依据：R8 missing_rules.txt 中全部缺失类均落在 java.awt.* / javax.swing.*（见验证档）。
-dontwarn java.awt.**
-dontwarn javax.swing.**
# Rhino 类经脚本引擎被反射式加载（包名/类名在运行时按字符串拼出来），
# 必须整体 keep，不能被 R8 裁掉或重命名。
-keep class org.mozilla.javascript.** { *; }

# 可选外部源：SPI 实现通过 META-INF/services 注册，R8 不能裁掉
-keep interface com.xyreader.booksource.ExternalSourceProvider { *; }
-keep class * implements com.xyreader.booksource.ExternalSourceProvider { *; }
-keep class * implements com.xyreader.booksource.ExternalSourceSession { *; }
