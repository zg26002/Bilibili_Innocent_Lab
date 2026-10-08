# SponsorBlock 兼容功能

本分支把 Brave 扩展 `eaoelafamejbnggahofapllmfhlhajdd`（SponsorBlock/小电视空降助手）的核心“片段查询 + 自动跳过”能力移植到 Android/Xposed 模块中。

## 当前行为

- 设置项：播放器设置中的“Automatically skip sponsored segments”。默认关闭。
- 开启后，在 Bilibili 主进程播放器准备完成时读取视频 BV/AV 标识。
- 将 BV 号做 SHA-256，取前四位，请求 `https://www.bsbsb.top/api/skipSegments/{prefix}`。
- 只处理 `actionType=skip` 的片段；命中后调用播放器 `seekTo` 跳到片段末尾。
- 网络失败、接口无数据、视频标识无法识别或播放器结构变化时静默降级，不影响原播放器。

目前没有把浏览器扩展的 `mute`、`full`、`poi` 动作直接映射到 Android 播放器，因为这些动作依赖扩展自己的播放器控制层。需要扩展时，应先在 `SponsorBlockFeatureInstaller` 增加独立动作策略和测试。

## 维护方式

1. 上游扩展更新时，先检查 `manifest.json` 和网络请求是否仍使用 `/api/skipSegments/{sha256(BV).take(4)}`，以及分类/动作字段是否变化。
2. 如果 BSB 服务地址变化，只修改 `SponsorBlockClient.DEFAULT_SERVER`，不要把 URL 散落到 Hook 代码中。
3. 如果 Bilibili 播放器结构变化，优先更新 `PlayerSpeedSessionLocator.Prepared` 或新增一个定位器；不要在播放进度热路径中做全量反射扫描。
4. 保持 `SponsorBlockModelsTest` 的 AV/BV 和片段命中测试；网络请求应继续使用超时、缓存和失败即空结果。
5. 设置目录新增项时同步提升 `SettingsCatalog.CATALOG_VERSION` 和数量断言，保证备份/恢复协议不会静默丢配置。

## 构建

在项目根目录使用：

```powershell
.\gradlew.bat :app:assembleDebug
```

不要写成 `.\gradlew\.bat`。构建生成的 `app/build/` 和 `.gradle/` 是本地产物，不应提交到 Git。
