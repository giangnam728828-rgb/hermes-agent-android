# Hermes Agent · Android

把 [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent) 装进一个可安装的
Android App：内置 Ubuntu 根环境 + Python 3.12 + Hermes 0.19.0，手机本地跑，数据不出设备。

> 与 [kimi-code-android](https://github.com/giangnam728828-rgb/kimi-code-android) 完全独立：
> 包名 `ai.hermes.agent`、目录、端口、根文件系统、签名互不共用，可同时安装。

## 它长什么样

- **启动页**：品牌标 + 状态卡（运行中 / 正在启动 / 失败）+ 端口 + 运行日志 + 进入工作台。
- **工作台**：Hermes 自带的 Web 面板全屏承载，聊天、会话、技能、定时、MCP、配置、密钥
  都在里面，功能与桌面版一致。
- **设置**：端口、工作目录、局域网访问、全自主执行、保持唤醒、导出日志、重装运行环境。

## 安装

从 [Releases](https://github.com/giangnam728828-rgb/hermes-agent-android/releases/tag/runtime-v1)
下载 APK 直接安装（arm64，Android 8.0+）。

首次启动会把 94 MB 的运行环境解压到私有目录，约两三分钟；之后每次启动只等 Python 预热。

## 首次配置

1. 打开 App →「进入工作台」。
2. 面板左侧进 **KEYS**，填你要用的模型服务的 API Key（OpenAI / OpenRouter / Moonshot /
   Nous Portal 等）。
3. 进 **MODELS** 选默认模型。
4. 回到 **CHAT** 开始对话。

Hermes 的技能、记忆、定时任务都落在 `/root/.hermes`（App 私有目录），卸载才会清。

## 让 Agent 动手机上的文件

设置 →「工作目录」改成 `/storage/emulated/0/HermesAgent`（默认值）。proot 会把
`/storage/emulated/0` 整个绑进容器，Agent 读写的就是你手机里的真实文件，文件管理器里能直接看到。

Node / Python 项目可以在容器内构建（镜像自带 Node 未装，需要时 `apt-get install nodejs`）。

## 架构

```
MainActivity ──► RuntimeService ──► proot (arm64)
   │                  │                 └─ /opt/hermes/start.sh
   │                  │                       └─ python3.12 -m hermes_cli.main dashboard
   └─ WebView ◄───────┴──── http://127.0.0.1:9119/  (FastAPI + 预构建 SPA)
```

- `proot` 用户态运行，无需 root；`targetSdk=28` 是为了保留「私有目录可执行二进制」的旧规则。
- 端口固定，换端口会改变 WebView 的 origin，网页端的 localStorage（登录、会话）会看起来清空。
- 停机先杀整棵进程树，避免孤儿 Python 进程继续占着端口。

## 构建

```bash
cd android
./gradlew :app:assembleRelease
```

CI 在 `ubuntu-24.04-arm` 上用 chroot 真跑一遍面板，再在 x86 runner 上打 APK 并发布到
`runtime-v1` release。

## 目录

```
android/app/src/main/assets/hermes-rootfs-arm64.tar.gz   内置运行环境（94 MB）
android/app/src/main/assets/proot/                        proot + loader + libtalloc
android/app/src/main/java/ai/hermes/agent/                App 源码（纯 framework，无 androidx）
```
