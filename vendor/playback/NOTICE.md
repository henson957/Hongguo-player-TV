# 播放取址与预加载适配

本地修改日期：2026-11-07，GPL-3.0-only。

BatchPlaybackResolver.kt 按 tsukiyo-zero/hongguoTV 当前公开实现中的请求字段、设备身份一致性、整轮截止时间与签名要求独立实现。请求方式包含 POST /novel/player/multi_video_model/v1/ 和 GET /reading/bookapi/video_tab/multi_video_model/v1/，只按请求中的分集 ID 接受返回数据。后续模型缓存按剧和会话隔离，最多 16 份，每份最多 256 KiB。生产环境的成功率未经本地在线验证。

- ContentRepository.kt 参考 Git blob：5338c421a4d15dfbd1489f99ee92f59a70c05d91
- PlaybackSigner.kt 改写自上游 Signer.kt，Git blob：594c32f24831b63f1ee14dd7bed3ad1272a6a3c2；未改动原账户接口所用的 Signer.kt。
- RequestScope.kt 截止时间与取消检查参考上游 Git blob：0a7414d4455469605f28f0217cde987b9cef8ae1，保留现有核心所需的方法。
- 来源：https://github.com/tsukiyo-zero/hongguoTV/tree/main/kotlin-tv/core/src/main/kotlin/com/hongguotv/core

yangbin1322/hongguo-player v1.2.24 发布说明提供了最多 15 集批量取址的设计参考。对其公开 Windows 包前端进行静态核对，观察到播放器建立后即发起后续四集的预取，另准备下一集媒体元素。Go 后端未反编译，不声称已核对其完整后端算法。本地工程没有复制或打包该项目的 EXE、网页资源或未公开后台代码。

Android 端使用 AndroidX Media3 1.8.1 自带的 DefaultPreloadManager 提前加载下一集前 8 秒，然后把同一 MediaSource 交给同一个播放器的队列。构建器按官方 API 共用播放器和预加载器所需组件。此集成已编译，但没有电视运行验证。

https://github.com/yangbin1322/hongguo-player/releases/tag/v1.2.24
https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/manage-play

2026-11-12，参考下列公开的 551 播放协议说明和固定向量，使用本项目既有 Kotlin 签名工具实现播放请求兼容更新。账号签名路径保持原样。

- https://github.com/aston314/hongguo-tv/pull/42
- https://github.com/aston314/hongguo-tv/blob/1cb6c030e0c5/core/cmd/hongguotv/testdata/playback-sign-vectors.json
- https://github.com/zfdang/hongguo-open-player/pull/92
