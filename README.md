# 深度阅读 DeepRead for Android

深度阅读（DeepRead）的 Android 平台工程（基于 [AmberAgent for Android](https://github.com/soul99soul-glitch/AmberAgent) 的 `deepread` 独立应用变体，包名 `app.amber.deepread`）。

- iOS 与 HarmonyOS 平台工程位于本仓库的 `main` 分支。
- 本分支（`Android`）默认启用 `deepread` Product Flavor（`STANDALONE_SURFACE = "deepread"`），构建后生成独立的深度阅读安装包与沙盒。

## 构建

```bash
# 构建 DeepRead Debug APK（输出至 app/build/outputs/apk/deepread/debug/）
./gradlew :app:assembleDeepreadDebug

# 构建 DeepRead Release APK（需在 local.properties 配置签名信息）
./gradlew :app:assembleDeepreadRelease
```

需要完整 Native APK 时，先安装 Rust、`aarch64-linux-android` target、Android NDK `27.0.12077973` 与 `cargo-ndk` 3.5.4。缺少 `cargo-ndk` 时 debug 构建会跳过 Native task（运行期回退至 JVM 实现）；完整构建与发布 CI 会校验所需的 Rust `.so` 原生库与 16 KB ELF 页对齐。细节见 [native/README.md](native/README.md)。

## 核心目录

- `app/src/deepread/`：独立应用清单（`AndroidManifest.xml`）、应用名与图标资源
- `app/src/main/java/app/amber/agent/StandaloneSurfaces.kt`：独立产品入口与模块门控
- `app/src/main/java/app/amber/feature/ui/pages/board/`：深度阅读 UI（发现页、阅读器、排版与模板宿主）
- `app/src/main/java/app/amber/feature/board/hotlist/deepread/`：专题研究、热榜聚合、音源抓取与杂志生成流水线
- `native/`：Rust 原生解析库（`reader-extractor`、`markdown-parser`、`markdown-preprocess` 等）

## 许可

见 [LICENSE](LICENSE)。
