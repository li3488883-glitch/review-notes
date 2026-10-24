# A股择时体检 · 安卓 App

只给自己用的原生安卓 App（Kotlin + Jetpack Compose），内容和仓库根目录的网页一致。

- 安装：手机浏览器打开 https://github.com/li3488883-glitch/review-notes/releases/tag/app-latest ，下载 `timing-app.apk` 安装。第一次安装要允许「安装未知来源应用」。
- 打包：`.github/workflows/app.yml` 在 `app/` 有改动时自动运行：先跑规则测试，再打包，最后发布到上面的 Release。
- 规则：`mobile/src/main/java/app/reviewnotes/timing/engine/Engine.kt` 是网页规则逐行移植过来的。`mobile/src/test` 里的测试用网页规则算出的结果逐条核对。
- 签名钥匙：`keystore/release.jks` 由 workflow 第一次运行时生成。仓库是公开的，任何人都能用这把钥匙签出能覆盖安装的包，所以只装自己 Release 里发布的 APK。
- 只是个人复盘工具，不构成投资建议。
