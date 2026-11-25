# P0 / P1 对齐 FongMi 源 — 完成状态

面向常用 TVBox/FongMi 订阅（CMS + Guard JAR + 少量 JS）。

## P0（必须一致）

| 项 | 状态 | 实现要点 |
|----|------|----------|
| CMS type 0/1/4 | **完成** | `CmsSiteClient` + `XmlCmsParser`；type4 extend/form |
| JAR + `spider;md5` | **完成** | `PluginArtifact` / `JarPrefetch` / `JarSpiderLoader` |
| jpg/png 伪装 jar | **完成** | `JarBytes.materialize`（纯 PK 或 JPEG 尾挂 zip） |
| 配置解密 2423 / JPEG 尾 | **完成** | `ConfigDecoder` + `Http.text` |
| 中文域名 | **完成** | `Urls.ascii` punycode |
| Guard 系 init | **完成（持续兼容）** | `JarSpiderLoader` Init/二次 dex/native 预检 |
| 本机 proxy + Range | **完成** | `SpiderProxyServer` 请求/响应头透传 |
| 搜索 searchable | **完成** | `AppRepository` 过滤 `searchable` |

## P1（强烈建议）

| 项 | 状态 | 实现要点 |
|----|------|----------|
| 站点 `ext` 对象/数组 | **完成** | `Site.readExt` 序列化 |
| `header` / `headers` | **完成** | 单复数、字符串 JSON |
| `filterable` / `filter` | **完成** | 双键 |
| 站点 `timeout` | **完成** | 秒→毫秒；CMS `callTimeoutMs` |
| 站点级 `jar` | **完成** | `fromJson` 覆盖全局 spider |
| 真实 `.js` 源 | **部分** | `JsSpider` 最小宿主（request/md5/base64/async）；复杂 drpy 仍可能失败 |
| 加密订阅入口体验 | **完成** | 解密失败明确提示；需可用镜像 |
| jar 预取重试 / 探活 | **完成** | `JarPrefetch`、`SiteHealth` |

## 非本轮（P2）

- Python / Chaquopy
- Node / CatPaw 完整 worker
- 与 FongMi 模块热更新一致

## 建议真机验收清单

1. 导入 `szyyds.cn/tv/x.json` → jar Ready → 任选 CMS + 一 Guard 站 home→detail→play  
2. 导入肥猫（2423）→ 解密成功 → spider md5 通过  
3. 导入 newwex → 全 csp_Guard 列表可点开（个别加壳站允许失败）  
4. 带 `searchable:0` 的站不出现在聚合搜索  
5. 播放网盘/插件 proxy 地址可拖进度（Range）  
