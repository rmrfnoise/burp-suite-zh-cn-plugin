# Burp Suite 中文界面插件（burp-suite-zh-cn-plugin）

一个 **Montoya API 扩展**，把 Burp Suite 界面上的英文替换成中文。

- 当前版本：`1.8.0`
- 直接下载：[dist/burp-suite-zh-cn-plugin-1.8.0.jar](dist/burp-suite-zh-cn-plugin-1.8.0.jar)
- 编译目标：Java 17，基于 Montoya API 2024.7
- 内置词库：2400 条词条 + 45 条正则规则，运行时编辑并热重载
- 许可证：[MIT](LICENSE)

> 这是个人维护的非官方项目，与 PortSwigger Ltd. 没有隶属或背书关系。
> "Burp" / "Burp Suite" 是 PortSwigger Ltd. 的商标；使用本扩展仍受你的 Burp Suite 许可协议约束。

---

## 一、直接使用

1. 下载 `dist/burp-suite-zh-cn-plugin-1.8.0.jar`。
2. Burp → **Extensions** → **Installed** → **Add**。
3. `Extension type` 选 **Java**，`Extension file` 选这个 jar，点 **Next**。
4. 在 **Output** 里应看到：

```text
[burp-suite-zh-cn-plugin] build 1.8.0+20261023-2042
[burp-suite-zh-cn-plugin] AWT listeners on bus: 1 (1 = clean)
[burp-suite-zh-cn-plugin] dictionary: 2400 entries / 45 rules <- C:\Users\<你>\.burp-i18n\dict.json
```

`AWT listeners on bus` 正常是 **1**。大于 1 说明进程里叠了旧实例，完整退出 Burp 再启动。

卸载：**Extensions → Installed** → 选中扩展 → **Remove**。
插件会停掉定时器、摘掉 AWT 钩子和组件监听、还原表格渲染器。
已经写进组件的中文会随 Burp 重绘/重启恢复，字体覆盖也在重启后恢复。

---

## 二、词库

首次加载时，jar 内置的 `dict.json` 会复制到：

```text
C:\Users\<用户名>\.burp-i18n\dict.json
```

之后所有翻译都读这个文件。改完保存，插件在 **8 秒内**自动重载并重扫界面。

### 结构

```json
{
  "config": {
    "version": 24,
    "translateHtml": true,
    "logMissing": true,
    "deepScan": true,
    "translateCells": true,
    "applyFont": false,
    "fontFamily": "",
    "resweepSeconds": 300
  },
  "ignore": ["Burp", "Repeater", "HTTP"],
  "groups": {
    "通用": { "OK": "确定", "Cancel": "取消" }
  },
  "map": { "Drop": "丢弃" },
  "scopes": {
    "SomeBurpPanel": { "Scope": "作用域" }
  },
  "patterns": [
    { "regex": "^Memory: ([\\d.]+)MB of ([\\d.]+)GB$", "replacement": "内存：$1MB / $2GB" }
  ],
  "ignorePatterns": ["^https?://"]
}
```

| 字段 | 作用 |
|---|---|
| `groups` / `map` | `英文: 中文` 映射；重复键后者覆盖前者 |
| `ignore` | 白名单：这些词永远不翻 |
| `scopes` | 按祖先组件类名限定译法，用于一词多义 |
| `patterns` | 正则规则，处理 `Memory: 192.7MB of 3.97GB` 这类动态文本 |
| `ignorePatterns` | 命中即视为数据（字符集、URL、IPv4、日期、哈希、文件大小），不翻也不记日志 |

匹配优先级：`scopes[祖先类名][原文]` → `groups/map[原文]` → 正则 → 保持英文。
查不到就原样保留，**不做半句替换或猜测翻译**。

### 补词条

1. 正常用 Burp，看到没翻译的地方。
2. 打开 `~/.burp-i18n/missing.txt`，一行一条未命中英文。
3. 挑需要的复制进 `dict.json` 的 `groups`，填中文，保存。
4. 8 秒内界面自动更新。

`config.version` 是词库版本号。插件启动时会比较 jar 内置版本和磁盘版本：
内置更高时备份旧文件并替换；相同或磁盘更高时不动你的文件。
想锁住自己的改动，把 `config.version` 改成 `9999`。

---

## 三、隐私与数据

- 插件**不联网、无遥测、不上报**。
- 本地数据都在 `~/.burp-i18n/`：词库、`missing.txt`、升级备份。
- `missing.txt` 是未命中英文，**可能包含主机名、URL、自定义字段等测试现场信息**。
  分享前请脱敏；不想生成就设置 `"logMissing": false`。
- 删除本地数据：关闭 Burp 后删除 `~/.burp-i18n/` 整个目录。

---

## 四、已知边界

| 不翻译的东西 | 原因 |
|---|---|
| 启动向导（选项目 / 选配置 / Starting project） | 出现在扩展加载之前 |
| 可编辑输入框 / 文本域 | 里面是用户数据：报文、搜索词、配置值 |
| 下拉框选项、表格里的 URL / 主机名 / 状态码 | 这些值常被程序按字符串比较；表格只在绘制层翻译，不改数据 |
| 渲染视图里的真实网页 | 那是用户请求回来的页面，按 base URL 跳过 |
| 帮助文档 | 独立的 HTML 资源包，扩展机制覆盖不到 |
| 插图 PNG / 代码截图里的文字 | 图片像素，不是文本组件 |
| 纯自绘且坐标固定的文字 | 内容能换，坐标改不了 |

其他行为：

- 表格单元格只在绘制时翻译，不写 `TableModel`。
- 可编辑框、下拉框选项、表格数据模型默认不碰。
- 自绘组件只对 `burp.*` 做实验性反射，Burp 升级后可能失效。
- 中文显示成方块时，把 `config.applyFont` 改成 `true`，插件会优先选微软雅黑/苹方/思源黑体，并保留原字号。

---

## 五、项目结构

```text
src/main/java/cn/burp/i18n/     扩展源码
src/main/resources/dict.json    内置词库
dist/                           可直接下载的 jar
```

这是个人项目，随缘维护，不承诺适配所有 Burp 版本，也不提供官方支持。
