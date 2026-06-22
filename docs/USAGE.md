# Remux 使用说明

本文介绍如何从零把 Remux 跑起来：构建三个组件、启动中继（relay）、在开发主机上运行设备代理（agent），并从 Android App 连接到远程 tmux 里的 AI Agent。

> 架构一句话：手机和开发主机都只**主动外连**到一台公网 relay，relay 把两者撮合在一起；真正的 SSH 在隧道内端到端加密运行，relay 看不到会话内容。详见 [`ARCHITECTURE.md`](ARCHITECTURE.md)。

---

## 0. 前置依赖

| 用途 | 依赖 |
|---|---|
| 构建 relay / agent | Go 1.25+ |
| 端到端验证 | `tmux`、`sshd`（openssh-server）、`ssh-keygen` |
| 构建 Android App | JDK 21、Android SDK（platform-35、build-tools 35）；详见 [`ANDROID_BUILD.md`](ANDROID_BUILD.md) |
| AWS 部署（可选） | AWS 凭证、Terraform、Docker；详见 [`../infra/README.md`](../infra/README.md) |

构建后端二进制（注意用 `-o`，否则输出名会和 `relay/`、`agent/` 目录冲突）：

```bash
go build -o bin/relay ./relay
go build -o bin/agent ./agent
```

---

## 方式一：自托管 relay（最简单，推荐先用这个跑通）

适合个人：一台小 VPS（或就放在能被手机访问的机器上）跑 relay，开发主机跑 agent。

### 1. 启动 relay（公网机器上）

```bash
# 设一个强 token，agent 和 App 都要用同一个
export REMUX_RELAY_TOKEN="$(openssl rand -hex 24)"
./bin/relay -addr :8080 -token "$REMUX_RELAY_TOKEN"
# 健康检查： curl http://<relay-host>:8080/healthz  ->  ok
```

> relay 默认监听明文 `ws`。SSH 在隧道内已端到端加密，会话内容安全；但若要隐藏元数据，建议在 relay 前加一层 TLS 反代（如 Caddy/Nginx），App/agent 改用 `wss://`。见 [`SECURITY.md`](SECURITY.md)。

### 2. 在开发主机（EC2 / MacBook）上运行 agent

确保本机 `sshd` 已开启（agent 会把会话转发到 `127.0.0.1:22`）。

```bash
./bin/agent \
  -relay ws://<relay-host>:8080 \
  -device-id my-ec2 \
  -token "$REMUX_RELAY_TOKEN" \
  -local-ssh 127.0.0.1:22
# 日志出现 "registered with relay" 即注册成功
```

也可以用配置文件 `-config agent.json`（命令行参数会覆盖文件）：

```json
{
  "relayUrl": "ws://relay.example.com:8080",
  "deviceId": "my-ec2",
  "name": "EC2 dev box",
  "token": "<同一个 token>",
  "localSsh": "127.0.0.1:22",
  "mode": "relay"
}
```

agent 断线会按指数退避自动重连。把它做成 systemd / launchd 服务即可开机自启。

### 3. 从 App 连接

在 App 里以**自托管（Self-hosted）**模式填入 relay 地址与 token，刷新设备列表选择 `my-ec2` 即可（见下文「Android App 使用」）。

---

## 方式二：部署到 AWS（apigw 模式）

控制面走 API Gateway WebSocket + Lambda + DynamoDB，数据面走 NLB 后的 Fargate relay。完整部署步骤（建 ECR、推镜像、打 Lambda 包、`terraform apply`）见 [`../infra/README.md`](../infra/README.md)。`apply` 后会输出：

- `websocket_url`：`wss://xxxx.execute-api.<region>.amazonaws.com/prod`（App 控制面）
- `nlb_dns_name`：`remux-nlb-xxxx.elb.<region>.amazonaws.com`（数据面，端口 8080）

### agent 以 apigw 模式连接

```bash
./bin/agent -mode apigw \
  -relay  wss://xxxx.execute-api.<region>.amazonaws.com/prod \
  -data-url ws://remux-nlb-xxxx.elb.<region>.amazonaws.com:8080 \
  -device-id my-ec2 -token "<relay_token>" -local-ssh 127.0.0.1:22
```

### App 以 API Gateway 模式连接

设备选择页切到 **API Gateway**，填入 `websocket_url`、数据面 NLB 地址、token。

> 为什么是「控制面 API GW + 数据面 NLB」的混合方案：API Gateway WebSocket 无法高效承载 SSH 字节流（每帧都要过一次 Lambda，且有 128KB 帧上限）。详见 [`PROTOCOL.md`](PROTOCOL.md) 的 *Control-plane modes*。

---

## Android App 使用

构建并安装 Debug APK：

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

App 内的典型流程：

1. **添加 Host**（首页右下角 `+`）：填写标签、主机/IP、端口、用户名；
   - 认证方式选 **Key**（粘贴 Ed25519/RSA 私钥 PEM，加密存于 Android Keystore，**不会明文落盘**）或 **Password**；
   - 连接方式选 **Direct**（直连 host）或 **Via relay**（填设备 ID，经 relay 隧道）。
2. **连接**：点击 Host 卡片即进入终端；状态栏显示 连接中 / 已连接 / 错误 / 断开。
3. **终端**：完整 xterm-256color 渲染（含中文/CJK 宽字符）；双指捏合缩放字号。
4. **快捷键工具栏**：Tab、Esc、方向键、Ctrl（粘滞修饰键，先点 Ctrl 再按字母即发 Ctrl 组合）、常用符号。
5. **AI 指令面板**：`/compact`、`/clear`、`yes`、`no` 等一键插入/发送，配合多行输入框书写较长 prompt。
6. **tmux**：连接后可检测并 attach 远程 tmux 会话。
7. **深色主题**默认开启。

---

## 一键本地验证（无需手机）

仓库自带端到端脚本，会自动起一个私有 `sshd` + relay + agent，并用 Go 客户端跑通真实 SSH + tmux：

```bash
bash scripts/e2e.sh
# 期望输出： "ssh handshake ok over tunnel"、"marker found: REMUX_AI_DONE"、"E2E PASSED"
```

纯逻辑（终端模拟器、协议、解析等）单测：

```bash
go test ./...                 # 后端
cd android && ./gradlew :core:test   # App 的 :core 模块（无需模拟器）
```

---

## 排错

| 现象 | 排查 |
|---|---|
| agent 一直重连 | relay 地址/端口可达？token 是否一致？relay 日志是否有 `bad token`？ |
| App 列不出设备 | agent 是否已 `registered`？App 与 agent 用的是同一 relay 与 token？apigw 模式下 DynamoDB GSI 有几百毫秒传播延迟，稍等重试。 |
| 连上但很快断开（apigw） | API Gateway 空闲约 10 分钟断连；agent 已内置 ping 保活，确认 agent 进程未退出。 |
| SSH 认证失败 | 私钥与远端 `authorized_keys` 是否匹配？用户名是否正确？ |
| `assembleDebug` 在 ARM64 机器上失败 | 见 [`ANDROID_BUILD.md`](ANDROID_BUILD.md)（需要 JDK + qemu + amd64 multiarch 库）。 |
