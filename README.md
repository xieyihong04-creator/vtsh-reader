# 衛道速覽 · VTSH Reader

> 臺中市私立衛道高級中學（https://www.vtsh.tc.edu.tw/）的非官方 Android 阅读器。
> 液态玻璃 UI，把官网的公告、校历、附件 PDF 变成一部真正好读的手机 App。

** unofficial fan project **。本 App 不隶属校方，只读取官网公开的 RSS / 页面 / 附件。

## 功能

- **公告阅读器** — 13 个官方分类频道（行政公告 / 招生 / 营队 / 荣誉榜 / 活动竞赛 / 升学 / 奖助学金 / 教师研习 / 卫教 / 来文 / 新生 / 总务），原生重排正文、附件；关键词全文搜索。
- **学校行事历** — 官网把整学期校历塞在一个 PDF 里；本 App 在设备端下载并用 PDFBox 现场解析成结构化事件，按「今天 / 未来一周 / 未来一个月 / 更多」时间线呈现，可按部门（教学、体育、辅导…）筛选。学校换学期只换 PDF，App **不需要更新**就能跟到。
- **PDF 附件预览** — 公告里的 Word 导出 PDF 用系统 `PdfRenderer` 直接渲染，可放大看表格小字。
- **桌面小工具** — 今日公告 / 缴费提醒两款 Widget，点击深链直达 App 内的公告。
- **液态玻璃 + 极光背景** — 程序化极光场景，卡片按窗口位置重采样；三档性能（折射 / 轻玻璃 / 省电）。
- **减弱动效** — 一屏内所有动画（交错入场、选中滑块、按弹、展开）都过 `reduceMotion` 这道闸；开启后时长归零、界面瞬达终态。同时尊重系统 `prefers-reduced-motion`。

## 技术栈

- Kotlin 2.0.21 · Compose BOM 2024.10.01 · Material 3
- AGP 8.7.3 · Gradle 8.10 · R8 minify + shrinkResources
- OkHttp 4.12 · PDFBox-Android 2.0.27（行事历运行时解析）
- minSdk 29 (Android 10) · targetSdk 35

## 构建

```bash
# 1. 放好 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. （仅发新版需要签名时）建 keystore.properties
#    并放同名 .jks 到项目根；两者均在 .gitignore 里，不会误提交
cat > keystore.properties <<'EOF'
storeFile=vtsh-reader.jks
storePassword=<你的口令>
keyAlias=<别名>
keyPassword=<别名口令>
EOF

# 3. 构建
./gradlew assembleRelease          # 有 keystore.properties → 签名 release APK
./gradlew assembleDebug            # 无需签名，日常开发
```

没有 `keystore.properties` 时 `assembleRelease` 依旧能跑，只是产物未签名；源码公开可复现构建。

## 项目结构

```
app/src/main/kotlin/tw/vtsh/reader/
  Home.kt             主画面 · 频道 chips · 交错入场 · 搜索
  ArticleView.kt      公告正文原生视图（附件、内嵌 PDF）
  CalendarRepo.kt     行事历运行时解析（PDFBox 版面坐标 + 星期定年）
  CalendarScreen.kt   行事历 UI（时间线 + 部门筛选）
  PdfStore.kt / PdfViewer.kt   附件 PDF 下载与渲染
  MonthRepo.kt        单日归档（供 Widget 使用）
  Rss.kt              WordPress feed 拉取与解析
  NetClient.kt        官网 TLS 中间证书补链
  Motion.kt           交错入场 / 选中滑块 / 按弹 等交互原语
  Glass.kt / Aurora.kt  液态玻璃与极光场景绘制
  Widget*.kt          今日公告、缴费提醒桌面小工具
  Prefs.kt            SharedPreferences + Compose 可观察
```

## 已知细节

- 官网 TLS 链不完整（只送叶子证书）。App 用打包在 `assets/trust_intermediate.pem` 里的中华电信 GCC R46 OV 中间证书**补链**，仍走系统默认 TrustManager 完整校验，**不**跳过证书验证、**不**做证书固定。
- 学校官网的 WordPress REST API 被 WAF 拦截，只能用公开的 RSS feed / 页面 / 日期归档。日期归档对「空期间」返回 HTTP 404——`HttpStatusException` 用来把「空」与「失败」分开。
- PDFBox 传递依赖里的 BouncyCastle 后量子加密数据表 ≈ 8 MB，我们只抽文本、用不到；已在 `packagingOptions` 里剥除。APK 因此从 8.9 MB 减到 5.2 MB。

## License

MIT（见 [LICENSE](LICENSE)）。本仓库内容按原样提供，仅供学习。卫道中学与其 logo / 名称的版权属于校方；对本 App 的使用不承担任何官方责任。
