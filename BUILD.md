# atvloadly Android 移植 — 构建说明

本目录把 [bitxeno/atvloadly](https://github.com/bitxeno/atvloadly) 移植成原生 Android App：
Go 后端交叉编译成 arm64 原生二进制，启动时作为子进程运行，WebView 加载它内置的 Vue 前端
（前端已 embed 进 Go 二进制，Apple 风格、已做移动适配）。

## 两条产物，先分清

| 产物 | 侧载/配对 | 在哪构建 | 说明 |
|------|-----------|----------|------|
| `dist/atvloadly-android-arm64.apk`（本机已出） | ❌ 不可用 | 本机 aarch64 | 能装、能跑界面、能扫到 Apple TV、手动刷新；**缺侧载引擎** |
| x86_64 一键构建（本目录脚本） | ✅ 可用 | 任意 x86_64 机器 / GitHub Actions | 含交叉编译的 plumesign 引擎，侧载完整可用 |

**关键事实**：侧载核心是外部 Rust 引擎 `plumesign`
（[PlumeImpactor](https://github.com/bitxeno/PlumeImpactor) v2.7.1-patch.11）。
它官方只发布 linux/macOS/Windows，**没有 Android 版**；且依赖含 C/汇编的 `ring`。
在 aarch64 主机上无法交叉编译到 Android（NDK 只有 x86_64 Linux 版）。所以侧载可用版
**必须在 x86_64 主机上构建**。

## 在 x86_64 上构建侧载可用的完整 APK

前提：x86_64 Linux、已装 JDK 17、Go 1.25、Node、Rust（stable + `aarch64-linux-android` target）、
`cargo-ndk`、Android SDK（build-tools 34 + platform android-34 + NDK 26.3）。

```bash
# 1. 拿到原项目源码
git clone https://github.com/bitxeno/atvloadly.git && cd atvloadly

# 2. 应用 lzfse 补丁（Android 上跳过 .car 图标解码，去掉 purego/cgo 依赖）
#    把本目录 patch/lzfseshim/ 拷到 atvloadly/pkg/lzfseshim/
#    并把 pkg/bom/pkg/asset/decode_rgb.go 的 import 改为:
#       lzfse "github.com/bitxeno/atvloadly/pkg/lzfseshim"

# 3. 构建前端
cd web/static && npm ci && npm run build && cd ../..

# 4. 交叉编译 Go 后端 (android/arm64, 前端已embed)
CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
  go build -ldflags="-s -w" -o libatvloadly.so main.go

# 5. 交叉编译 plumesign 引擎 (aarch64-linux-android)
git clone --depth 1 --branch v2.7.1-patch.11 \
  https://github.com/bitxeno/PlumeImpactor.git engine && cd engine
cargo ndk -t aarch64-linux-android -o out build --release --bin plumesign
cd ..; cp engine/out/aarch64-linux-android/plumesign plumesign-android

# 6. 打包 APK (aapt2/d8/apksigner 在 x86_64 原生运行)
cd ../android   # 本目录
GO_BIN=../libatvloadly.so \
ENGINE_BIN=../plumesign-android \
ANDROID_SDK_ROOT=/opt/android-sdk \
  bash build-apk.sh
# 产物: android/dist/atvloadly-android-arm64.apk
```

或直接用 GitHub Actions：把 `.github/workflows/build-android.yml` 放进仓库，
`workflow_dispatch` 触发即可云端出包（需先把 lzfse 补丁提交进仓库）。

## 本目录内容

- `app/` Android 壳：`MainActivity.java`（拉起 Go 服务 + WebView + 写入"关闭自动刷新"配置）、`AndroidManifest.xml`
- `res/` 资源：iOS 风格主题、启动图标、网络安全配置
- `build-apk.sh` 一键打包脚本（aapt2→javac→d8→bundle→zipalign→sign，已验证跑通）
- `patch/lzfseshim/` lzfse 补丁（Android 上优雅跳过 .car 图标，不碰 cgo/purego）
- `.github/workflows/build-android.yml` 云端构建工作流
- `libs/arm64-v8a/libatvloadly.so` 本机已交叉编译的 Go 后端（可直接复用）

## 已验证

- Go 后端原生编译并在本机端到端跑通：Fiber 启动、Vue 前端 serve（HTTP 200，JS 616KB 正常加载）
- 全部 API 端点 200：`/api/version|settings|devices|apps|accounts|scan|service/status|healthcheck`
- 自动刷新已关闭（后端日志 `disabled`，`task.enabled=false`），手动刷新端点保留
- 打包脚本 6 步全通，APK 签名校验通过
- 设备发现走纯 Go mDNS（Android 走 `!linux` 分支，不依赖 avahi/D-Bus）

## 尚未在本机验证的部分（诚实说明）

- **侧载/配对/签名**：本机产出的 APK 缺引擎，无法端到端验证；需在 x86_64 构建后于真机 +
  Apple TV 环境实测。构建脚本的 bundling 逻辑已用占位引擎验证无误。
- 未在真实 Android 设备上安装运行（本环境无模拟器/真机），APK 结构、签名、资源均按标准流程
  生成并校验通过。
