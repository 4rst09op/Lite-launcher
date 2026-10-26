# Lite Launcher (Google TV)
Build: open this folder in Android Studio -> Build > Build APK(s) -> app-debug.apk
Install:   adb connect <TV_IP>:5555 && adb install app-debug.apk
Make it Home (reversible):
  adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
  adb shell pm disable-user --user 0 com.google.android.tvlauncher
Undo:
  adb shell pm enable com.google.android.apps.tv.launcherx
  adb shell pm enable com.google.android.tvlauncher
