# DeepRead 平台目录整理

## 最终布局

- `ios/`：原 DeepRead iOS 文件整体移入，包括未提交源码、资源、测试、XcodeGen 配置、Xcode 工程、原有文档和构建产物。
- `harmony/`：从 `amber-harmony-preview/.harmony-standalone/deepread` 实体移动的独立鸿蒙工程；必需的 ArkTS、领域模块、原生依赖、fixture、测试、脚本均在本仓库内。
- 根目录保留原 DeepRead `.git` 与许可；增加平台入口说明、独立测试入口与鸿蒙开发 skill。

两边的 HEAD 均未改变，没有提交、推送、重写历史或清理未提交内容。搬移完成时核对了 iOS 116 个源文件/配置文件，全部一致。随后另一轮 iOS 工作更新了 `IOSDeepReadTemplates.swift`、`DeepReadRenderingReviewTests.swift` 和原 iOS review 文档；这些新修改保留在 `ios/`。本轮仅更新了 iOS README 的目录和许可链接。

## 鸿蒙工程与依赖

保留独立包名 `app.amber.deepread.reader`、独立产品资源、权限与本机 AGC 签名配置。`build-profile.json5` 与迁移前逐字一致，三个外部签名材料路径全部可用；本机配置及签名材料受 `.gitignore` 排除，新增不含凭据的 `build-profile.example.json5`。

原生成目录的缓存包含旧绝对路径：主工程 `.hvigor`、`.cxx` 与构建目录归档后重新生成；原生依赖的 80 个生成文本文件定向修正旧工程前缀，并保留源码包、安装目录和原生库。修正后在新路径重新编译了原生依赖；当前工程无断开的符号链接。Node/ohpm 依赖已在新目录本地安装，没有回链到 Amber 工作区。

独立快照中遗漏的本机 CPython 测试 addon 已补入新工程；源构建脚本一并保留，重新检出可用 `sh native/python/scripts/build-host-addon.sh` 构建。该 addon 属于生成缓存，不进入 Git。全量测试入口保留 TS、CJS、跨模块 fixture 与 lint 工具回归，去除属于原 Amber 产品组装器的测试入口；原组装器在原仓库单独验证。

`harmony/scripts/build.mjs` 自动定位 SDK，并在未设置 JAVA_HOME 时使用 DevEco 自带 JBR，避免当前机器 `/usr/bin/java` 没有默认运行时导致打包失败。此处只调整构建环境，没有改变应用 UI 或业务逻辑。

## 旧入口

原 `prepare.mjs --product deepread` 在同级 `DeepRead/harmony` 存在且应用身份正确时直接返回新工程，不进行源码生成或覆盖。原 `build.mjs --product deepread` 使用新工程自带的 builder；实际执行后构建成功，输出指向 `DeepRead/harmony/entry/build/default/outputs/default`。小说及 Amber 主应用的共享源码保留在原仓库中。

旧入口新增回归：验证返回新工程、保留签名配置、不重新生成旧目录、拒绝错误的应用身份。临时 fixture 使用隔离的父目录。

## 实际验证

- iOS：从 `ios/AmberDeepRead.xcodeproj` 执行完整 iOS Simulator 构建，`BUILD SUCCEEDED`。没有改动工程内部相对路径。
- 鸿蒙：从 `harmony/` 执行 `npm run build`，独立应用完整构建成功，AGC 签名与摘要校验通过。
- 鸿蒙：`npm test` 全量 4425 通过、0 失败；旧 Amber 产品准备脚本 7 项单独回归通过。
- 首轮全量回归发现本机原生 addon 缺失，以及共享小说测试的等待时序失败。补全 addon 后，该小说测试单独复验及后续全量回归均通过，没有修改其测试断言或生产实现。
- 新入口脚本语法检查、改动 diff 空白检查通过。必需模块文件与原共享源码逐一比对一致；iOS 初始文件路径没有遗漏，迁移中的并行改动也保留。
- 本次只整理目录与构建链，没有重新安装真机或重新进行 UI/Provider 行为验收。

证据、目录搬移清单、修正前缓存和日志在 `/tmp/deepread-platform-move-20261007/`。主要文件：`ios-files-before.json`、`ios-moved-top-level.json`、`ios-post-move-changes.json`、`native-cache-relocated.json`、`file-verification.txt`、`ios-build.log`、`harmony-build-final.log`、`harmony-tests-final.log`、`harmony-signature.log`、`legacy-prepare-tests.log`、`legacy-build-final.log`。
