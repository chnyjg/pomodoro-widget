# 番茄钟桌面小部件（自用）

30 分钟工作 / 5 分钟休息，循环；主屏小部件**常驻显示倒计时**，点「暂停/继续」即可，**无需打开 App**。
纯本地、无网络、无账号，仅自己用。

## 工程结构
- `PomodoroWidget.kt` — 主屏小部件（外观 + 点按发广播）
- `TimerService.kt` — 前台 Service，真正每秒走表、更新小部件和通知
- `MainActivity.kt` — 打开 App 即启动计时；内含「重置为 30 分钟」按钮
- `BootReceiver.kt` — 开机自动恢复计时
- 状态存 `SharedPreferences`，服务被杀后重启会补偿流逝时间

## 编译 / 安装（mac 上用 Android Studio）
1. 安装 [Android Studio](https://developer.android.com/studio)，首次启动会装好 SDK。
2. `File → Open` 选择本目录（含 `settings.gradle` 的那层），等待 `Sync Project with Gradle Files` 完成。
   - 若提示 Gradle 版本，点「使用 Android Studio 自带的 Gradle」即可。
3. 手机：设置 → 我的设备 → 全部参数 → 连续点「MIUI/HyperOS 版本」打开**开发者选项** → 开启 **USB 调试**。
4. 数据线连电脑，Android Studio 顶部选你的手机，点 ▶ Run（或 `Build → Build Bundle(s)/APK(s) → Build APK` 后把 apk 拷到手机安装）。
5. 首次打开 App 会请求「通知」权限，允许（前台服务需要）。

## 添加小部件到主屏
长按主屏空白处 → 添加小部件 → 找到「番茄钟」→ 拖到主屏。
小部件上的「⏸ 暂停 / ▶ 继续」直接点即可，不打开 App。

## ⚠️ 小米 / HyperOS 必做：关闭电池优化（否则锁屏就停）
小米的「神隐模式」会杀后台 Service，导致计时器停止。必须：
1. 设置 → 应用设置 → 应用管理 → 番茄钟 → 省电策略 → 选 **「无限制」**。
2. 设置 → 应用设置 → 授权管理 → 自启动管理 → 番茄钟 → 允许**自启动**。
3. 设置 → 省电与电池 → 右上角齿轮 → 神隐模式 → 把番茄钟设为**「无限制」/关闭**。
（不同 MIUI/HyperOS 版本叫法略有差异，核心是「该应用不被省电限制、允许后台」。）

## 自定义
- 改时长：编辑 `TimerService.kt` 里的 `WORK_SECONDS = 30 * 60` 和 `BREAK_SECONDS = 5 * 60`。
- 改文案/配色：编辑 `res/layout/widget_pomodoro.xml` 和 `res/drawable/*`。

## 说明
本工程未在真机编译验证，请按上面步骤在 Android Studio 中打开；如遇 Gradle / 版本报错，
优先让 Android Studio 自动修复（弹窗里的 "Update" / "Use recommended"），或把报错发我。
