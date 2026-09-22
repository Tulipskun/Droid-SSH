# Droid-SSH keeps defaults; MINA + slf4j don't need extra rules for debug.
-keep class org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**
-dontwarn org.slf4j.**
