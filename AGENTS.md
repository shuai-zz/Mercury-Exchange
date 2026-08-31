# AGENTS.md

本文件描述 Mercury Exchange 项目的目标、开发计划与协作规范，供所有开发者（包括 AI 助手）遵循。

## 项目目标

在 **两个月**内，从零开发一个 7x24 小时运行的内存撮合交易所，参考教程
[《从零开始搭建一个7x24小时运行的证券交易所》](https://liaoxuefeng.com/books/java/springcloud/)，
最终交付一个事件溯源架构、100% 内存撮合、可崩溃恢复的完整交易系统。

学习目标（按优先级）：

1. 掌握交易所核心领域知识：订单簿、撮合算法、清算、行情合成
2. 理解事件溯源（Event Sourcing）与定序器（Sequencer）设计
3. 熟练使用 Kafka / Redis / MySQL / WebSocket 构建消息驱动系统
4. 建立完整的微服务工程实践：配置中心、模块化、Issue + PR 工作流

## 技术栈

- Java 17（编译 target 17，JDK 21 可运行）+ Spring Boot 3.x + Spring Cloud 2022.x + Maven
- Kafka（事件流）、Redis（缓存）、MySQL 8（持久化）、Vert.x（WebSocket 推送）
- 基础设施通过 `step-by-step/step-1/build/docker-compose.yml` 一键启动

## 开发计划（共约 8 周）

| 周次 | 内容 | 状态 |
| ---- | ---- | ---- |
| W1 | Step 1：项目骨架、基础设施、数据库表结构 | ✅ 已完成 |
| W2 | Step 2：common 模块（核心模型、工具类）+ Step 3：trading-api | 待开始 |
| W3 | Step 4：trading-sequencer 定序器 | 待开始 |
| W4–W5 | Step 5–6：撮合引擎 + 清算结算（本项目的核心，预留两周） | 待开始 |
| W6 | Step 7：quotation 行情服务 | 待开始 |
| W7 | Step 8：push 推送 + Step 9：UI 界面 | 待开始 |
| W8 | Step 10–11：崩溃恢复、7x24 运行整合 + 收尾（文档、压测） | 待开始 |

每个 Step 的开发方式：先阅读教程对应章节，再自己实现，最后可参考上游
`michaelliao/warpexchange` 的对应 commit 对照检查（不要直接抄）。

## 构建与运行

```bash
# 启动基础设施（Kafka / Redis / MySQL / Zookeeper）
cd step-by-step/step-1/build && docker compose up -d

# 安装 parent POM（只需一次，或 parent 变更后）
cd ../parent && mvn install

# 编译/安装各模块（common 必须先 install，其他模块依赖它）
cd ../common && mvn install
# 其余模块：config / trading-api / trading-sequencer / trading-engine / quotation / push / ui

# 启动顺序：config → 各业务服务 → ui
```

注意：step-N 各阶段无根聚合 POM，需按依赖顺序逐个 `mvn install`。

## 提交规范

**一切代码变更必须通过 Issue + PR 流程，禁止直接 push 到 main。**

### 分支

- `main`：受保护分支，永远保持可构建状态，只接受 PR 合并
- 功能分支命名：`step<N>-<简述>` 或 `fix/<简述>`、`feat/<简述>`，如 `step2-common`、`fix/kafka-image`
- 历史学习分支：`step1`（保留，作为 step1 阶段快照）

### 工作流

1. **开 Issue**：每个开发任务先建 Issue，描述目标、范围和验收标准
2. **建分支**：从最新 `main` 切出功能分支
3. **开发 + 提交**：commit message 使用 Conventional Commits 格式：
   - `feat: ...` 新功能 / `fix: ...` 修复 / `docs: ...` 文档 / `chore: ...` 杂项 / `refactor: ...` 重构
   - message 用英文，一句话说明"做了什么"，必要时正文说明"为什么"
4. **开 PR**：标题清晰，正文关联 Issue（`Closes #N`），说明变更内容和自测情况
5. **合并前自查**：相关模块 `mvn install` 通过；不提交运行时产物（`target/`、`build/docker/` 数据目录等）
6. **合并**：使用 merge commit，合并后删除远程功能分支

### 禁止事项

- 禁止 force push 任何已推送的分支
- 禁止提交密钥、本地环境配置（如个人代理配置）
- 禁止把上游参考代码整段复制提交（学习项目，必须自己写）

## 环境备忘

- 开发环境为 WSL，Windows 主机运行 TUN 模式代理（端口 7897），Maven 代理已配置在 `~/.m2/settings.xml`（该文件不入库）
- Bitnami 旧镜像已下架，compose 文件使用 `bitnamilegacy/*` 镜像
- zookeeper / kafka 数据目录需 `chown -R 1001:1001`（Bitnami 容器以 uid 1001 运行）
