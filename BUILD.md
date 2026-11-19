# atvloadly Android 移植 — 交付说明

把 [bitxeno/atvloadly](https://github.com/bitxeno/atvloadly)（给 Apple TV 侧载 IPA 的服务）
移植成原生 Android App，并**通过 GitHub Actions 在 x86_64 上构建出侧载功能完整的 APK**。

## 最终产物

**`dist/atvloadly-android-arm64.apk`**（约 15.4 MB，arm64-v8a，minSdk 24 / target 34，已签名）

APK 内含两个原生二进制（都是构建时交叉编译的真实产物，非占位）：

| 文件 | 大小 | 说明 |
|------|------|------|
| `libatvloadly.so` | 26.7 MB | Go 后端（已 embed 原版 Vue 前端），原生 arm64 |
| `libplumesign.so` | 11.8 MB | **完整 Rust 侧载引擎**（PlumeImpactor），原生 arm64 |
| `classes.dex` | 12 KB | Android 壳（WebView + 启动服务） |

App 启动时把 Go 后端作为子进程拉起，WebView 加载它内置的 Vue 前端（localhost）；
配对/签名/安装则调用捆绑的 plumesign 引擎。整个流程照搬原项目，未重写侧载协议。

## 怎么用

1. 安装 `atvloadly-android-arm64.apk`（需允许安装未知来源应用）。
2. 手机与 Apple TV 连同一 WiFi。
3. 打开 App，界面会自动扫描局域网内的 Apple TV（纯 Go mDNS，无需 avahi）。
4. 配对 → 登录 Apple ID（建议专用小号）→ 选 IPA 安装。
5. 刷新为**纯手动**（已按要求关闭后台自动刷新）。

## 构建流程（GitHub Actions，x86_64）

推送即自动构建。工作流 `.github/workflows/build-android.yml` 在 x86_64 runner 上完成：

1. 装 JDK 17 / Go 1.25 / Node / Rust + `aarch64-linux-android` target + cargo-ndk
2. 装 Android SDK + NDK 26.3（含 make/ninja，供 cmake 原生构建）
3. clone atvloadly → 应用 lzfse 补丁（Android 跳过 .car 图标解码，去 purego/cgo）
4. 构建前端 → 交叉编译 Go 后端（android/arm64）
5. clone PlumeImpactor → cargo-ndk 交叉编译 plumesign（aarch64-linux-android）
6. 打包签名 APK → 上传 artifact

### 踩过的坑（都已修复，记录备查）

| 问题 | 根因 | 修复 |
|------|------|------|
| `sdkmanager: command not found` | 不在 PATH | 动态查找 cmdline-tools 下的 sdkmanager |
| `Failed to find package 'ndk;$NDK_VERSION'` | `with:` 不做 shell 展开 | 改用字面版本号 |
| setup-android 多行 packages 被当成一个参数 | action 解析 bug | 弃用 action，直接调 sdkmanager |
| `CMAKE_MAKE_PROGRAM is not set` | 缺 make/ninja | apt 装 ninja-build + 设 Ninja 生成器 |
| `linker aarch64-linux-android-clang not found` | RUSTFLAGS 覆盖了 cargo-ndk 的链接器 | 删掉 RUSTFLAGS，让 cargo-ndk 自动设 |
| `No usable artifacts... set crate-type cdylib` | `-o` 让 cargo-ndk 找 .so 而非 bin | 去掉 `-o`，从 cargo target 目录取 bin |
| lzfse/purego 编不过（CGO_ENABLED=0） | purego dlopen 需 cgo | lzfse shim，Android 跳过 .car 图标 |

## 本机环境的重要限制

本会话运行在 **aarch64 手机 proot** 上，**无法在本机交叉编译 plumesign**（NDK 只有 x86_64 版，
本机跑不了）。所以本机产出的 APK 是缺引擎的空壳（`libplumesign.so` 仅 26 字节占位）。
**侧载功能完整的 APK 必须由 GitHub Actions（x86_64）构建**，即上面这套流程——已经跑通并产出真包。

## 已在本机验证

- Go 后端原生编译并在本机端到端跑通：Fiber 启动、Vue 前端 serve、全部 API 返回 200
- 纯 Go mDNS 设备发现（Android 走 `!linux` 分支，不依赖 avahi/D-Bus）
- 自动刷新已关闭（`task.enabled=false`，手动刷新端点保留）
- 打包脚本 6 步流水线、APK 签名校验

## 待真机确认

APK 的结构、签名、两个原生库均已校验为真实有效产物。但**实际安装、配对、侧载需在真机 +
Apple TV 环境实测**——本会话无安卓真机/模拟器，无法端到端验证侧载。
若安装或运行遇到问题，把 logcat 报错发我继续排查。