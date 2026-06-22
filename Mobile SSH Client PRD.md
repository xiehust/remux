# Remux — 需求描述

> **Remux** — *Remote + tmux.* Your tmux, in your pocket.

## 产品定位

**Remux** 是一款面向开发者的移动端 SSH 客户端（Android 优先，后续扩展 iOS），专为在手机/平板上连接远程 tmux 会话中的 AI Coding Agent（Claude Code、Codex 等）而优化。参考 Termius 的交互设计，但聚焦于 AI 编程助手的移动端操作体验。

**Tagline:** *Tap into your AI agents, anywhere.*

## 目标用户

- 使用 EC2 实例或 MacBook 作为开发主机的开发者
- 日常通过 tmux 运行 Claude Code / Codex 等 CLI AI 工具
- 需要在移动场景下（通勤、会议间隙等）查看进度、发送指令、审阅输出

## 核心功能

### 0. 网络桥接方案（解决防火墙穿透）

**问题**：EC2 和 MacBook 处于防火墙/NAT 内，不接受入站连接，但支持出站。App 无法直连。

**方案：Relay Server + 反向隧道**

```
┌──────────┐         ┌──────────────┐         ┌──────────┐
│  Mobile  │◄───────►│ Relay Server │◄────────│EC2/MacBook│
│   App    │  WSS    │  (公网)       │  反向隧道 │ (防火墙内) │
└──────────┘         └──────────────┘         └──────────┘
                          ▲
                     用户通过 App
                     选择已注册设备

```

**架构设计：**

1. **Relay Server（中继服务）**- 部署在公网（如 AWS EC2/Lambda + API Gateway，或轻量 VPS）
- 职责：设备注册、连接握手、数据转发
- 协议：WebSocket (WSS) 双向通信
- **AWS 自建方案（推荐）：**
  - **API Gateway (WebSocket API)** — App ↔ Relay 的 WSS 长连接管理，自动扩缩容、按消息计费
  - **Lambda** — 连接生命周期处理（$connect / $disconnect / $default），设备注册、认证、路由
  - **DynamoDB** — 设备注册表（device_id, connection_id, 状态, 心跳时间戳）
  - **ECS Fargate (或 EC2 t4g.small)** — 有状态 TCP relay 进程，桥接 WebSocket ↔ SSH 流
  - **NLB (Network Load Balancer)** — 设备 Agent outbound TCP 长连接入口

**AWS 架构拓扑：**

```
┌──────────┐    WSS     ┌─────────────────────────────────┐
│  Mobile  │◄──────────►│  API Gateway (WebSocket API)    │
│   App    │            │         ↓                       │
└──────────┘            │  Lambda (认证 + 路由分发)         │
                        │         ↓                       │
                        │  ECS Fargate                    │
                        │  (TCP Relay 双向桥接进程)         │◄─── TCP outbound ───┐
                        │         ↓                       │                     │
                        │  NLB (TCP 长连接入口)             │               ┌─────┴────┐
                        │         ↓                       │               │EC2/MacBook│
                        │  DynamoDB (设备注册表 + 路由)      │               │(防火墙内)  │
                        └─────────────────────────────────┘               └──────────┘
```

**成本估算（低流量，< 10 台设备）：**
- API Gateway WebSocket: ~$1/百万消息
- Lambda: 免费层覆盖
- DynamoDB on-demand: ~$1/月
- ECS Fargate (0.25vCPU / 0.5GB): ~$10/月
- NLB: ~$16/月（固定费）
- **合计约 $28/月**

2. **设备端 Agent（守护进程）**- 部署在 EC2 / MacBook 上，开机自启
- 主动向 Relay Server 发起 outbound 连接（WebSocket 或 SSH 反向隧道）
- 通过 NLB 接入 ECS Fargate relay 进程
- 保持心跳，断线自动重连
- 接收到 App 端连接请求后，将流量转发到本地 sshd（127.0.0.1:22）
- 用 Rust 或 Go 编写，跨平台支持 Linux (EC2) + macOS (MacBook)
3. **App 端连接流程**- App → Relay Server 认证（token / OAuth）
- 获取已注册在线设备列表
- 选择设备 → Relay 桥接 → 与设备 Agent 建立端到端通道
- 在通道上跑标准 SSH 协议（对上层终端透明）
4. **安全性**- Relay 仅做流量转发，不解密 SSH 内容（端到端加密）
- 设备注册使用一次性 token 或设备证书
- 可选：E2E 加密层（在 SSH 之外再包一层，防 relay 被攻破）
- 支持 mTLS 认证 Relay ↔ Agent 通道

### 1. SSH 连接管理

- 支持 SSH 密钥认证（Ed25519、RSA）和密码认证
- Host 列表管理：IP/域名、端口、用户名、密钥绑定
- 连接配置导入（支持 `~/.ssh/config` 格式）
- 连接状态保活与自动重连
- 支持跳板机 / ProxyJump

### 2. 终端模拟器

- 完整的终端模拟（xterm-256color）
- 支持 Unicode、中文显示
- 自适应屏幕尺寸与横竖屏切换
- 可调字体大小、配色方案
- 滚动缓冲区与历史搜索

### 3. tmux 深度集成（关键差异化）

- 自动检测并列出远程 tmux session/window/pane
- 一键 attach 到指定 tmux session
- tmux pane 切换手势（左右滑动切换 pane/window）
- 显示 tmux 状态栏信息

### 4. AI Agent 交互优化（核心卖点）

- **智能输入栏**：针对 Claude Code / Codex 的交互模式优化- 多行输入支持（AI prompt 通常较长）
- 常用指令快捷面板（如 `/compact`、`/clear`、`yes`、`no` 等）
- 输入历史与模板管理
- **输出阅读优化**：- 代码块高亮渲染（Markdown / diff 格式识别）
- 长输出折叠与展开
- 关键内容锚点（如 "Changes applied"、"Error" 等标记）
- **状态感知**：- 识别 AI Agent 的运行状态（思考中 / 等待输入 / 执行完成）
- 后台通知：Agent 完成任务时推送通知

### 5. 移动端交互体验

- 可自定义的快捷键工具栏（Tab、Ctrl、Esc、方向键等）
- 手势操作：双指缩放字体、长按选中复制
- 触觉反馈
- 深色/浅色主题
- 分屏模式支持（平板端）

### 6. 安全与同步

- 密钥存储加密（Android Keystore）
- 可选的生物识别解锁
- 主机配置云同步（可选，E2E 加密）

## 技术选型建议

| 层面 | 方案 |
| --- | --- |
| 开发框架 | Kotlin（Android）→ 后续 KMP 或 Flutter 扩展 iOS |
| 网络桥接 | AWS API Gateway (WSS) + Lambda + ECS Fargate + NLB + DynamoDB |
| SSH 协议 | 基于 libssh2 或 Apache MINA SSHD |
| 终端模拟 | 基于 [Termux/terminal-emulator](https://github.com/termux/termux-app) 或自研 VT100/xterm 解析 |
| 网络层 | 支持 Mosh（UDP）作为备选，改善弱网体验 |
| 本地存储 | Room + EncryptedSharedPreferences |
| Relay 部署 | API Gateway (WSS) + Lambda + ECS Fargate + NLB + DynamoDB |

## MVP 范围（v0.1）

1. ✅ SSH 密钥连接 EC2 / MacBook
2. ✅ 基础终端模拟（xterm-256color）
3. ✅ tmux session 自动检测与 attach
4. ✅ 移动端快捷键工具栏
5. ✅ AI Agent 常用指令快捷面板
6. ✅ 深色主题
7. ✅ Relay Server + 设备 Agent（基础反向隧道连通）

## 后续迭代

- v0.2：Mosh 支持、输出高亮渲染、通知推送
- v0.3：iOS 版本、云同步
- v1.0：AI Agent 状态感知、智能摘要

## 竞品对比

| 特性 | Termius | 本产品 |
| --- | --- | --- |
| SSH/SFTP | ✅ 全功能 | ✅ SSH 为主 |
| tmux 集成 | ❌ 无特殊优化 | ✅ 深度集成 |
| AI Agent 适配 | ❌ 无 | ✅ 核心差异点 |
| 定价 | $10/月（Pro） | TBD |
| 目标场景 | 通用运维 | AI 编程助手移动操控 |

