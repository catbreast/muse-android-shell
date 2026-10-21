# Muse Shell — 给云端 AI Agent 用的手机壳

> 本项目由 **Muse**（Meta 的个人 AI agent）设计、编码、构建并在真机上验活——
> 从设计稿到每一行代码到最后装机测试，都是 agent 本人完成，人类只负责拍板和点授权。

一句话：**大脑在云端，手机只装一个壳当手和眼。**

Muse Shell 是一个 Android 端的执行壳：它自己不带任何模型和对话逻辑，只把手机的
能力（看屏幕、点按钮、读文件、开虚拟屏、用 root 干活）封装成一组工具调用，
通过一条长连接交给云端的 AI agent 驱动。agent 在服务端运行（带自己的记忆、
任务和工具生态），手机端只负责忠实执行 + 安全审批。

```
云端 AI agent ──HTTP──▶ server.py（中继/队列） ──长轮询──▶ 手机 BridgeService
                                                              │
                                          ToolDispatcher ─────┼─ files（白名单）
                                                              ├─ a11y（无障碍）
                                                              ├─ shell / android.*（root）
                                                              └─ vscreen core（特权进程）
```

## 它能做什么

- **看**：无障碍控件树（文本树 + 扁平节点列表）、屏幕截图、虚拟屏逐帧画面
- **点**：按文字语义点击、分数坐标（0~1，免疫截图缩放）、绝对坐标；
  每次点击都回读屏幕做对比，返回 `pageChanged` / `newTitle`——点了有没有生效
  不再靠猜
- **OCR 补树**：H5 / 自绘页面在控件树里没有文字时，配套工具 `server/ocrtap.py`
  在服务端把截图 OCR 成带坐标的文字索引，照样「按字点」
- **虚拟屏**：独立 dex 的特权进程（root 经 `app_process` 拉起，uid=0）建
  VirtualDisplay，**不需要 MediaProjection 授权弹窗**，能真实承载其他 App 在
  后台运行，输入走 `IInputManager` 注入；60 秒无请求自动销毁，不留孤儿屏
- **干活**：白名单文件读写、特权 shell、应用管理（启动/强停/清数据/装卸）、
  系统设置读写（带改前改后值）、通知
- **双路特权通道**：有 root 走 su；**没 root 的手机装 Shizuku**（用户启动其服务
  并授权一次）即可用 shell 身份跑命令和虚拟屏——通道模式 auto/su/shizuku 可切换，
  虚拟屏 core 也随当前通道拉起
- **留痕**：每次工具调用写 JSONL 账本，可审计

## 安全模型

- **双令牌**：手机与中继、调用方与中继各用一把令牌；虚拟屏 core 另有独立令牌，
  只监听 127.0.0.1
- **审批卡**：删文件、覆盖文件、root shell、装/卸应用、改系统设置等敏感操作，
  必须在手机上弹卡确认，90 秒超时即拒绝；控制台可勾选「本次会话免审」
- **急停**：控制台开关 + 悬浮球，随时一键冻结除 ping/status 外的全部工具
- **白名单**：文件操作只允许共享存储与应用自有目录

## 构建（无 Gradle，纯手工流水线）

需要 JDK 17+、Android SDK build-tools、一个 android.jar：

```bash
# 准备工具链（或用环境变量 ANDROID_BT / ANDROID_JAR 指定已有路径）
mkdir -p toolchain/android-35
ln -s $ANDROID_SDK/build-tools/35.0.0 toolchain/android-16
cp $ANDROID_SDK/platforms/android-35/android.jar toolchain/android-35/

./app/build.sh        # 首次构建会自动生成本地签名口令 .ks-pass 与 keystore
# 产物：MuseShell.apk（assets 里带虚拟屏 core 的 core.dex）
```

装机可以用任意方式（adb install / 手动安装）。已有旧版在运行时，
`server/push_apk.py` 能通过壳自身的工具通道把新 APK 推送上机自升级。

## 运行

1. 手机装好后打开 App，授予：无障碍、所有文件访问、悬浮窗、忽略电池优化
2. 控制台里填中继地址与令牌，启动服务
3. 服务端跑中继：

```bash
cd server
cp tokens.example.json tokens.json   # 填入自己的两把令牌
python3 server.py                     # 只绑 127.0.0.1:8390，对外请套自己的隧道/反代

# 或者用无服务器中继（推荐）：把 server/worker.js 部署成 Cloudflare Worker
# （需一个 Durable Object 绑定 RELAY + 两个 Secret：PHONE_TOKEN / ADMIN_TOKEN，
#  协议与 server.py 一致；手机端地址填 Worker 的域名即可，调用端见 phone.py）
```

4. 调用方用 `server/phone.py call <tool> '<json>'` 即可驱动手机；
   OCR 按字点击用 `server/ocrtap.py`（需另建 venv 装 rapidocr-onnxruntime）

## 状态

实验性个人项目。在 Android 16 真机上全量验活：语义点击、分数坐标、点后对比、
虚拟屏建屏/启动应用/截图/点按/关屏、OCR 按字点击均实测通过。
设计与取舍记录在 [DESIGN.md](DESIGN.md)。

## 致谢

本项目的若干设计受到以下开源项目的启发，在此表示感谢：

- [woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app) —— 特权进程虚拟屏与无障碍工具层设计的启发
- [google/artemis](https://github.com/google/artemis) —— 控件树与 OCR 融合索引思路的启发

## License

MIT
