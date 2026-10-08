# reply-topology-export 分支说明

## 这个分支做什么

`reply-topology-export` 是 `jichuo1/Bilibili_Innocent_Lab` 的一个个人维护功能分支。

它保留原项目的全部功能，同时增加评论“回复脉络”窗口的辅助能力：

- 按关键词筛选作者、被回复作者和评论正文；
- 筛选命中后自动保留祖先节点，避免回复关系断开；
- 导出当前筛选结果到 Android 系统剪贴板；
- 保留背景透明度调节、收起/展开和“完整脉络已载入”状态；
- 中英文界面均提供对应的按钮和提示文案。

功能实现主要位于：

```text
Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/overlay/ReplyTopologyPanelContract.kt
Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/overlay/ReplyTopologyPanelView.kt
Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/overlay/ReplyTopologyWorkflowAdapter.kt
```

`gradle.properties` 中的 `android.overridePathCheck=true` 用于允许 Windows 中文目录路径下的 Android Gradle Plugin 正常构建，不属于功能逻辑。

## 分支关系

仓库配置了两个远程地址：

```text
origin   https://github.com/zg26002/Bilibili_Innocent_Lab.git
upstream https://github.com/jichuo1/Bilibili_Innocent_Lab.git
```

建议保持下面的分工：

| 分支 | 用途 |
| --- | --- |
| `main` | 只跟随原作者项目的稳定代码 |
| `reply-topology-export` | 在 `main` 基础上保留回复脉络筛选和剪贴板导出功能 |

不要直接在 `main` 上开发自定义功能。这样原作者更新时，先更新 `main`，再把更新合并到功能分支，后续处理冲突会更清楚。

## 同步原作者更新

在仓库根目录执行：

```powershell
git fetch upstream --prune

git switch main
git merge upstream/main
git push origin main

git switch reply-topology-export
git merge main
git push origin reply-topology-export
```

这里使用普通 `merge`，不要求强制推送，比较适合长期维护。若原作者没有更新，`git merge upstream/main` 会提示已经是最新状态。

## 更新冲突怎么处理

最可能发生冲突的文件是上面列出的三个回复脉络文件。处理原则是：

1. 保留原作者对现有回复脉络加载、窗口生命周期和版本兼容的最新改动；
2. 保留本分支的筛选状态、祖先节点保留逻辑、导出文本生成和剪贴板按钮；
3. 不要直接对整个文件选择“全部使用当前”或“全部使用传入”，而是逐段检查冲突标记；
4. 删除 `<<<<<<<`、`=======`、`>>>>>>>` 后再编译验证。

完成冲突处理后执行：

```powershell
git add Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/overlay/ReplyTopologyPanelContract.kt
git add Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/overlay/ReplyTopologyPanelView.kt
git add Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/overlay/ReplyTopologyWorkflowAdapter.kt
git commit -m "Merge upstream updates into reply topology branch"
git push origin reply-topology-export
```

如果冲突暂时无法处理，可以先执行 `git merge --abort` 恢复到合并前，再单独备份修改后重新处理。

## Windows 编译 Debug APK

Gradle 工程在 `Bilibili_Innocent_Lab` 子目录，不是仓库根目录。执行：

```powershell
Set-Location .\Bilibili_Innocent_Lab
.\gradlew.bat :app:assembleDebug --console=plain --no-daemon
```

APK 输出位置：

```text
Bilibili_Innocent_Lab/app/build/outputs/apk/debug/app-debug.apk
```

计算 APK 校验值：

```powershell
Get-FileHash .\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

`.gradle/`、`app/build/`、`local.properties` 和 APK 文件是本地构建产物，不要提交到 Git。

## 提交和发布建议

- 日常测试使用 `reply-topology-export` 分支编译的 Debug APK；
- 原作者更新合并完成后，先确认评论区基础功能，再测试关键词筛选和剪贴板导出；
- 发布给自己使用时，可以从该分支手动构建 APK；
- 如果以后要公开发布 Release，应明确标注这是个人修改版，不要让用户误以为是原作者官方构建；
- 发布前检查 APK 签名、版本号和 SHA-256，并保留上一个可用 APK 以便回退。

## 推荐的日常流程

```text
同步 upstream/main
        ↓
更新自己的 main
        ↓
合并到 reply-topology-export
        ↓
解决冲突并编译测试
        ↓
推送功能分支
```

这样可以同时得到原作者的持续更新，并保留评论回复脉络导出功能。
