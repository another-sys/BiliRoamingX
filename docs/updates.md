# 国内集成包更新

更新清单位于 `http://cdn.ianother.cn/biliroamingx/update.json`。APK、模块、源码对应构建信息和校验和位于同域名的 `biliroamingx/releases/<tag>/`，同时保留 GitHub Release 附件。

## 客户端

包含本更新器的版本在首页恢复后延迟 15 秒后台检查，每 24 小时最多尝试一次。检查及下载使用独立工作线程，不占用首页推荐补页或模块公共工作线程。播放页不自动弹出更新。设置中可关闭自动检查；「哔哩漫游 X → 关于 → 检查更新」可随时手动检查。

更新前显示发行版本、更新说明和下载大小，用户确认才下载。下载可取消；完成后先验证 SHA-256、大小、包名和客户端版本，再提示覆盖安装。Android 的安装来源权限与安装确认由用户处理。已安装版本的签名由系统在覆盖安装时检查，原有账号和设置保留。仅修改漫游模块的更新也会发布完整 APK。

清单使用固定 APK 发布证书对应的 RSA 密钥签名，客户端内置公钥，验证签名后才接受下载地址。按用户选择，公开更新文件先使用 HTTP，仅允许本域名的 `biliroamingx/` 路径；HTTPS 地址也受支持。HTTP 不保护请求隐私或可用性，但 APK 完整性由签名清单与 SHA-256 保证，系统安装器还会校验 APK 签名。更新请求不携带哔哩哔哩账号或令牌。发行编号来自源码提交时间，并独立于哔哩哔哩客户端版本。客户端记录见过的最高发行编号，拒绝较旧清单触发降级。

旧版没有更新器，需要手动覆盖安装一次本版本。下载过程中退出进程后需重新检查并下载，不提供跨进程断点续传。

## 发布

1. 修改代码及 `docs/update-notes.txt`，完成本地验证并提交到 `main`。
2. 在对应提交上打 `v*` 标签，推送后触发 `release-apk.yml`。
3. 工作流构建、签名并验证 APK，生成签名清单，发布 GitHub Release。
4. 使用 GitHub OIDC 换取短期 STS 凭据，上传 OSS。先上传不可覆盖的版本目录，再通过公开 CDN 完整下载 APK 核验 SHA-256。
5. 只有公开下载验证成功，才替换发现新版本用的 `update.json`。清单设为 `no-store, no-cache, max-age=0, must-revalidate`，发布后再验证 CDN 返回内容。

发布任务串行执行。较旧发行编号或相同编号但不同 APK 会被拒绝，已发布版本目录不覆盖。重新发布实际修改应创建新的源码提交和新标签。单纯推送 `main` 不发布更新。

OSS 发布独立于构建任务，使用同一次构建上传的原始附件。网络故障时选择 Actions 的「Re-run failed jobs」只重试发布，不重新生成签名包；这样可以安全继续上传相同版本目录。不要为重试覆盖 tag。

若发布脚本本身需要修复，可在修复后的 `main` 手动运行 `Republish an existing release to OSS` 并填写已有 tag。它下载已有 Release 的原始附件、核验签名和版本，再上传；不重新构建 APK，也不替换 GitHub 附件。该任务与 tag 发布共享串行锁。

## OSS 配置

- Bucket：`another-oss`；地域：`cn-beijing`；前缀：`biliroamingx/`。
- 保留原有私有 Bucket ACL，仅本前缀内发布对象设置为 `public-read`。
- OIDC 提供商：`github-biliroamingx`；角色：`biliroamingx-release`。
- 信任来源仅 `another-sys/BiliRoamingX` 的 `main` 和 `v*` 标签，不信任 fork 或 PR。
- 角色仅允许该前缀的 `GetObject`、`PutObject`、`PutObjectAcl`，不授予删除、列举或其他目录权限。
- 不把 OSS AccessKey 或签名私钥放入 APK。GitHub 无长期 OSS AccessKey；既有 APK 签名密钥继续保存在 GitHub Secrets。

`Verify OSS update hosting` 手动工作流可验证 OIDC、上传、CDN 公开读取及跨目录读取拒绝。其小型检查文件保留在 `biliroamingx/checks/`，不修改已有业务文件。

当前域名 HTTPS 未启用。后续启用 HTTPS 后可先通过签名清单切换 APK 下载地址，再随客户端更新切换清单地址。上传 APK 的 OIDC 角色没有 DNS 或证书管理权限。证书私钥不进入源码仓库。
