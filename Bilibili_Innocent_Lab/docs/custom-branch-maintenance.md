# 自定义分支维护说明

本仓库用于在原作者 `jichuo1/Bilibili_Innocent_Lab` 持续更新的基础上，保留本分支的自定义功能。

## 分支和远程仓库

- `upstream`：原作者仓库，只拉取更新，不直接推送。
- `origin`：个人仓库 `zg26002/Bilibili_Innocent_Lab`，用于保存和发布本分支。
- `reply-topology-export`：当前长期维护分支，包含原作者更新、评论脉络导出、SponsorBlock 跳过和播放器字幕导出到剪切板功能。

## 同步原作者更新

在项目根目录执行：

```powershell
git switch reply-topology-export
git fetch upstream main
git merge upstream/main -m "Merge latest upstream updates while preserving custom features"
```

如果出现冲突，按以下规则处理：

1. 评论脉络 UI、关键词筛选、树状图、局部路径和剪贴板导出，优先保留 `upstream/main` 的最新实现；
2. SponsorBlock 相关文件和设置项 `player.sponsor_block.enabled` 保留本分支实现；
3. 字幕导出相关文件为 `SubtitleExportFeatureInstaller.kt`、`SubtitleExportClient.kt` 和 `SubtitleExportText.kt`，按钮默认可用，不新增设置项；
4. 设置目录快照与公告数量测试要同步包含 SponsorBlock 项；
5. 解决后运行 `git diff --check`，确认没有冲突标记，再进行测试。

## 验证、构建和推送

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
git push origin reply-topology-export
```

Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 新增自定义功能

自定义功能应尽量放在独立文件和独立设置项中，避免直接改动原作者经常更新的核心文件。新增设置时同时更新：

- `SettingsCatalog`；
- `app/src/test/resources/settings-backup/catalog-v*.txt` 快照；
- 相关设置数量或兼容性测试；
- 本文档的功能说明。

提交前建议检查：

```powershell
git status --short
git diff --check
git log --oneline --decorate -5
```

不要把 `.gradle/`、`build/` 或本地构建日志提交到 Git。
