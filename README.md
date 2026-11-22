# LanShare · 安卓局域网文件传输

把手机变成一个**局域网文件服务器**：手机连上 WiFi，电脑/平板打开手机界面显示的
`http://192.168.x.x:8080`，就能在浏览器里**浏览、下载手机里的文件，也能直接上传文件到手机**。
不需要装电脑客户端，不经过互联网，速度取决于路由器。

## 功能

- 手机端一键启动 / 停止 HTTP 服务（默认端口 8080）
- 手机端自动显示局域网访问地址（支持多网卡）
- 浏览器端：文件列表、下载、上传（multipart 表单，单文件上限 200MB）
- 手机端“添加文件”：从系统文件选择器挑任意文件放进共享目录
- 共享目录：`/sdcard/Android/data/com.lanshare/files/share`
- 防路径穿越（只允许访问共享目录内的文件）

## 环境要求

- Android 5.0 (API 21) 及以上
- 手机和访问设备在同一个 WiFi / 局域网内

## 构建

```bash
gradle clean assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

如果本地已有 Gradle Wrapper（如 `./gradlew`），也可以直接：

```bash
./gradlew clean assembleDebug
```

### 为什么这个工程特别“好编”

- **零第三方依赖**：HTTP 服务用 JDK 原生 `ServerSocket` 实现，不需要 AndroidX、不需要 NanoHTTPD，构建时没有联网拉包失败的风险
- **零 XML 资源 + 不用 AppCompat**：界面全部代码构建，不会出现 `Theme.AppCompat` / `ic_launcher` 资源缺失导致的启动崩溃
- 不需要 Compose，也就不存在 Kotlin 与 Compose 编译器版本绑定问题

## 使用

1. 安装 APK，打开应用（会自动启动服务）
2. 确认界面显示 `http://192.168.x.x:8080`
3. 电脑浏览器打开该地址，即可上传 / 下载文件

在模拟器里测试时，宿主电脑需要先做端口转发：

```bash
adb forward tcp:8080 tcp:8080
# 然后在电脑浏览器访问 http://127.0.0.1:8080
```

## 技术说明

| 文件 | 说明 |
| --- | --- |
| `MainActivity.kt` | 纯代码构建界面（`android.app.Activity`，无 XML 资源、无 AppCompat），显示 IP、启停服务、添加文件 |
| `LanServer.kt` | 基于 `ServerSocket` 的极简 HTTP 服务，处理列表 / 下载 / 上传 |

接口：

- `GET /`：文件列表页面
- `GET /download?f=<文件名>`：下载文件
- `POST /upload`：上传文件（multipart/form-data）

## 安全提示

- 仅用于同一局域网内、自己设备之间的文件传输
- 服务监听在所有网卡上，同一局域网内的其它人也能访问，请在可信 WiFi 下使用
- 不做公网穿透、不做代理、不监听其它设备的流量

## 开源协议

MIT
