# Our own code is small: keep it whole so nothing referenced by name (manifest
# components, enums parsed from settings, JSON keys) can be renamed or removed.
-keep class io.github.anwo91.phevlink.** { *; }
-keepclassmembers enum * { *; }
-dontwarn org.slf4j.**
