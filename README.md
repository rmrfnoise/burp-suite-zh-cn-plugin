# Burp Suite 简体中文插件（burp-suite-zh-cn-plugin）

一个基于 **Montoya API** 的 Burp Suite 扩展，把界面上的英文翻译成简体中文。

- 下载：[Releases](https://github.com/rmrfnoise/burp-suite-zh-cn-plugin/releases/latest)
- 许可证：[MIT](LICENSE)
- 构建基线：Montoya API 2024.7 / Java 17

> 非官方项目，与 PortSwigger Ltd. 没有隶属或背书关系。  
> “Burp” / “Burp Suite” 是 PortSwigger Ltd. 的商标；使用本扩展仍受 Burp 许可协议约束。

## 安装与使用

1. 从 [Releases](https://github.com/rmrfnoise/burp-suite-zh-cn-plugin/releases/latest) 下载最新 jar。
2. 打开 Burp → **Extensions** → **Installed** → **Add**。
3. `Extension type` 选 **Java**，`Extension file` 选下载的 jar，点 **Next**。
4. 在 **Output** 中确认加载成功：

```text
[burp-suite-zh-cn-plugin] build <版本>+<构建时间>
[burp-suite-zh-cn-plugin] AWT listeners on bus: 1 (1 = clean)
[burp-suite-zh-cn-plugin] dictionary: <词条数> entries / <规则数> rules <- C:\Users\<你>\.burp-i18n\dict.json
```

`AWT listeners on bus` 是扩展注册的监听器数量，正常为 **1**。大于 1 通常说明旧实例没有退干净，完整退出 Burp 再启动即可。

卸载：**Extensions** → **Installed** → 选中扩展 → **Remove**。已经替换的界面文本会随 Burp 重绘或重启恢复。

## 自己构建（可选）

只有修改 Java 源码或内置词库时才需要。构建需要 **JDK 17+**，在项目根目录运行：

```powershell
# 重新打当前版本，自己使用
.\build.ps1

# 发布新版本时指定版本号（示例占位，实际填你的版本）
.\build.ps1 -Version <新版本号>
```

产物输出到 `dist/`。`-Version` 只影响 manifest 和 jar 文件名，不会自动修改 README 或词库的 `config.version`。

如果 jar 正在被 Burp 加载，先 Remove 扩展或关闭 Burp，否则 Windows 会锁住文件。

## 词库

首次运行时，插件会把 jar 内置的词库复制到：

```text
C:\Users\<用户名>\.burp-i18n\dict.json
```

之后修改这个文件并保存，插件会在 **8 秒内**自动重载。

### 结构

```json
{
  "config": { "logMissing": true, "resweepSeconds": 300 },
  "ignore": ["Burp", "HTTP"],
  "groups": { "通用": { "OK": "确定", "Cancel": "取消" } },
  "map": { "Drop": "丢弃" },
  "patterns": [
    { "regex": "^Length: (\\d+)$", "replacement": "长度：$1" }
  ],
  "ignorePatterns": ["^https?://"]
}
```

| 字段 | 作用 |
|---|---|
| `map` | 平铺的“英文 → 中文”映射，适合零散补词 |
| `groups` | 按主题分组的同样映射；和 `map` 等效，同名键以 `map` 为准 |
| `ignore` | 永不翻译的白名单 |
| `patterns` | 动态文本的正则替换 |
| `ignorePatterns` | URL、日期、哈希等数据，不翻译也不记日志 |
| `scopes` | 按组件类名限定译法，默认空 |
| `config` | `version` 词库版本；`logMissing` 记录漏译；`deepScan` 扫描自绘组件；`translateCells` 翻译表格；`applyFont` 覆盖字体；`resweepSeconds` 兜底扫描周期 |

匹配顺序是：`scopes` → `map` / `groups` → `patterns` → 保持英文。查不到就原样保留，**不做半句替换或猜测翻译**。

### 补词

直接修改 `C:\Users\<用户名>\.burp-i18n\dict.json`：在 `map` 里加 `"英文": "中文"`，也可以放进 `groups` 的某个组，保存后自动热重载。

`missing.txt` 是可选的漏译清单：插件会把界面出现、但词库没命中的英文记在这里，方便你找还缺哪些词，或复制准确原文。不想生成就设 `"logMissing": false`。

| 你要做什么 | 改哪里 |
|---|---|
| 自己补词，马上生效 | `C:\Users\<用户名>\.burp-i18n\dict.json` |
| 更新发给别人的内置词库 | `src/main/resources/dict.json`，然后重新构建 jar |

### 版本与升级

`config.version` 是**词库版本**，不是插件版本。只有发布新 jar、需要让已有用户换用新内置词库时，才需要调高。

- 只改本地 `~/.burp-i18n/dict.json`：不用动版本号；
- 发布新 jar：把 `src/main/resources/dict.json` 的 `version` 加 1，已有用户启动时才会备份旧词库并替换；
- 想锁住自己的词库不被升级：把版本号改成 `9999`。

如果忘了升版本号，已有用户会继续用本地旧词库，新词不生效；新用户不受影响。升了版本号时，插件会先把旧词库备份成 `dict.json.bak-时间`，再替换；旧词库里的自定义词不会自动合并，需要从备份手动搬。

## 隐私

- 插件**不联网，不统计你用了哪些功能，也不上传任何数据**。
- 本地只写 `~/.burp-i18n/`：词库、`missing.txt`、升级备份。
- `missing.txt` 可能包含主机名、URL、自定义字段等测试信息，分享前请脱敏；不想生成就设 `"logMissing": false`。
- 只有 `build.ps1` 在构建时会访问 Maven Central 下载 Montoya API；插件运行时不发起网络请求。
- 关闭 Burp 后删除 `~/.burp-i18n/` 即可清除本地数据。

## 已知边界

| 不翻译 | 原因 |
|---|---|
| 启动向导 | 出现在扩展加载之前 |
| 可编辑输入框 / 文本域 | 里面是用户数据：报文、搜索词、配置值 |
| 下拉框选项、表格里的 URL / 主机名 / 状态码 | 常被程序按字符串比较；表格只在绘制层翻译 |
| 渲染视图里的真实网页 | 用户请求回来的页面，按 base URL 跳过 |
| 帮助文档、图片和截图里的文字 | 不是普通文本组件 |

其他：

- 表格只在绘制层翻译，不修改 `TableModel` 或请求/响应数据。
- 自绘组件 `burp.*` 的反射是实验性的，Burp 升级后可能失效。
- 中文显示成方块时，把 `config.applyFont` 改成 `true`。
- 翻译范围是界面文本；不会修改 Burp 的请求、响应和扫描结果。

## 项目结构

```text
src/main/java/cn/burp/i18n/     扩展源码
src/main/resources/dict.json    内置词库
dist/                           构建产物
build.ps1                       本地构建脚本
```

个人项目，随缘维护，不承诺适配所有 Burp 版本。
