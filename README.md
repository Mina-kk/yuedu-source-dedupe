# 阅读书源去重

一款轻量、原生的 Android 阅读书源整理工具，用于合并多个书源 JSON，并按规则识别和移除重复项。

> 本项目是独立工具，不隶属于「阅读」或任何书源网站。请仅处理你有权使用的书源数据，并遵守相关网站的服务条款。

## 2.1.0 大数据优化

- 后台线程处理本地导入、解析与导出，避免主线程卡死
- 增量去重，边解析边保留最优书源
- 重复/错误详情设置内存上限，默认仅保留前 500 条详情
- 导出与“导入到阅读”改为流式写入，降低内存峰值
- 延迟创建 YCK WebView，减少启动内存占用

## 功能

- 导入一个或多个本地 JSON 文件
- 从多个 JSON URL 下载并合并书源
- 标准、严格、激进三种去重模式
- 可选清理书源名称中的装饰字符
- 展示重复项与无效项统计
- 保存整理后的 JSON
- 通过 Android 分享/打开方式导入到支持 JSON 的阅读应用
- 内置 YCK 页面入口，可收集受支持的书源链接
- 支持 1～5 个并发下载任务，可中止并保留已完成结果

## 系统要求

- Android 5.0（API 21）或更高版本
- 构建目标：Android API 34
- JDK 17（JDK 8 及以上通常也可用于当前源码）
- Android SDK Platform 34
- Android SDK Build Tools 34.0.0

## 下载与安装

预编译 APK 将发布在 GitHub 仓库的 **Releases** 页面。安装第三方 APK 前，请自行核对发布页提供的 SHA-256 摘要。

Android 可能提示允许浏览器或文件管理器“安装未知应用”，请根据系统提示操作。

## 使用方法

1. 打开应用，添加本地 JSON 文件或填写书源 JSON URL。
2. 选择去重模式：
   - **标准**：适合大多数情况。
   - **严格**：尽量减少误判。
   - **激进**：更积极地合并相似来源，建议导出前检查结果。
3. 根据需要设置网络并发数和名称清理选项。
4. 开始解析并查看重复、无效及保留数量。
5. 保存 JSON，或使用“导入到阅读”交给设备上的兼容应用处理。

建议在操作前备份原始书源。不同去重模式可能产生不同结果，请在导入前检查导出文件。

## 从源码构建

设置 Android SDK 环境变量：

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
```

确保以下文件存在：

```text
$ANDROID_SDK_ROOT/platforms/android-34/android.jar
$ANDROID_SDK_ROOT/build-tools/34.0.0/aapt
$ANDROID_SDK_ROOT/build-tools/34.0.0/d8
$ANDROID_SDK_ROOT/build-tools/34.0.0/zipalign
$ANDROID_SDK_ROOT/build-tools/34.0.0/apksigner
```

然后执行：

```bash
chmod +x build_apk.sh run_tests.sh
./run_tests.sh
./build_apk.sh
```

默认使用 `$HOME/.android/debug.keystore` 生成开发测试包；若文件不存在，脚本会创建 Android 调试密钥。产物位于：

```text
dist/yuedu.apk
```

调试签名只适合本地测试。正式发布应使用发布者自己的私有签名密钥，并妥善保管；不要把密钥或密码提交到仓库。

## 项目结构

```text
src/                         AndroidManifest 与 Java 源码
res/                         图标、字符串和 FileProvider 配置
tests/                       不依赖 Android 运行时的核心测试
run_tests.sh                 JVM 测试脚本
build_apk.sh                 基于 Android SDK 命令行工具的构建脚本
docs-native-regression-checklist.md  手工回归检查清单
```

项目使用原生 Java 和 Android SDK 命令行工具构建，不依赖 Gradle。

## 隐私与网络

- 应用仅声明网络权限。
- 本地书源文件在设备上处理。
- URL 导入会直接请求用户提供的地址。
- 内置网页入口会访问对应第三方站点；第三方站点的内容、可用性和隐私政策不由本项目控制。
- 项目不提供、运营或担保任何第三方代理服务。

## 测试

```bash
./run_tests.sh
```

发布前还应在真实 Android 设备上执行 [`docs-native-regression-checklist.md`](docs-native-regression-checklist.md) 中的检查。

## 参与贡献

欢迎通过 Issue 报告问题或提交 Pull Request。报告问题时建议提供：

- Android 版本与设备型号
- 应用版本
- 可复现步骤
- 已脱敏的示例 JSON 或错误信息

请勿在 Issue 中公开私人书源、Cookie、Token、服务器地址或其他敏感信息。

## 免责声明

本工具只负责整理用户提供的数据，不托管、不维护、不审核任何书源。书源的合法性、安全性、稳定性及内容版权由其提供者和使用者自行负责。使用者应遵守所在地法律法规以及相关服务的使用条款。

## 许可证

本项目采用 [MIT License](LICENSE) 开源。
