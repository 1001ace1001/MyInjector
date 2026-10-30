# 上游自动同步

本仓库是 [5ec1cff/MyInjector](https://github.com/5ec1cff/MyInjector) 的二次开发分支。
`.github/workflows/sync-upstream.yaml` 负责在**上游有新提交时**自动把上游主线合并进来，
编译通过后推送到本仓库分支，并触发 `build.yaml` 产出 Release APK。

## 触发方式

| 方式 | 说明 |
| --- | --- |
| 定时 | 每天 03:17 UTC（北京时间 11:17）自动检查一次 |
| 手动 | Actions → `Sync upstream` → Run workflow，可指定分支、冲突策略、是否编译验证 |
| 外部 | 调用 `repository_dispatch`，`types: [sync-upstream]` |

Fork 仓库收不到上游的 `push` 事件，所以用定时轮询 + 手动 / 外部触发代替。

## 一次运行做了什么

1. 抓取上游 `master` 与本地所有远端分支。
2. 对每个目标分支判断 `upstream/master` 是否已是其祖先；是则跳过（不会产生空提交）。
3. 合并上游：
   - 先尝试正常合并；
   - 若有冲突且策略为 `ours` / `theirs`，用 `git merge -X ours|theirs` 重试一次；
   - 策略为 `fail` 时直接中止该分支，等人工处理。
4. 编译验证（默认开启）：`./gradlew assembleRelease`
   - 通过 → 推送分支；
   - 失败 → `git reset --hard` 丢弃这次合并，**不推送**，并把日志尾部写进任务摘要。
5. 推送成功后触发 `build.yaml` 编译 Release：
   - 用 `GITHUB_TOKEN` 推送时，push 事件不会触发其他工作流，必须显式 `gh workflow run`；
   - 配置了 `SYNC_TOKEN`（PAT）且分支是 `master` 时，push 会自然触发，避免重复构建。

## 配置项

**仓库变量**（Settings → Secrets and variables → Actions → Variables）

- `SYNC_BRANCHES`：逗号分隔的分支列表，例如 `master,lbe-17`；不设则只同步默认分支 `master`。

**仓库密钥**（可选）

- `SYNC_TOKEN`：带 `repo` 权限的 PAT。设置后推送走该身份，`master` 的构建由 push 事件触发。
  不设置则用内置 `GITHUB_TOKEN`，由工作流显式 dispatch 构建。

## 已知限制

- `-X ours` 在冲突处保留本仓库版本，可能丢掉上游对同一文件的改动。摘要里会列出冲突文件，
  建议每次同步后扫一眼；`scope.list` 这类列表文件尤其需要人工确认是否需要补上上游新增条目。
- `build.yaml` 的签名步骤只在 `master` 上生效，同步其他分支时产物是 debug 签名。
- 定时任务在仓库 60 天无提交活动后会被 GitHub 自动停用，届时手动跑一次即可恢复。
