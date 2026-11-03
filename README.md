# 哔哩漫游 X · 9.17 适配版

基于 [BiliRoamingX/BiliRoamingX](https://github.com/BiliRoamingX/BiliRoamingX) 的公开派生项目，保留 GPL-3.0 许可。当前构建目标为官方粉版 **9.17.0（9170300）、ARM64、Android 7.0 及以上**。

**这是兼容性预览版，尚未恢复上游全部增强功能。** 具体范围和验证记录见 [兼容性说明](docs/compatibility.md)。不支持以同一配置构建 Play 版、HD 版或任意其他版本。

## 下载安装

前往 [本仓库 Releases](https://github.com/another-sys/BiliRoamingX/releases)，下载 `Bilibili-9.17.0-BiliRoamingX-…-arm64.apk`。这是已经合并模块的完整客户端，无需 Root、LSPosed 或单独安装模块 APK。

- 首次安装与官方签名不一致：先备份数据、卸载官方版，再安装本版本。
- 本仓库使用固定签名；之后从本仓库下载的同包名版本可覆盖升级。请保存签名密钥备份。
- 设置入口在客户端设置内的「哔哩漫游 X」。区域相关功能需要用户自行配置可用的解析服务；构建不附带服务账号或访问密钥。
- `BiliRoamingX-integrations-*.apk` 是构建模块，**不是**完整客户端。普通用户下载以 `Bilibili-` 开头的 APK。
- 每个发布附带 `SHA256SUMS.txt`、`build-info.json` 及对应 tag 源码。

## 打 tag 自动构建

仓库已提供 [Build installable Bilibili APK](.github/workflows/release-apk.yml)。推送 `v*` tag 会从该提交构建模块与补丁、验证官方 APK、合并、签名，并创建带 APK 的 GitHub 预发布。

```sh
git tag v1.24.0-bili9.17.0-rc.2
git push origin v1.24.0-bili9.17.0-rc.2
```

使用新 tag 发布，不要覆盖已有 tag。也可以在 Actions 中手动运行；手动构建只上传 Actions artifact，不创建 Release。

自动化需要以下仓库 Actions secrets（本仓库已配置）：

| Secret | 内容 |
| --- | --- |
| `APK_KEYSTORE_BASE64` | PKCS12/JKS 签名文件的 Base64 |
| `APK_KEYSTORE_PASSWORD` | 签名文件密码 |
| `APK_KEY_ALIAS` | 签名别名 |
| `APK_KEY_PASSWORD` | 私钥密码 |

签名证书 SHA-256 固定为 `15c915cf551ebfa7154d6bf3ff829345366a7e59011eee105bfe7901c68b0959`。密钥和密码不会放入 Git、构建附件或日志。另行 fork 本仓库时必须使用自己的密钥并修改 `release_signer_sha256`，不能使用这个仓库的证书指纹冒充已有签名。

## 更新客户端版本

`release-config.json` 固定官方客户端版本、SHA-256、官方证书及定制 CLI 版本。官方最新下载入口一旦换包，校验会失败；**不会把未适配的新包当成成功发布**。

更新时需要检查官方包的版本与证书，修改指纹及集成代码，重新执行补丁和设备验证，最后更新配置与兼容性记录再打 tag。打 tag 本身不能自动完成对未来客户端的逆向适配。

补丁工具有“个别补丁失败仍退出 0”的行为，本项目额外检查完整日志。任何未声明排除的补丁失败、核心网络入口缺失、源包变化、签名变化都会中止发布。

## 本地构建

需要 JDK 17、Python 3.11+、Android SDK 35 / Build Tools 35.0.0、NDK 26.3.11579264、CMake 3.22.1。Windows 请放在纯英文路径中构建。

```sh
git clone --recurse-submodules https://github.com/another-sys/BiliRoamingX.git
cd BiliRoamingX
python scripts/release.py tools
python -m unittest discover -s scripts/tests -v
./gradlew dist
python scripts/release.py source
python scripts/release.py patch
```

设置 `ANDROID_HOME`，Windows 使用 `gradlew.bat`。最终签名构建还需设置 `APK_KEYSTORE_PATH`、`APK_KEYSTORE_PASSWORD`、`APK_KEY_ALIAS`、`APK_KEY_PASSWORD`，然后执行 `python scripts/release.py release`。结果写入 `dist/`；发布前要求此目录为空，避免混入旧产物。

定制 CLI 从原作者的 [v4.6.0.2 Release](https://github.com/zjns/revanced-cli/releases/tag/v4.6.0.2) 下载并校验。构建使用其中的兼容 patcher API，不再依赖已无法匿名访问的旧 GitHub Maven registry。

## 来源与许可

原项目源码基线：`ae58109f3acdd53ec2d2b3fb439c2a2ef1886221`（2024-09-24）。本仓库保留原历史和作者信息。模块及补丁使用 [GPL-3.0](LICENSE)；哔哩哔哩客户端属于其原作者，本仓库的许可不改变其权利归属。
