# Shizuku instantiates ShellService by class name via reflection in the privileged process.
-keep class com.chargescope.app.ShellService { *; }
-keep class com.chargescope.app.IShellService { *; }
-keep class com.chargescope.app.IShellService$* { *; }
