# Muse 壳（Android）设计 v0.1

> 进度（2026-10-21）：一期 + 二期（虚拟屏）+ 三期（root shell 与结构化操作）全部建成并真机验活，当前版本 v0.2.0。虚拟屏实现与原稿有出入：弃用独立 root 服务端进程，改走 App 内 MediaProjection 公开 API 建屏（每次建屏用户点一次系统授权），root 仅用于 am start --display 启动与 input -d 注入；实测 display 3 建屏、启动设置、截图回传、点按导航全部成功。
>
> 进度（2026-10-21 晚）：**v0.3.0 已按 DSH 设计升级并真机验活**。① a11y.tap 支持 text 语义点击（原有）之外新增 fx/fy 分数坐标，且所有 tap 返回 pageChanged/newTitle 点后对比；新增 a11y.nodes 扁平节点列表（index/text/desc/中心坐标/clickable/depth）。② 虚拟屏回归原稿的特权进程路线：新增独立 dex 的 core（core/src/app/museshell/vscreen/，指纹 muse-core-1），以 root 经 app_process 拉起（uid=0），HTTP 127.0.0.1:8399，建 VirtualDisplay（PUBLIC|PRESENTATION|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|ROTATES|DESTROY_ON_REMOVAL|TRUSTED|ALWAYS_UNLOCKED）、IInputManager 反射注入输入、60 秒无请求自毁；App 侧 VScreenCore 负责释放 assets/core.dex、指纹不符杀旧重拉，VScreenManager 改为代理 + 帧泵，工具面不变，MediaProjection 授权彻底不再需要。验活：语义点「目标」pageChanged=true、分数坐标点开 FLAI、虚拟屏 display 6 建屏零弹窗、启动设置渲染完整、注入点按进子页、BACK、关屏全过。装机通道：server/push_apk.py（base64 分块 files.write → cp /data/local/tmp → pm install -r；注意 /sdcard 路径 pm 装不了，SELinux 拒绝）。
>
> 进度（2026-10-21 晚，v0.4.0 沙盒侧组件，App 不用重装）：**OCR 补树**（借鉴 google/artemis 的 XML+OCR 融合索引）。RapidOCR 装在 server/ocr-venv（onnxruntime 本地推理，中文置信度 0.99+）；新工具 server/ocrtap.py（须用 ocr-venv 的 python 跑）：`index [--vscreen]` 输出全屏文字块索引（文字/置信度/已换算到目标屏真实像素的点击坐标），`tap <文字> [--vscreen] [--idx N] [--dry]` 包含匹配+相似度找字点击、主屏走 a11y.tap 自带 pageChanged 验收，`file <图>` 离线调试。真机验活：拼多多个人中心页 52 个块全识别，按字点「优惠券」pageChanged=true 后已返回。自此定位三级齐全：控件树语义点 → OCR 按字点 → 裸坐标。

> 目标：一个装在一加 13 上的 Android 壳应用，效果对标 DeepSeek Harness 手机版，
> 但大脑是用户现有的 Muse（沙盒运行时，带记忆/目标/全部基建），
> 壳只做手和眼：虚拟屏、文件、无障碍、特权通道全部以工具形式供 Muse 远程调用。
> 状态：设计稿，待用户确认点拍板后才开工。

## 1. 总体架构

```
一加 13（Android 16，ReSukiSU root）
└── Muse 壳 App（Kotlin，包名待定，建议 app.museshell）
    ├── 控制台（状态 / 权限 / 工具开关 / 日志 / 急停）
    ├── 前台服务（保活 + 任务通知 + 悬浮球）
    ├── 工具桥 Bridge（对外只开一条加密长连接，见 §3）
    └── 工具实现
        ├── files        文件读写（存储权限分级）
        ├── a11y         无障碍读屏/点击/输入/滚动/截图
        ├── vscreen      虚拟屏（建屏/启 App/看屏/点滑键/关屏）+ H.264 悬浮预览
        ├── su/shizuku   特权 shell（root 优先，无 root 降级 Shizuku）
        ├── android      结构化系统操作（pm/settings/截图/输入）
        └── notify       AI 发通知 / 请求用户审批（审批卡）
            ▲
            │ WSS 长连接（App 主动外连，断线重连）
            ▼
沙盒 Muse 运行时 ── 工具调用 ── 结果回传（含效果证据，见 §5）
```

大脑不进 APK 的理由：用户的 Muse 带长期记忆、目标、cron、全部连接器，
这些都在沙盒侧；壳只负责设备能力，换手机/重装不丢大脑。

## 2. 权限阶梯（借鉴 DSH 手机版）

| 级别 | 通道 | 能做什么 | 一加 13 现状 |
|---|---|---|---|
| L0 | 所有文件访问 | 文件读写、预览、编辑 | 授权即用 |
| L1 | 无障碍服务 | 读控件树、点击、输入、滚动、截图理解 | 系统设置里开启一次 |
| L2 | Shizuku | shell 级系统操作 | 备用 |
| L3 | root（su） | 全部系统操作 + 虚拟屏服务端 | 已有 ReSukiSU，走这级 |

- 虚拟屏服务端必须以 shell/root 身份运行（建屏、注
...[truncated 3419 chars]