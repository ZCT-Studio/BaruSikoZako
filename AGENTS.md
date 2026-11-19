# BaruSikoZako · Agent 入口

## 文档读取顺序
1. **优先读取** `private/agents/docs/` 下的所有文档
2. 再读取 `public/agents/` 下的内容（如有）
3. 目前所有文档均为 private，位于 `private/agents/docs/`

## 核心文档索引（private/agents/docs/）
- [项目进度与交接](private/agents/docs/status.md)
- [产品范围](private/agents/docs/requirements.md)
- [总体架构](private/agents/docs/architecture.md)
- [技术决策记录](private/agents/docs/decisions.md)
- [开发验证](private/agents/docs/development.md)
- [在线捕获](private/agents/docs/online-capture.md)
- [构建文档](private/agents/docs/build.md)
- [Zstd 基准](private/agents/docs/benchmarks/zstd/README.md)
- [公共接口](private/agents/docs/public-api.md)
- [接口与格式](private/agents/docs/interfaces-and-formats.md)
- [平台适配](private/agents/docs/platform-adaptation.md)
- [旧版服务端适配](private/agents/docs/legacy-server.md)
- [Zstd 模式](private/agents/docs/zstd-modes.md)
- [恢复机制](private/agents/docs/recovery.md)
- [捕获策略](private/agents/docs/capture-strategies.md)
- [依赖审计](private/agents/docs/dependency-audit.md)

## 项目状态
- Phase 0–4 已确认。用户批准 Phase 5 平台适配研究与依据既有决策的工程骨架、Core、公共接口及 Zstd 静态文件开发并行推进。
- 在已确认的产品定位、架构、安全、性能和兼容性要求内，自主完成设计、选型、实现和验收，记录关键取舍。
- 改变核心架构或产品定位、重大兼容性破坏或不可逆格式变更、降低数据安全保证、引入重大外部依赖或更改许可证，以及无法自主解决的实质性阻碍，才暂停并请求确认。
- 技术信息核对官方文档、上游源码或发行记录，清楚区分候选范围、上游可用与本项目实际验收。不得用跳过测试或关闭检查掩盖问题。
- 决策和验收结果同步更新设计主文档与进度页。未经授权，不创建 Git 提交、标签或推送。
- 项目采用 [MPL-2.0](../LICENSE)。新增或迁入的原创源码使用 [构建文档](private/agents/docs/build.md#许可证与第三方声明) 中指定的版权头；保留第三方原有声明。

## Changelog 规则

> 本文件记录**面向用户**的改动，内容会直接用于 GitHub Release 与发布说明。
> 纯内部的调整（重构、构建配置、文档整理等）不在此列出。

### 格式要求

- 采用双语格式，基于 [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) 和 [语义化版本](https://semver.org/lang/zh-CN/)
- 分类按顺序排列，空分类省略：**Added / 新增**, **Changed / 变更**, **Deprecated / 弃用**, **Removed / 移除**, **Fixed / 修复**, **Security / 安全**
- 性能优化归入 **Changed / 变更**
- **每条变更必须包含两行语言：英文在前，简体中文在后**
- 用 `<br>` 分隔两行（第二行开头）
- 缺失翻译用 `[TODO]` 占位；常规开发提交可含 `[TODO]`
- 两种语言完成后必须语义一致
- **同一条目不得两语言都是 `[TODO]`**
- 变更聚焦用户可见功能，不记内部实现细节

### 版本标题结构

- 版本标题用 Markdown 二级标题（`##`），格式：`## [版本号] - YYYY-MM-DD`
- 保留 `## [Unreleased / 未发布]` 占位最新开发变更
- 发版时将 Unreleased 移至对应版本标题下
- 不用引用块包裹版本标题
- 保持 CI 能自动提取的格式

### 示例

**双语完整：**
```markdown
- Added new teleport feature.
<br> 新增传送功能。
```

**仅英文：**
```markdown
- Added new teleport feature.
<br> [TODO]
```

**仅中文：**
```markdown
- [TODO]
<br> 新增传送功能。
```

**无效（禁止）：**
```markdown
- [TODO]
<br> [TODO]
```

### 版本发布要求

- 发版前完成该版本所有 `[TODO]` 翻译
- 发布版本号与项目版本配置一致
- 不修改已发布的 changelog 条目（除非纠错）
- 不自行创建或移动 Git 标签（需显式授权）

### 归档规则

- 旧版本按主/次版本号归档至 `changelogs/v<major>/<major>.<minor>.md`
  - 例：`changelogs/v1/1.0.md`
- 当前开发系列保留在 `CHANGELOG.md`
- 仅在维护结束后归档
- 归档保留原版本号、发布日期、双语条目
- 不在 `CHANGELOG.md` 重复归档条目
- 归档不得破坏 CI 的发布说明提取

## AI 约束：测试存档项目
- **严禁**直接复制测试存档文件/目录进行测试
- **必须**通过 Git 进行版本控制和反复运用：
  - 使用 `git stash` / `git checkout` / `git restore` 管理测试状态
  - 测试前创建临时分支或提交，测试后通过 Git 恢复
  - 利用 `git worktree` 实现并行测试环境隔离
  - 所有测试数据变更必须可追溯、可回滚、可复现
- 这适用于所有测试场景：单元测试、集成测试、基准测试、手动验证
