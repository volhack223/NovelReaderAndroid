# 竖屏小说阅读器 Android 版（NovelReader Android）

专为安卓手机竖屏阅读 TXT 小说打造的原生应用，内置本地图片代理与缓存，支持直接显示 `[图片: https://images.novelpia.com/..._ori.file]` 等内嵌插图。

## 主要特性
- **原生文件导入**：通过系统文件选择器导入任意 `.txt` 小说，自动识别 UTF-8 / GBK / GB18030 / Big5 / UTF-16 编码，告别乱码。
- **Novelpia `.file` 插图原生渲染**：内置 Kotlin 图片拦截与磁盘缓存，自动附带 `Referer` 并通过文件头魔数识别 WebP/PNG/JPG/GIF/AVIF，点击插图可全屏放大。
- **竖屏沉浸阅读**：5 套护眼主题（纸墨、护眼绿、纯净白、夜灰、纯黑 OLED）、字体/字号/行距/段距/边距/首行缩进可调、音量键翻页、阅读进度自动保存、自动分章与目录搜索。

---

## 生成 APK 的两种方式

### 方式一：GitHub 云端自动打包（推荐，电脑无需安装 Android Studio）
1. 在 GitHub 上新建一个仓库（公开或私有均可）。
2. 将本压缩包解压后的所有文件（包含 `.github` 文件夹）上传到该仓库。
3. 打开仓库顶部的 **Actions** 页面，点击左侧的 **Build Android APK**，再点击右侧 **Run workflow**（或推送代码后会自动触发）。
4. 等待约 2 分钟构建完成后，点击本次构建记录，在底部 **Artifacts** 中下载 `NovelReader-APK`，解压后即可得到可直接安装到安卓手机的 `app-debug.apk`。

### 方式二：使用 Android Studio 本地打包
1. 下载并安装 [Android Studio](https://developer.android.com/studio)。
2. 选择 **Open**，打开解压后的 `NovelReaderAndroid` 文件夹，等待 Gradle 同步完成。
3. 点击顶部菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
4. 构建完成后在 `app/build/outputs/apk/debug/app-debug.apk` 找到安装包，传到手机安装即可。
