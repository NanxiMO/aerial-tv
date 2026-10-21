# 小米 MiTV-ASTP0（mulan）Android 9 背光入口

## 适用范围

本次适配严格限定于以下真机环境，其他机型、Android 版本及固件继续使用原有逻辑：

```text
model=MiTV-ASTP0
device=mulan
Android=9 / SDK=28
build=24.7.4.3982
fingerprint=Xiaomi/mulan/mulan:9/PPR1.180610.011/3982:user/release-keys
```

这台电视没有 `Settings.Global.picture_backlight`。写入标准 `screen_brightness` 不能控制面板背光。Aerial 1.18 的“亮度”按钮尝试 `android.settings.DISPLAY_SETTINGS` 和 `android.settings.SETTINGS`，但当前固件没有对应 Activity，按钮因而无法打开设置。

## 直接打开背光强度

当前系统设置 APK 的 `SettingsBroadcastReceiver` 接收 `com.xiaomi.mitv.settings.BACKLIGHT_POPUP` 后调用 `MitvSettingsPopup.showPopup(3)`，直接显示系统的“背光强度”调节条。接收器已导出，清单没有为它声明权限要求。Aerial 使用显式广播，不修改背光值，也不请求新增权限。

在**主机终端**使用普通 ADB shell 可以打开该面板，不需要 Root：

```sh
adb -s <电视序列号> shell am broadcast --user 0 \
  -a com.xiaomi.mitv.settings.BACKLIGHT_POPUP \
  -n com.xiaomi.mitv.settings/.SettingsBroadcastReceiver
```

注意这不是 `BACKLIGHT_MODE_POPUP`，后者打开动态背光模式。不要发送到整个系统，也不要用旧 `DisplaySoundActivity` 替代：虽然清单仍声明该 Activity，此固件实际启动会报 `ClassNotFoundException`。

## 验证范围

- 2026-10-21 已在上述固件验证普通 ADB 广播能从桌面直接打开“背光强度”面板，显示值为 50；没有调节该值。
- 2026-10-21 已构建本次修改的 standard Release APK，直接 `adb install -r` 覆盖已安装的 1.18（versionCode 20），返回 `Success`；没有卸载、清除数据或重启电视。安装后执行 `cmd package compile -m speed -f app.orchard.tv`，返回 `Success`。
- 从 Aerial 控制中心用方向键选中“亮度”并按确定，应用自身成功打开系统“背光强度”面板，读回与截图均显示 50。这一步未使用 ADB 广播代替按钮操作，也未调节背光值。返回及 HOME 仍正常，两个桌面守护服务已绑定。
- standard、guard 两个 Release APK 构建通过；5 个单元测试通过，覆盖设备型号、代号、Android SDK 和固件标识的正向匹配与不匹配边界。guard 未安装到电视。
- 本机构建使用已有 Gradle 9.5.1、AGP 8.12.0。完整 `lintStandardRelease` 报 5 个错误；未修改的上游源码在相同工具链下报告完全相同的 5 个错误（`RestrictedApi` 3 项、`MissingTranslation` 1 项、`IntentFilterExportedReceiver` 1 项），不在本次补丁中抑制或改写。
- 其他小米 Android 9 固件及其他 Android 版本不在本次验证范围内。
