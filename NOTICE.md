# 红果电视测试版：来源与范围

本应用是为雷鸟雀4制作的独立 Android 电视测试工程，包名 dev.local.hongguotv，非红果官方产品。

- 电视界面参照 yangbin1322/hongguo-player Windows 版公开截图的配色、侧边栏和海报布局重新实现。APK 不包含该 Windows EXE、原版编译后的网页资源或其后台程序。
- 内容与媒体核心来自 https://github.com/ps1001/HGTV 的 core 模块；精确文件 Git blob 哈希见 vendor/hgtv/source-manifest.json。其作者与上游派生说明保留于 vendor/hgtv/NOTICE.md，许可证保留于 vendor/hgtv/LICENSE。
- VideoDataSource.java 按同一项目的 VideoDataSource.kt 改写为 Java，来源为 https://github.com/ps1001/HGTV/blob/main/android/app/src/main/kotlin/com/hgtv/star/player/VideoDataSource.kt。
- 新增独立电视前端、Android 桥接、本机记录、播放控制和构建脚本，修改日期 2026-11-07；派生应用源码按 GPL-3.0-only 提供，见 LICENSE。
- Kotlin 1.9.24、AndroidX Media3 1.8.1、OkHttp 4.12.0、Okio 3.6.0：Apache-2.0；Bouncy Castle 1.79、jsoup 1.21.2：MIT；对应项目许可说明随依赖 JAR 与源码来源保留。
- `pinyin.tsv` 提取自 Unicode 17.0.0 Unihan 的 `kMandarin`、`kHanyuPinyin` 和 `kXHC1983`，已去除声调。采用 Unicode License V3，完整声明随 `app/src/main/assets/licenses.txt` 提供。数据来源见 https://www.unicode.org/Public/17.0.0/ucd/Unihan.zip 。
- 本测试版未测试真实取剧、注册与播放，按用户要求只进行本地编译、签名和离线界面检查。网络与解码效果由用户在电视上验收。

## 0.1.1 本地播放兼容修改（2026-11-07）

新增 PlaybackStreams.kt、修改 ContentRepository.kt 和原生播放接入；增加独立的离线回归检查 PlaybackStreamsCheck.java。
原始上游文件身份仍保留在 source-manifest.json；这些本地修改不代表上游原始版本。
公开网页播放器数据结构参考以下主来源，自行实现 Kotlin 解析，不调用其他第三方解析服务：
- https://github.com/zhoufuweigg/guoapp/blob/3a430336c9a84d8c994e97790dcc3831dd47affe/native/core/provider_hongguo.go
- https://github.com/zhoufuweigg/guoapp/blob/3a430336c9a84d8c994e97790dcc3831dd47affe/native/core/provider_hongguo_native_media.go
- https://github.com/gybeyond1/hongguo-dl/blob/main/docker/hongguo_core.py

## 0.1.2 连播、账号与电视布局（2026-11-07）

加入单集预加载及同一个 Media3 播放器的队列衔接、内存视频前缀复用、取消和资源所有权管理；自动换集不主动显示控制栏或进度条。新增扫码和短信官网登录、Android Keystore 加密保存，以及独立的云端收藏和历史读取入口。账号登录适配来源和精确上游 Git blob 见 vendor/web-login/NOTICE.md。
新增登录与预加载离线回归，修正旧版 WebView 不支持 inset 和 flex gap 时的布局，以及遥控器焦点样式。真实播放、无缝程度和官网登录尚未在线验证。

## 0.2.0-rc1 连播重构、云端写回与评论弹幕（2026-11-07）

新增独立批量取址与签名适配，将媒体预加载交由 Media3 DefaultPreloadManager；保留原账户请求签名器。来源见 vendor/playback/NOTICE.md。
新增按账号隔离的持久化写回队列、收藏/进度回读确认、云端精确进度读取、评论分页/官网剧评和弹幕时间轴。评论读取和签名适配来源见 vendor/community，其通用签名结构的 MIT 声明随 APK 保留于 licenses.txt。
按用户后续指示，移除尚未交付的离线下载实现及相关服务、权限，取消自动更新计划。未完成电视或真实服务验收，因此仍为候选版本。

## 0.2.0-rc2 本机与云端自动合并（2026-11-08）

新增 LibraryState.java，重写 CloudSync.java，并修改 TvApplication、MainActivity、PlayerActivity 和本地电视前端，
完成按验证账号隔离的收藏/历史自动合并、删除标记、真实观看时间比较、修改版本回读确认、重试和界面自动刷新。
修改上游 Account.kt 的账号身份读取，ContentRepository.kt 增加严格的云端列表快照与实际观看时间上传。
原始文件的 Git blob 身份仍保留于 vendor/hgtv/source-manifest.json，不将本地修改冒充上游版本。
评论发布、点赞、删除仍按用户要求不做。本轮按用户要求仅 UI 检查、编译和必要的打包检查，未执行功能测试。

## 0.2.0-rc3 自动选择可播放清晰度（2026-11-08）

新增 PlaybackAttempts.java；修改 PlaybackStreams.kt、BatchPlaybackResolver.kt、PreparedPlayback.java、
PlayerActivity.java、TvApplication.java、MainActivity.java 和本地前端，按分集选择最高兼容档位，
播放/预加载失败后自动降低清晰度并保留播放位置，以内部指纹避免再次选择已失败的相同资源。
保留批量模型缓存和正常连播的播放器队列。按用户最新要求，移除全部手动清晰度设置入口。
原始上游 Git blob 身份继续保留，本地修改遵循原许可证。本轮仅 UI、编译与封装检查，未执行功能测试。

## 0.2.0-rc4 搜索补全与同系列选季（2026-11-08）

新增 TvSearch.java、SearchTitles.kt；修改 ContentRepository.kt 的搜索分组解析与目录补页条件，
并复用原有官方联想词、相关作品及分季解析接口，修正 SeriesSeasons.kt 对括号季号的读取。
主界面接入独立搜索会话，后台补充匹配结果与同系列分季，顶部提供选季入口，保留输入和详情焦点，
并在切换查询、离开界面和进入播放时取消旧请求。没有硬编码真实剧名、搜索结果或季数。
原始上游文件身份继续保留于 source-manifest.json。按用户要求仅检查本地 UI 样例、编译和封装，未验证真实搜索接口。

## 0.2.0-rc5 搜索实测与中间分部序号（2026-11-08）

按用户最新要求执行真实搜索检查，发现分部编号出现在标题中间时未归组，修改 SeriesSeasons.kt 支持此类阿拉伯序号，
保留末尾/括号季号规则，并排除常见年份、年龄和数量单位。使用与 APK 相同的搜索协调器及核心，
在电脑 JVM、空库、无账号、无设备注册条件下，对两个用户提供的关键词均取得并正确归组第 1～5 部。
将真实搜索快照用于 UI 检查，另编译、检查签名与封装。未进行电视、播放、登录或云写入测试。

## 0.2.0-rc6 Android 9 搜索闪退修正（2026-11-08）

修改 SeriesSeasons.kt，将 ICU 60.2 不支持的 IsHan 别名替换为显式字符范围。
修改 TvSearch.java 和 MainActivity.java，处理初始化/链接错误和线程池关闭竞争，保留同一 ID 更完整的分季名。
新增 CrashDiagnostics.java，修改 TvApplication.java，将异常类型和少量代码位置保存在本机最近提示，不存储账号或网络请求参数。
使用官方 ICU 60.2 原生库复现旧规则失败并验证新规则，完成搜索故障注入和关闭竞争检查、真实搜索复测、UI、编译、Lint 和封装检查。
未连接电视或模拟器运行，未测试登录、云写入或播放。旧版 ICU 测试库仅存于开发工作目录，不包含在 APK 或对应源码包中。

## 0.2.0-rc7 新剧集取址自动恢复（2026-11-08）

修改 BatchPlaybackResolver.kt 和 PreparedPlayback.java，为前台取址及下一集预取加入有界重试、
分轮超时、注册身份会话复用、可取消的注册等待及账号变更检查。
播放注册不再等待搜索注册，失败的注册不占用成功注册冷却时间，未注册时不使用上游固定设备 ID。
HTTP 429 不重试或轮换身份；网页缺失信息不覆盖真实格式错误。保持既有自动清晰度与播放器队列。
本轮仅检查源码、离线 UI、编译、Android Lint、签名与封装，未执行功能测试或真实媒体请求。

## 0.2.0-rc8 兼容片源回退（2026-11-09）

调整 BatchPlaybackResolver.kt 和 PlaybackStreams.kt。批量模型不适合播放时继续尝试单集接口，
兼容媒体配置的请求字段参考 guoapp 的 provider_hongguo_native_media.go，来源见上文 0.1.1。
允许多个备用解析地址、切换播放线路，并区分无兼容片源与 HTTP 404。沿用原有编解码和密钥算法。

## 0.2.0-rc10 创作者合集（2026-11-09）

合集目录请求字段参考 openhongguo（https://github.com/beilunyang/openhongguo ，Apache-2.0），
以《我的妹妹不可爱》的公开目录和官方单集分享页核对实际字段。电视版自行实现请求与解析。
官方公开分享页组件的静态分析证据：ev_9e1faf00bad2ce60587a73a347895677af6e1e75af10563d158af3e51dbd8b65。

