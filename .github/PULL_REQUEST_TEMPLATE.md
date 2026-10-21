<!-- 提交前请确认 CI 需要的检查已本地通过：./gradlew assembleDebug testDebugUnitTest lintDebug -->

## 改动说明

<!-- 改了什么，为什么改。若关联 issue 请写 "Fixes #123" -->

## 改动类型

- [ ] 新功能（feat）
- [ ] 缺陷修复（fix）
- [ ] 文档（docs）
- [ ] 重构（refactor，不改变行为）
- [ ] 构建 / CI（build / ci）
- [ ] 其他

## 自查清单

- [ ] `./gradlew assembleDebug` 通过
- [ ] `./gradlew testDebugUnitTest` 全绿；纯逻辑改动已补单元测试
- [ ] 未违反模块依赖方向（`:app → :ui → :assembly → {:data, :engine} → :core`）
- [ ] 涉及「手机内置运行时」的改动已对照 HANDOVER.md 的硬约束
- [ ] 新增第三方依赖已注明许可证，并确认与 Apache-2.0 及本仓库捆绑组件许可兼容
- [ ] 文档 / 截图中的号码、令牌、wxid 等已脱敏

## 验证方式

<!-- 如何验证这个改动：命令输出、真机截图等 -->
