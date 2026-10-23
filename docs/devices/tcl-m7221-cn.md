# TCL m7221（国行，75V2 / sugarcane）

> 和 [tcl-mt5879-cn.md](tcl-mt5879-cn.md)（Android 11 / MT5879）、[tcl-mt9655-cn.md](tcl-mt9655-cn.md)（Android 11+ / MT9655）是**三个不同平台**，拦安装的代码根本不在同一处，不要照着另一篇的方法试。

- 零售型号：TCL **75V2**；`ro.product.device` / `ro.product.model` = `sugarcane`；固件 `V8-S48CT05-LF1V142`（MS848C 机芯）。
- 平台 MStar **m7221**（`ro.board.platform` / `ro.hardware`），1920×1080，240dpi；Android **8.0.0**（SDK 26），`OPR5.170623.014 dev-keys`。
- 原桌面：`com.tcl.cyberui/.MainActivity`。
- 安装的 flavor：**`standard`**。这台不需要 guard 守护——桌面由固件的 `persist.sys.launcher.component` 直接拉起（见下），也就用不到无障碍服务、通知监听，不受 TCL 自启动管理限制。
- 全程只用 `adb shell` 权限，**没用 root**。测试日期 2026-10-22 / 23。

## 开 ADB：固件开机不会把 adbd 拉起来

这台出厂就带 `persist.service.adb.enable=1`、`persist.adb.tcp.port=5555`，但**重启后 5555 连不上**。
原因不是固件把 adb 关了，而是 adbd 由 init 的**属性触发器**启动，而**触发器只在属性值发生变化时点火**：

```bash
$ adb shell getprop | grep -i adb
[persist.service.adb.enable]        [1]      # 真开关（OEM 属性）
[persist.sys.service.adbd.enable]   [1]
[persist.adb.tcp.port]              [5555]
[service.adb.tcp.port]              [5555]   # 非 persist，开机必丢
[persist.sys.usb.config]            [adb]
[init.svc.adbd]                     [running]
[ro.adb.secure]                     [1]
```

开机时 init 加载 `persist.*`，值从 1 到 1、**没有变化 → 触发器不触发 → adbd 起不来**。
这与「持久化 = 开机自动生效」的直觉正好相反。

判定是否真的活着，**只看 `init.svc.adbd`**：

```bash
adb shell getprop init.svc.adbd            # running 才算活着
```

⚠ `Settings.Global.ADB_ENABLED` 是诱饵：写 0 或 1 都启停不了 adbd，「开发者选项里开 USB 调试」在这台没用。

### 通用解法：把属性 toggle 0→1

制造一次变化让 init 点火，任何「触发器只认变化」的固件都适用，只需换成本机的真开关属性名：

```bash
adb shell setprop persist.service.adb.enable 0
sleep 1
adb shell setprop persist.service.adb.enable 1
adb shell getprop init.svc.adbd            # running 即成功
```

本机是用自建 App `app.adbkeep`（无障碍服务）在开机后 5/15/45/120/300 秒各试一次，
外加 10 分钟 watchdog 兜底。它做的不外乎上面这三行。

旁证：上游 `tools/install.sh` 的 `KEEP_ADB=1` 就是为同类现象准备的。

其他提醒：
- `ro.adb.secure=1`，adbd 走 RSA 认证，已授权公钥记在 `/data/misc/adb/adb_keys`，换电脑连要在电视上确认一次，重启不失效。
- `adbd` 的 `PPid=1`（`cat /proc/<pid>/status`），由 init 直接拉起，**与任何 App 都无关**，所以这类电视不需要装「ADB 保活」类工具去「守进程」，只需要制造属性变化。

## 安装限制：PMS 的 compareSignatures，不是策略链

MT5879 那台是 `VerifierReciver` + `InstallStrategyManager`，`PmStrategy` 一律拒绝 adb/pm 安装
（`INSTALL_FAILED_VERIFICATION_FAILURE`）。

**这台 Android 8 没有那条策略链**，拦人的是 `PackageManagerService` 自己：

```
INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES:
disable install launcher app not signature by tcl
```

从 `/system/framework/oat/arm64/services.vdex` 反出来的逻辑
（注意：`/system/framework/services.jar` 只有 315 字节，是空壳，真身在 vdex）：

```java
// PackageManagerService.<clinit>，只在 system_server 启动时读一次
matrixInstallerEnalbe = SystemProperties.get("persist.sys.matrix.mi.enable", "0");

// compareSignatures()
if ("1".equals(matrixInstallerEnalbe) && launcherApp) return SIGNATURE_MATCH;  // 直接放行

// isTclLauncher(pkg)：签名与平台一致 && meta-data com.tcl.app.type == com.tcl.ui
```

所以 MT5879 文档里的 `persist.tcl.installapk.enable=1`、`package_verifier_enable=0` **在这台无效**。

### 免 root 安装

```bash
adb connect <tv-ip>:5555
adb shell setprop persist.sys.matrix.mi.enable 1   # 开签名放行
adb shell setprop ctl.restart zygote               # 软重启让 system_server 重读那个 static 字段
adb install aerial-standard.apk                    # Success
```

`matrixInstallerEnalbe` 是 `static` 字段、`<clinit>` 只在 system_server 启动时跑，**改完必须让 framework 重启**。
`ctl.*` 在这台 shell 可写，软重启约 4 秒、不熄屏，不必真重启：

| 实验 | 结果 |
|---|---|
| 置 0 + 软重启后安装 | 拒绝：`disable install launcher app not signature by tcl` |
| 置 1 + 软重启后安装 | `Success` |

⚠ **覆盖安装不触发这条校验**。装过官方版之后 `adb install -r` 升级直接 Success，连软重启都不用。
前提是**同签名**；换自编译签名版时首次安装仍需 `mi.enable=1`。

## 开机直达：`persist.sys.launcher.component`

TCL 在 AMS 里改了 `setDefaultLauncher()`，开机时读这个属性直接拉起指定组件：

```bash
adb shell cmd package set-home-activity app.orchard.tv/.MainActivity
adb shell setprop persist.sys.launcher.component app.orchard.tv/app.orchard.tv.MainActivity
```

两点要注意：

- 格式必须是**完整类名** `包名/类全名`。写成 `app.orchard.tv/.MainActivity`（相对类名）会黑屏。
- **它不校验组件有没有声明 HOME**，给任何 Activity 都能用。

实测（真重启，从 adb 恢复起每 0.5 秒采样 48 次）：

```
T+0s   发出 reboot
T+27s  adb 恢复，前台已是 app.orchard.tv/.MainActivity
T+27s ~ T+57s  48 次采样全部为 app.orchard.tv/.MainActivity
```

`mResumedActivity` 全程没出现过 `com.tcl.cyberui`；连按 3 次 Home 也停在同一 task。
比 guard 守护干净：不需要无障碍、不需要通知监听、不受 `APP_AUTO_START` / `APP_ASSOC_START` 限制。

建议其他 TCL 机型先探一下有没有默认值：`getprop persist.sys.launcher.component`。

## 已知问题

### 1. 冷启动时 MainActivity 崩 2 次（CE 存储未解锁）

开机 logo 消失后黑屏约 2 秒才出画面。system_server 把 MainActivity 创建了 3 次，前 2 次崩：

```
13:00:17.003  am_proc_start: [0,3166,10009,app.orchard.tv,activity,app.orchard.tv/.MainActivity]
13:00:17.388  am_crash: java.lang.IllegalStateException,
              SharedPreferences in credential encrypted storage are not available
              until after user is unlocked, ContextImpl.java,389
13:00:17.536  am_proc_start: [0,3275,...]     ← 系统重试
13:00:17.989  am_crash: [3275,...]            ← 又崩
13:00:18.195  am_proc_start: [0,3345,...]     ← 第三次成功
```

根因：TCL 的 `setDefaultLauncher()` 在 user 0 还是 `RUNNING_LOCKED` 时就把 launcher 拉起来了，
比标准 Android TV 早；而 `MainActivity.onCreate` 的初始化链一进来就读 CE 存储的 SharedPreferences。

⚠ 复现前提（不满足就复现不出来，排查时容易误判为「已修复」）：
必须**开机桌面由固件直接拉起** + **冷启动**。`setprop ctl.restart zygote` 软重启不重走解锁流程，**复现不出来**。
与 flavor 无关，1.31 / 1.32 都必现。

尝试过的缓解（**都没能归零**，记录在此避免重复踩）：
- 用 `UserManager.isUserUnlocked` 判断：不可靠，user 状态与 CE 挂载不同步，崩溃点挪到了 `HomeViewModel`。
- 直接 try/catch 探测 CE 存储可用性：崩溃 2 次 → 1 次。

根治要走 `createDeviceProtectedStorageContext()` + `android:directBootAware="true"`，把开机路径的配置迁到 DP 存储。

### 2. 冷启动时 TCL 桌面抢前台约 1.2 秒，随后自己崩

```
08:42:24.8  am_create_activity: com.tcl.cyberui/.MainActivity   ← 前台被抢
08:42:25.9  am_crash: com.tcl.cyberui  SecurityException:
            Package app.orchard.tv does not belong to 1000
            at FKPageConfigManager.setIs4KEnv
08:42:25.9  am_finish_activity: app.orchard.tv/.MainActivity, force-crash   ← Aerial 被连带结束
08:42:26.0  Aerial 重建，恢复前台
```

即 TCL 桌面自己站不住（以 uid 1000 读「当前桌面包」时撞 uid 校验），但启动瞬间会顶掉 Aerial。
成因是我们把默认桌面改成了非 TCL 包；属固件侧行为，Aerial 无过错。

### 3. 「下载并安装」自动升级不可用

`REQUEST_INSTALL_PACKAGES` 已是 `allow`，但点下去 `com.android.packageinstaller/InstallStart`
启动即崩：

```
09:45:33  am_crash: com.android.packageinstaller  SecurityException:
          Package app.orchard.tv does not belong to 10011
          at Settings.Global.getStringForUser
```

与上面第 2 条同一类：调用方把自身 uid 与 `app.orchard.tv` 一起当 opPackageName 送进 Settings 的 binder 调用，
被服务端按「包必须属于调用 uid」拒。**升级只能 `adb install -r`。**

### 4. 信源面板固定列出 4 个 HDMI（实际 2 口）

固件侧正确（`com.tcl.tv` 只列 TV / HDMI1 / HDMI2 / AV，`source_support` 也只有 2 口），
多出的入口来自 Aerial 的 TCL 硬编码分支（`HDMI1..4` + DTV + AV1），因为这台没有 TvInputService
（`dumpsys tv_input` 为空）走了厂商兜底。已单独提 issue #279。

## 两个坑

1. **别卸载 `com.tcl.common.weather`。**
   桌面和控制中心的「设置」都依赖 `com.tcl.weather.provider`，卸掉后 `SettingsMainDialog`
   初始化抛 `SecurityException`，设置面板再也打不开。HDMI 界面走另一条入口、不受影响，容易误判。
   恢复：`cmd package install-existing com.tcl.common.weather`（APK 在 `/vendor/app/TCL_ALL_WeatherForecast/`，免 root）。
2. **TCL 有两个自己的无障碍服务**（`com.tcl.walleve`，「小T助手」），改 `enabled_accessibility_services` 时要保留。

⚠ **别去 `pm disable` / `pm enable` TCL 的系统应用。** 实测误操作 `pm enable com.tcl.walleve`
之后 system_server 变僵尸、zygote64 进 D 状态，整机崩，只能 `setprop sys.powerctl reboot` 救回。

## 权限边界（全程未用 root）

| 能做 | 不能做 |
|---|---|
| `setprop persist.*` | `pm install`（被 compareSignatures 拦） |
| `adb install`（streamed） | `pm disable` / `pm enable`（SecurityException） |
| `setprop ctl.restart zygote` | 动 TCL 的 system uid 应用 |
| `cmd package set-home-activity` | |

## 命令速查

```bash
adb connect <tv-ip>:5555

# 首次部署
adb shell setprop persist.sys.matrix.mi.enable 1
adb shell setprop ctl.restart zygote                    # 软重启让开关生效
adb install aerial-standard.apk
adb shell cmd package set-home-activity app.orchard.tv/.MainActivity
adb shell setprop persist.sys.launcher.component app.orchard.tv/app.orchard.tv.MainActivity

# 还原
adb shell setprop persist.sys.launcher.component ""
adb shell cmd package set-home-activity com.tcl.cyberui/.MainActivity

# 误卸天气导致设置打不开时
adb shell cmd package install-existing com.tcl.common.weather
```

## 找同类属性的方法（换机型时照做，别直接套结论）

1. `getprop | grep -iE "launcher|home|boot|adb|install|verif|sign|matrix"` —— 零成本枚举。
2. **从 `services.vdex` 里捞 system_server 用到的全部 persist 属性名**（免 root，最能说明问题）：
   ```bash
   adb shell grep -a -ohE "persist\.[a-zA-Z0-9._]+" /system/framework/oat/*/services.vdex | sort -u
   ```
3. 验证固件认不认：`getprop` → `setprop` → `ctl.restart zygote` → 看前台/看安装是否放行。
4. 反编译只作最后手段：pull vdex → 偏移 28 取 dex → 重算 SHA1(32:) 与 Adler32(12:) → jadx/androguard。
5. 读不到 `.rc` 时用**行为实验**：toggle 属性看 `init.svc.<svc>` 是否变化——adbd 的「变化才触发」就是这么测出来的。

一个屡试不爽的技巧：**先让它失败一次，拿报错字符串当 grep 关键词**
（例如 `disable install launcher app not signature by tcl`）。
