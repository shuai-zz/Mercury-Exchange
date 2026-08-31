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
- 基础设施通过 `build/docker-compose.yml` 一键启动

## 仓库结构

本仓库是产品仓库，模块直接位于仓库根目录：

```
├── parent/             Maven 父 POM
├── build/              docker-compose（Kafka/Redis/MySQL）+ 建库 SQL
├── config/             Spring Cloud Config 配置中心
├── config-repo/        外置配置文件
├── common/             公共代码（模型、枚举、工具类）
├── trading-api/        用户侧 REST API
├── trading-sequencer/  全局定序器
├── trading-engine/     撮合引擎（核心）
├── quotation/          行情服务
├── push/               WebSocket 推送
└── ui/                 Web 界面
```

注意：无根聚合 POM，需按依赖顺序逐个 `mvn install`（`parent` → `common` → 其余模块）。

## 构建与运行

```bash
# 启动基础设施（Kafka / Redis / MySQL / Zookeeper）
cd build && docker compose up -d

# 安装 parent POM（只需一次，或 parent 变更后）
cd ../parent && mvn install

# 安装 common（其他模块依赖它，变更后必须重新 install）
cd ../common && mvn install

# 编译其余模块：config / trading-api / trading-sequencer / trading-engine / quotation / push / ui

# 服务启动顺序：config → 各业务服务 → ui
```

## 开发计划（里程碑制）

| 里程碑 | 内容 | 对应版本标签 |
| ------ | ---- | ------------ |
| v0.1   | 项目骨架、基础设施、数据库表结构 | `v0.1.0` ✅ |
| v0.2   | common 模块 + 资产系统（Issue #4） | `v0.2.0` |
| v0.3   | trading-api 用户接口 | `v0.3.0` |
| v0.4   | 定序器 + 事件溯源机制 | `v0.4.0` |
| v0.5   | 撮合引擎（核心，重点投入） | `v0.5.0` |
| v0.6   | 清算结算 | `v0.6.0` |
| v0.7   | 行情服务 + WebSocket 推送 | `v0.7.0` |
| v0.8   | Web UI | `v0.8.0` |
| v1.0   | 崩溃恢复、7x24 运行整合（MVP 完成） | `v1.0.0` |
| v2.0   | 功能增强（手续费、市价单、开放API、多交易对、风控、压测，见 milestone "v2 功能增强"） | `v2.0.0` |
| v3.0   | 永续合约引擎（BTC-USD 单合约 MVP，4 周，提案见 Issue #13；前置依赖：v1.0 + #6 市价单） | `v3.0.0` |

## 协作规范

**一切代码变更必须通过 Issue + PR 流程，禁止直接 push 到 main。**

### Issue 管理

- 每个开发任务先建 Issue，描述目标、范围（checklist）和验收标准
- 必须打 label：`enhancement`（新功能）/ `bug` / `docs` / `chore`
- 必须挂 milestone：主线任务挂 `v1.0 交易所主线（MVP）`，增强功能挂 `v2 功能增强`
- 任务完成即关闭（优先通过 PR 的 `Closes #N` 自动关闭）

### 分支管理

- `main`：受保护分支，永远保持可构建状态，只接受 PR 合并
- 分支命名：`feat/<功能>`、`fix/<问题>`、`docs/<文档>`、`chore/<杂项>`，如 `feat/asset-service`
- 从最新 `main` 切分支；合并后删除远程功能分支
- 本地保留 `upstream-reference` 分支（原教程仓库完整代码，仅作对照参考，不推送）

### 提交与 PR

- Commit message 使用 Conventional Commits 格式：`feat:` / `fix:` / `docs:` / `chore:` / `refactor:` / `test:`
- message 用英文，一句话说明"做了什么"，必要时正文说明"为什么"
- PR 标题清晰，正文关联 Issue（`Closes #N`），说明变更内容和自测情况
- 合并前自查：相关模块 `mvn install` 通过；不提交运行时产物（`target/`、`build/docker/` 数据目录等）
- 使用 merge commit 合并

### 标签（Tag）与发布

- 采用语义化版本 `vX.Y.Z`，里程碑完成时在 `main` 上打 tag 并 push
- 每个里程碑的 tag 即该阶段的可回溯快照（替代原教程的 step 目录）

### 禁止事项

- 禁止 force push 任何已推送的分支
- 禁止提交密钥、本地环境配置（如个人代理配置）
- 禁止整段复制上游参考代码提交（学习项目，必须自己写，可对照不可照抄）

## 环境备忘

- 开发环境为 WSL，Windows 主机运行 TUN 模式代理（端口 7897），Maven 代理已配置在 `~/.m2/settings.xml`（该文件不入库）
- Bitnami 旧镜像已下架，compose 文件使用 `bitnamilegacy/*` 镜像
- zookeeper / kafka 数据目录需 `chown -R 1001:1001`（Bitnami 容器以 uid 1001 运行）
