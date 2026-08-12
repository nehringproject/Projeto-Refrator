# Binder contracts are instantiated across isolated app processes.
-keep interface dev.agentworkbench.I* { *; }
-keep class dev.agentworkbench.I*$Stub { *; }
-keep class dev.agentworkbench.I*$Stub$Proxy { *; }

# Android and Room construct these classes outside direct call sites.
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Database class * { *; }
-keepclasseswithmembers class * {
    @androidx.room.* <fields>;
}

# Chaquopy resolves its Java bridge and Python entry points dynamically.
-keep class com.chaquo.python.** { *; }
-keep class dev.agentworkbench.PythonRuntimeService { *; }
-keep class dev.agentworkbench.LiteLlmRuntimeService { *; }

# Shizuku binds provider and user-service entry points by class name.
-keep class rikka.shizuku.** { *; }

# JGit probes optional JVM JMX monitoring APIs which Android does not provide.
# Git functionality does not depend on this monitoring path.
-dontwarn java.lang.management.ManagementFactory
-dontwarn javax.management.**
-keep class dev.agentworkbench.ShizukuUserService { *; }
