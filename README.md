# LiquidTab

LiquidTab 是适用于 LSPosed 的 Android 液态玻璃界面模块。2.1.2 包含管理界面、共享玻璃渲染核心与 33 款应用的适配。应用 ID 保持为 `io.github.offlineglass`，用于兼容既有安装和本地设置。

## 特别致谢：kyant0 / AndroidLiquidGlass

特别感谢 [kyant0 的 AndroidLiquidGlass（Backdrop）](https://github.com/Kyant0/AndroidLiquidGlass)。LiquidTab 的液态玻璃折射方案参考、借鉴并改编了该项目的开源实现；这些上游贡献不应被视为 LiquidTab 独创。该项目采用 Apache License 2.0。

LiquidTab 同时使用 Miuix、AndroidX 和 Android 原生渲染能力，并包含自身的原生 View 桥接、动态高光、生命周期管理和应用适配代码。具体许可见 [NOTICE.md](NOTICE.md)。

感谢酷安博主 **小郑大王** 提供顶栏布局创意。

## 运行条件

- Android 15（API 35）或更高版本
- 已安装并启用支持现代模块接口（API 101）的 LSPosed；将 LiquidTab 的作用域限制在需要适配的应用
- 建议每次设置应用适配后重启目标应用进程

模块离线运行，底栏设置通过本地配置接口传递给目标进程。

## 当前正式适配范围

正式作用域由 `app/src/main/resources/META-INF/xposed/scope.list` 提供，与模块的应用列表一致：

高德地图、哔哩哔哩、哔哩哔哩国际版、美团外卖、美团、米家、小米社区、小米运动健康、小米应用商店、网易云音乐、QQ 音乐、Spotify、Apple Music、小红书、拼多多、淘宝、抖音、TikTok、菜鸟、闲鱼、京东、百度贴吧、YouTube、微博、微信、移动交通大学、知乎、ChatGPT、华为运动健康、X、Instagram、Reddit、Quora。

小米桌面、小米系统界面、小米相册和超级小爱不属于本次正式适配范围。

## 主要设置与默认值

- 首次安装默认中文，主题跟随系统；开启液态玻璃、滑块照亮、描边和底栏无字模式；关闭纯色、磨砂、姿态动态、简化切页动画、经典导航和动态配色。
- 默认模糊半径 0、底栏高度 56 dp、底色透明度 50%、高光描边强度 60%、高光透明度 45%、折射强度 120%、粘滞感 25%。已有设置不覆盖。
- 调节模糊半径仍影响底栏和隐藏层，点按放大、拖动时的滑块不附加模糊。升级安装保留已有设置。

## 项目结构

- `app/src/main/java/io/github/offlineglass/hook/adapters/<应用键>/`：应用专属配置、适配器和 Hook 信号。
- `app/src/main/java/io/github/offlineglass/targets/`：按稳定顺序汇总应用配置，并管理正式列表和查找。
- `app/src/main/java/io/github/offlineglass/hook/`：模块入口、适配调度、通用导航发现和共享宿主/绘制生命周期。
- `app/src/main/java/io/github/offlineglass/rendering/`：跨应用共享的液态玻璃、模糊和材质绘制实现。
- `app/src/main/java/io/github/offlineglass/config/`：模块及目标应用共用的配置协议、默认值和本地存储。
- `app/src/main/java/io/github/offlineglass/ui/`：模块主页、应用列表、设置页和说明二级页面。

## 构建

需要 JDK 21、Android SDK Platform 37 和 Build Tools 37。Windows PowerShell：

```powershell
./gradlew.bat :app:assembleRelease
```

Release 构建产物默认位于 `app/build/outputs/apk/release/`。工程不携带签名密钥，也不在源码中设置个人签名信息；发布者应使用自己的密钥签名，并安全保存密钥。

## 开源与第三方材料

LiquidTab 源码按 GNU GPL v3 发布，完整文本见 `LICENSE`。直接依赖及授权说明见 `NOTICE.md`。随 APK 使用的若干应用导航图标属于第三方应用素材，详见 `THIRD_PARTY_ASSETS.md`；本项目许可证不授予这些素材的额外权利。请在公开分发前确认相关素材的授权范围。

本工程不包含第三方应用 APK、反编译输出、用户数据、签名密钥或设备调试记录。赞赏码由项目作者提供，仅用于模块说明页。


