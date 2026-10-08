# 深度阅读 DeepRead

同一产品的 iOS 与 HarmonyOS 平台工程放在同一个仓库中，各自独立构建、签名和安装。

```text
DeepRead/
├── ios/          # Swift / SwiftUI、XcodeGen 配置、资源和 iOS 测试
├── harmony/      # ArkTS / ArkUI、领域模块、原生依赖和鸿蒙测试
├── scripts/      # 跨目录的验证入口
└── LICENSE
```

## iOS

在 Xcode 中打开 `ios/AmberDeepRead.xcodeproj`，或从 XcodeGen 配置生成工程：

```sh
cd ios
xcodegen generate
xcodebuild -project AmberDeepRead.xcodeproj -scheme DeepRead \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

需要 Xcode 26、XcodeGen，最低 iOS 26。功能、模型配置和测试说明见 [iOS README](ios/README.md)。既有 iOS Git 历史保留在仓库根目录；平台目录整理尚未提交。

## HarmonyOS

直接在 DevEco Studio 中打开 `harmony/`。命令行开发：

```sh
cd harmony
# 首次检出时创建本机工程配置；本次迁移已保留现有配置。
cp -n build-profile.example.json5 build-profile.json5
npm ci --prefix chat
npm ci --prefix deepread
ohpm install --all
npm test
npm run build
```

需要 HarmonyOS Command Line Tools 与对应 SDK。API 基线为 12。真机必须使用 AGC 调试签名；已有本机签名配置未改变，签名材料和本机 `build-profile.json5` 不进入版本控制。

鸿蒙平台说明见 [HarmonyOS README](harmony/README.md)。独立应用包名保持 `app.amber.deepread.reader`，覆盖安装可延续原应用沙盒。源码、测试、构建脚本和原生依赖均位于本仓库内，构建不再从 Amber 工作区同步源码。

## 原工程入口

`amber-harmony-preview/scripts/harmony-standalone/prepare.mjs --product deepread` 在同级 `DeepRead/harmony` 存在时直接返回该工程；原独立构建命令也使用新位置，不会覆盖这里的源码或签名配置。Amber 主应用和小说产品所需的共享源码仍保留在原仓库中。

## 许可

iOS 沿用根目录 [LICENSE](LICENSE)；HarmonyOS 沿用拆出来源的 [LICENSE](harmony/LICENSE)。
