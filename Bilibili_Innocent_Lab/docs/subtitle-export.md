# 字幕导出到剪切板

本分支新增了播放器字幕导出功能。它参考桌面脚本 `Bilibili B 站浏览助手-3.15.txt` 的字幕读取流程，但在 Android/Xposed 侧只保留“导出纯文本到剪切板”，不保存字幕文件。

## 使用方法

1. 在 LSPosed 中启用本模块，并重新启动哔哩哔哩。
2. 打开一个视频，等待播放器完成准备。
3. 播放器右上角会出现“导出字幕”按钮。
4. 点击按钮，模块会优先读取简体中文字幕；没有简体中文时依次回退到繁体中文或其他可用语言。
5. 成功后显示“字幕已复制到剪切板”，可直接粘贴到备忘录、聊天框等位置。

按钮只在播放器所在 Activity 的前台生命周期内存在，不需要悬浮窗权限。没有字幕、网络失败或字幕正文为空时只提示错误，不影响视频播放。

## 实现与接口

- `SubtitleExportFeatureInstaller.kt`：监听播放器准备回调、管理 Activity 生命周期、创建按钮和写入 Android `ClipboardManager`。
- `SubtitleExportClient.kt`：读取当前 BVID/CID，访问 `x/player/v2`，必要时回退 `x/player/wbi/v2` 和旧版字幕配置接口，再请求 `subtitle_url`。
- `SubtitleExportText.kt`：语言选择、字幕正文清理和地址规范化的纯逻辑。

字幕正文只取每个时间片段 `content` 的第一行，用换行连接；日志只记录 BVID、语言、数量和字符数，不记录字幕正文。

## 维护注意事项

1. 哔哩哔哩播放器的混淆结构变化时，优先检查 `PlayerSpeedSessionLocator.prepared()` 是否仍能定位 `setOnPreparedListener`、`getCurrentMediaItem()` 和 `MediaItem.getId()`。
2. 若字幕接口返回结构变化，先在 `SubtitleExportClient` 中兼容响应，再补充 `SubtitleExportTextTest`；不要把网络请求放回播放器回调线程。
3. 多 P 视频必须优先使用播放器媒体 ID 中的 CID；没有 CID 时才调用 `x/web-interface/view` 读取第一个分段。
4. 任何网络错误、JSON 错误或剪切板异常都必须被功能边界捕获，不能抛回宿主播放器。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

生成文件：`app/build/outputs/apk/debug/app-debug.apk`。
