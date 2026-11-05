# Velmorath

Android client for a university campus portal. Kotlin + native Android.

## 当前功能

- WebView 登录门户和本科教务
- 复用 WebView 的 Cookie 调用接口：
  - 门户聚合通知
  - 作业与课程提醒
  - 当前周课表
  - 教务系统考试安排
- 所有请求串行、低频，带 Referer / UA / X-Requested-With

## 构建

需要 JDK 17+、Android SDK 35、Gradle 8.9：

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$HOME/.local/share/gradle/gradle-8.9/bin:$PATH
cd ~/Projects/zhlj-android
gradle :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/Velmorath-debug.apk`

## 隐私

- Cookie 只保存在系统 WebView 的 CookieManager 中
- `android:allowBackup="false"`，不进入系统备份
- 不写入日志、不上传
- `local.properties`（本机 SDK 路径）已在 `.gitignore`

## 待办

- WorkManager 定时刷新（最短约 15 分钟）与通知
- 成绩查询（教务系统可能要求验证码）
- SQLite/SharedPreferences 去重，只提示新条目
- 启动图标、发布签名
