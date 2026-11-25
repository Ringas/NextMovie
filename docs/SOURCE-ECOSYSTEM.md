# 源生态目标与实现状态

> 对应产品目标：CMS、Java JAR、QuickJS/JS、Python、CatPaw(Node)、网盘代理、纯数据适配器。

## 总览

| 能力 | 状态 | 入口 | 说明 |
|------|------|------|------|
| CMS JSON/XML | **已实现** | `CmsSiteClient` / `XmlCmsParser` | type 0/1/4 |
| Java JAR `csp_*` | **已实现** | `JarSpiderLoader` + `:spider` 进程 | Guard 持续打磨 |
| QuickJS / JS | **已实现（最小集）** | `JsSpider` | 同步 Spider API；复杂异步仍弱 |
| Python | **骨架** | `source/python/PythonEngine` | 默认关闭，待 Chaquopy 评估 |
| CatPaw / Node | **骨架** | `source/catpaw/CatPawEngine` | 待本机 worker |
| 网盘代理 | **部分实现** | 插件扫码 + `SpiderProxyServer` | 夸克链路已验证 |
| 纯数据适配器 | **骨架 + 注册表** | `source/adapter/AdapterEngine` | 按站点注册，默认空 |

统一路由：`com.nextmovie.core.source.SourceRouter`  
能力矩阵：`SourceRouter.capabilityMatrix()`

## 架构原则

1. **Android TV 优先**：能跑 JAR 就不要为跨平台去写几十套纯适配器（与 Marco 非 Android 策略不同）。
2. **进程隔离**：第三方字节码 / native 只在 `:spider`；Python/Node 若启用也必须子进程。
3. **不内置源、不内置网盘账号**：配置与授权均由用户导入 / 扫码。
4. **失败要可读**：未启用的运行时抛中文 `SourceException`，不崩溃。

## 推荐落地顺序

### Phase A — 巩固已有（进行中）
- JAR 兼容、代理 Range、源健康探活、插件下载重试
- JS 补强（状态码、更多异步场景）

### Phase B — 网盘体验
- 抽象「插件账户会话」状态到设置页
- 代理协议更多变体、失败自动换线提示
- 夸克之外按用户真实插件逐步验证

### Phase C — 纯数据适配器
- 为高频、规则稳定的 `XBPQ` / `XPath` 类站点注册适配器
- 仅当 JAR 无法在目标设备加载时作为兜底

### Phase D — Python（谨慎）
- Chaquopy 包体 / 1–2GB 盒子内存试点
- 独立进程 + 超时杀进程
- 通过后再改 `BuildFlags.PYTHON_ENABLED`

### Phase E — CatPaw
- 服务包 MD5 校验、回环端口、与播放器对接
- 评估是否用轻量替代方案（规则抽取到 Adapter）

## 代码位置

```
core/source/
  SourceKind.kt          # 七类枚举 + 识别
  SourceEngine.kt        # 统一接口
  SourceRouter.kt        # 分发与能力矩阵
  python/PythonEngine.kt
  catpaw/CatPawEngine.kt
  adapter/AdapterEngine.kt
  netdisk/NetdiskProxy.kt
```

现有生产路径仍以 `AppRepository` → CMS / `SpiderHost` 为主；新运行时先挂 Router，再逐步委托，避免一次大爆炸重构。
