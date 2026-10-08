# DeepRead HarmonyOS

独立深度阅读工程，包名 `app.amber.deepread.reader`。从 Amber 的独立构建目录迁移到 DeepRead 多平台仓库后，本目录即鸿蒙版本的源码与构建入口，不再由 Amber 准备脚本生成或覆盖。

- `entry/`：ArkUI、UIAbility、平台实现、资源与原生桥接。
- `deepread/`：阅读模型、收集与生成、运行状态、持久化与导出；包含当前依赖的共享领域能力。
- `chat/`：阅读器与 Provider 所需的 AI 协议、Markdown 和共享领域实现。
- `native/`：现有 Rust、SSH、Python、Mosh 原生依赖及构建脚本。
- `tests/`、各模块 `src/test/`：原有回归与协议 fixture。
- `scripts/`：本地构建、lint、签名及验证工具。

## 开发

```sh
npm ci --prefix chat
npm ci --prefix deepread
ohpm install --all
# 原生协议回归需本机 Node headers 与 CPython 测试桥：
sh native/python/scripts/build-host-addon.sh
npm test
npm run build
# 修改 ArkTS 后按改动范围检查：
bash scripts/lint-arkts.sh entry/src/main/ets/pages/DeepReadAppearancePage.ets
```

DevEco Studio 打开本目录。构建默认输出 `entry/build/default/outputs/default/`。现有本机 AGC 配置在 `build-profile.json5`；重新检出时从 `build-profile.example.json5` 复制后，在 DevEco/AGC 重新配置本机签名。示例配置不含签名凭据。`scripts/sign-hap.sh` 是离线模拟器签名工具，不能代替零售真机的 AGC 签名。

ArkTS 开发规则见 [harmony-arkts skill](../.agents/skills/harmony-arkts/SKILL.md)。保持 V1 状态模型、API 12 兼容约束；新增平台 API 先查官方文档。改 UI 后需构建、安装及截图检查。

## 迁移范围

保留独立应用身份、权限、资源和现有签名配置，迁移必需的源码与测试均在本仓库内。共享 Chat/Novel 能力当前仍作为阅读与 Provider 模块依赖保留；本次只移动平台工程，没有据目录名称删除共享实现或测试。依赖包通过本机 npm/ohpm 安装，引用路径均位于本工程内。

旧生成缓存已在迁移证据目录中归档；原生依赖工作缓存内的旧绝对路径已定向更新。迁移验证与边界见仓库根目录的迁移记录。
