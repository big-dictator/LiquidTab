# LiquidTab

LiquidTab 是适用于 LSPosed 的 Android 液态底栏模块。2.0 版本包含模块管理界面、统一玻璃渲染核心和按应用目录组织的适配代码。应用 ID 保持为 `io.github.offlineglass`，用于兼容既有安装和本地设置。

## 运行条件

- Android 15（API 35）或更高版本
- 已安装并启用 LSPosed；将 LiquidTab 的作用域限制在需要适配的应用
- 建议每次设置应用适配后重启目标应用进程

模块离线运行，不需要账号、网络授权或在线更新。底栏设置通过本地配置接口传递给目标进程。

## 当前正式适配范围

正式版作用域由 `app/src/main/assets/xposed_scope` 提供，模块页面清单由 `AppCatalog` 管理。2.0 按当前新版代码适配范围发布（24 个应用）：

高德地图、哔哩哔哩、哔哩哔哩国际版、美团外卖、美团、米家、小米社区、小米运动健康、小米应用商店、网易云音乐、QQ 音乐、小红书、拼多多、淘宝、抖音、菜鸟、闲鱼、京东、百度贴吧、YouTube、微博、微信、移动交通大学、知乎。

电话/联系人、短信、日历、笔记、文件管理、相册、主题商店、系统选择器和哈啰已归档，不在 2.0 作用域中；其代码（若仍存在）不会被加载。

## 主要设置与默认值

- 液态玻璃：开启；纯色底栏：关闭；底部渐变模糊：关闭
- 模糊半径：2；底栏高度：56 dp；页面间距：12 dp；单栏宽度：76 dp
- 深浅色底色透明度固定为 40%；深色高光描边强度默认 50%
- 圆角连续曲线、底色透明度使用设计默认值，不提供独立滑块

## 项目结构

- `app/src/main/java/io/github/offlineglass/hook/adapters/<应用键>/`：应用专属配置、适配器、Hook 信号与该应用的工作说明。
- `app/src/main/java/io/github/offlineglass/targets/`：按稳定顺序汇总应用配置，并管理正式列表和查找。
- `app/src/main/java/io/github/offlineglass/hook/`：模块入口、适配调度、通用导航发现和共享宿主/绘制生命周期。
- `app/src/main/java/io/github/offlineglass/rendering/`：跨应用共享的液态玻璃、模糊和材质绘制实现。
- `app/src/main/java/io/github/offlineglass/config/`：模块及目标应用共用的配置协议、默认值和本地存储。
- `app/src/main/java/io/github/offlineglass/ui/`：模块主页、应用列表、设置页和说明二级页面。
- `xposed-stubs/`：仅供编译使用的 Xposed API 最小接口声明；不会打包进 APK。
- `design/`、`docs/`：设计规范和开发文档。

新增或修改应用适配时，先阅读 `docs/AI_HANDOFF.md`，再检查对应应用目录中的现有实现。适配专属逻辑优先放在对应应用目录；确需修改共享 Hook、配置或渲染核心时，必须做跨应用回归检查。设备日志和本机交接记录不纳入公开源码。

## 构建

需要 JDK 21、Android SDK Platform 37、Build Tools 37 和网络可用的 Gradle 依赖源。Windows PowerShell：

```powershell
./gradlew.bat :app:assembleRelease
```

适配目录和 LSPosed 作用域一致性检查：

```powershell
pwsh -NoProfile -File tools/verify-adapter-workspaces.ps1
```

Release 构建产物默认位于 `app/build/outputs/apk/release/`。工程不携带签名密钥，也不在源码中设置个人签名信息；发布者应使用自己的密钥签名，并安全保存密钥。

## 开源与第三方材料

LiquidTab 源码按 GNU GPL v3 发布，完整文本见 `LICENSE`。直接依赖及授权说明见 `NOTICE.md`。随 APK 使用的若干应用导航图标属于第三方应用素材，详见 `THIRD_PARTY_ASSETS.md`；本项目许可证不授予这些素材的额外权利。请在公开分发前确认相关素材的授权范围。

本工程不包含第三方应用 APK、反编译输出、用户数据、签名密钥或设备调试记录。赞赏码由项目作者提供，仅用于模块说明页。
