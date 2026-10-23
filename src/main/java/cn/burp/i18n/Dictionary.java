package cn.burp.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 词库模型：把 dict.json 解析成"查表结构"，并提供三种查法。
 *
 * <p>一份词库由四部分组成：
 * <ul>
 *   <li>{@code entries}：{@code 英文 -> 中文} 的精确映射（来自 {@code groups} 与 {@code map}，后者覆盖前者）</li>
 *   <li>{@code scopes}：按祖先类名限定的译法，用来消除"同一个词在两个面板里含义不同"的歧义</li>
 *   <li>{@code rules}：正则规则，处理带数字/编号的动态文本（{@code Memory: 192.7MB of 3.97GB}）</li>
 *   <li>{@code noise}：命中即"永远不翻译、也不记进 missing"的规则（字符集名、文件大小、URL、哈希）</li>
 * </ul>
 *
 * <p>查询优先级：{@code scopes[祖先类名][原文]} → {@code entries[原文]} → 保持英文。
 * 查不到就返回 {@code null}，调用方必须原样保留英文，<b>绝不做任何猜测式翻译</b>。
 *
 * <p>不可变性：实例构造完成后所有集合只读（字段是 final 引用，但集合本身可变，
 * 仅由 {@link #fromJson} 在构造期填充）。热重载是"整个对象换掉"，不是原地修改，
 * 所以任意线程读一个已发布的 Dictionary 都是安全的。
 */
final class Dictionary {

    /** 空词库占位符。插件启动失败或词库全坏时用它兜底，保证所有查询直接返回 null。 */
    static final Dictionary EMPTY = new Dictionary();

    /** 精确映射表，保持 JSON 里的书写顺序，方便调试时对照。 */
    private final Map<String, String> entries = new LinkedHashMap<>();
    /** 作用域映射：类简单名 -> (英文 -> 中文)。 */
    final Map<String, Map<String, String>> scopes = new HashMap<>();
    /** 白名单：这些词（模块名、协议名、字段名）任何情况下都不翻译。 */
    private final Set<String> neverTranslate = new HashSet<>();
    /** 正则规则，按书写顺序匹配，先命中者生效。 */
    private final List<Rule> rules = new ArrayList<>();
    /** 噪声规则：命中说明这是数据而不是界面文案，既不翻译也不记漏译。 */
    private final List<Pattern> noise = new ArrayList<>();

    /**
     * "确定查不到译文"的文本缓存。
     *
     * <p>热点复核、300 秒兜底、点击扫描会反复查同一批没命中的英文；每次都要跑
     * 12 条噪声正则 + 两次查表 + 45 条翻译正则，成本可观。这里只缓存负结果，
     * 命中结果本来就快。词库重载会整体换新对象，缓存自然失效。
     */
    private static final int MISS_CACHE_MAX = 8192;
    private final Map<String, Boolean> missCache = new ConcurrentHashMap<>();

    // ---- config 项。字段保持包内可读，由 fromJson 在构造期一次性填好 ----

    /** 是否处理 {@code <html>...</html>} 包裹的文本（Burp 用它做换行）。 */
    boolean translateHtml = true;
    /** 是否强制覆盖 UI 字体。只在中文显示成方块时才需要打开。 */
    boolean applyFont = false;
    /** 是否把没命中的英文写进 missing.txt，用于补词库。 */
    boolean logMissing = true;
    /** 是否反射改写自绘组件里的字符串字段（Burp 新 UI 的卡片是画出来的）。 */
    boolean deepScan = true;
    /** 是否翻译表格单元格里的"值类型"短语。 */
    boolean translateCells = true;
    /** applyFont 时使用的字体族，留空则自动挑选。 */
    String fontFamily = "";
    /** 全量兜底扫描周期（秒），0 表示关闭。 */
    int resweepSeconds = 300;
    /** 词库版本号：jar 内置版本更高时自动升级磁盘副本。 */
    int version = 0;

    /** 一条正则翻译规则。原来用两个平行 List 存放正则和替换串，容易错位，改成一条记录。 */
    private record Rule(Pattern pattern, String replacement) {
    }

    private Dictionary() {
    }

    static Dictionary load(Path path) throws IOException {
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return fromJson(text);
    }

    @SuppressWarnings("unchecked")
    static Dictionary fromJson(String text) {
        Dictionary d = new Dictionary();
        Object root = Json.parse(text);
        if (!(root instanceof Map)) {
            return d;
        }
        Map<String, Object> m = (Map<String, Object>) root;

        d.readConfig(m.get("config"));

        Object ignore = m.get("ignore");
        if (ignore instanceof List) {
            for (Object o : (List<Object>) ignore) {
                if (o instanceof String && !((String) o).isEmpty()) {
                    d.neverTranslate.add((String) o);
                }
            }
        }

        // groups 是"按主题分组"的写法，map 是平铺写法，两者等价、可共存；map 后读，因此优先
        Object groups = m.get("groups");
        if (groups instanceof Map) {
            for (Object g : ((Map<String, Object>) groups).values()) {
                if (g instanceof Map) {
                    mergeInto(d.entries, (Map<String, Object>) g);
                }
            }
        }
        Object flat = m.get("map");
        if (flat instanceof Map) {
            mergeInto(d.entries, (Map<String, Object>) flat);
        }

        Object sc = m.get("scopes");
        if (sc instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) sc).entrySet()) {
                if (e.getValue() instanceof Map) {
                    Map<String, String> sub = new LinkedHashMap<>();
                    mergeInto(sub, (Map<String, Object>) e.getValue());
                    if (!sub.isEmpty()) {
                        d.scopes.put(e.getKey(), sub);
                    }
                }
            }
        }

        Object pts = m.get("patterns");
        if (pts instanceof List) {
            for (Object o : (List<Object>) pts) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> pm = (Map<String, Object>) o;
                Object regex = pm.get("regex");
                Object repl = pm.get("replacement");
                if (regex instanceof String && repl instanceof String) {
                    try {
                        d.rules.add(new Rule(Pattern.compile((String) regex), (String) repl));
                    } catch (PatternSyntaxException bad) {
                        // 单条坏正则跳过，不影响其余词条 —— 用户手改词库时很常见
                    }
                }
            }
        }

        Object noisePatterns = m.get("ignorePatterns");
        if (noisePatterns instanceof List) {
            for (Object o : (List<Object>) noisePatterns) {
                if (!(o instanceof String)) {
                    continue;
                }
                try {
                    d.noise.add(Pattern.compile((String) o));
                } catch (PatternSyntaxException bad) {
                    // 同上，坏规则跳过
                }
            }
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    private void readConfig(Object cfgObj) {
        if (!(cfgObj instanceof Map)) {
            return;
        }
        Map<String, Object> cfg = (Map<String, Object>) cfgObj;
        translateHtml = bool(cfg.get("translateHtml"), true);
        applyFont = bool(cfg.get("applyFont"), false);
        logMissing = bool(cfg.get("logMissing"), true);
        deepScan = bool(cfg.get("deepScan"), true);
        translateCells = bool(cfg.get("translateCells"), true);
        Object ff = cfg.get("fontFamily");
        if (ff instanceof String) {
            fontFamily = ((String) ff).trim();
        }
        // JSON 数字统一解析成 Double，这里取整并夹到合法区间，避免负数和超大值把定时器搞崩
        Object rs = cfg.get("resweepSeconds");
        resweepSeconds = Math.max(0, Math.min(86400, intValue(rs, resweepSeconds)));
        Object ver = cfg.get("version");
        version = intValue(ver, version);
    }

    /**
     * 把配置里的数字读成 int。除了 JSON 数字，也接受 {@code "60"} 这种手写字符串 ——
     * 用户改词库时把数字写在引号里是很常见的。
     */
    private static int intValue(Object o, int dflt) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // 不是整数就使用默认值
            }
        }
        return dflt;
    }

    /**
     * 读取词库版本，用于决定是否升级磁盘副本。
     * 只在启动时调用一次，直接走完整解析，代码更少。
     *
     * @return 解析失败返回 0（调用方会当作旧版本，触发升级）
     */
    static int parseVersion(String json) {
        try {
            Object root = Json.parse(json);
            if (root instanceof Map) {
                Object cfg = ((Map<?, ?>) root).get("config");
                if (cfg instanceof Map) {
                    Object v = ((Map<?, ?>) cfg).get("version");
                    if (v != null) {
                        return intValue(v, 0);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 解析失败按 0 处理，调用方会当作"比内置版本低"从而触发升级 —— 正好能修好坏词库
        }
        return 0;
    }

    // ------------------------------------------------------------------ 查词

    /**
     * 精确查词。
     *
     * @param scopeChain 从组件自身到根的类简单名列表，可为空
     * @return 译文；未命中返回 {@code null}
     */
    String lookup(List<String> scopeChain, String text) {
        if (text == null || text.isEmpty() || isIgnored(text)) {
            return null;
        }
        if (!scopes.isEmpty()) {
            for (String scope : scopeChain) {
                Map<String, String> sub = scopes.get(scope);
                if (sub != null) {
                    String v = sub.get(text);
                    if (v == null && hasApostrophe(text)) {
                        // scopes 里的词条也要能容忍撇号写法差异
                        v = sub.get(withSwappedApostrophe(text));
                    }
                    if (v != null) {
                        return v;
                    }
                }
            }
        }
        String v = entries.get(text);
        if (v != null) {
            return v;
        }
        // Burp 的界面文案里撇号有时是 U+2019、有时是 ASCII，词条只写一种形态即可
        return entries.get(withSwappedApostrophe(text));
    }

    /**
     * 宽松查词：在精确匹配失败后再用，容忍"末尾句号有／无"这类极小的标点差异。
     * Burp 同一句话在不同面板里时有时无句号，为这种差异各写一条词条并不值得。
     */
    String lookupLoose(List<String> scopeChain, String text) {
        String hit = lookup(scopeChain, text);
        if (hit != null || text == null || text.isEmpty()) {
            return hit;
        }
        if (text.endsWith(".")) {
            String cut = text.substring(0, text.length() - 1).trim();
            if (!cut.isEmpty()) {
                hit = lookup(scopeChain, cut);
                if (hit != null) {
                    // 英文句号在中文里对应"。"，补回去才像中文
                    return hit.endsWith("。") || hit.endsWith("！") || hit.endsWith("？") ? hit : hit + "。";
                }
            }
        } else {
            hit = lookup(scopeChain, text + ".");
        }
        return hit;
    }

    /**
     * 按正则规则翻译动态文本（{@code Memory: 192.7MB of 3.97GB}、{@code Length: 107}）。
     *
     * @return 替换结果；没有规则命中返回 {@code null}
     */
    String applyRules(String text) {
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            java.util.regex.Matcher m = r.pattern().matcher(text);
            if (m.matches()) {
                try {
                    // matches() 已保证整串命中，replaceFirst 即可（少一次全串扫描）
                    String replaced = m.replaceFirst(r.replacement());
                    // 替换结果和原文一样（例如 "$1+ $2" 这种规则）就继续试下一条，
                    // 否则后面的专用规则会被永久遮蔽
                    if (!replaced.equals(text)) {
                        return replaced;
                    }
                } catch (RuntimeException bad) {
                    // 替换串里的 $ 引用写错会抛异常，跳过这条规则
                }
            }
        }
        return null;
    }

    /**
     * 是否是"纯数据"，不该翻译也不该记漏译。
     * 例如 {@code UTF-16}、{@code 64KB}、{@code https://...}、{@code 0x7f3a}、{@code 2026-10-23}。
     */
    boolean isNoise(String text) {
        if (noise.isEmpty() || text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < noise.size(); i++) {
            // 用 find() 而不是 matches()：词库里有 `^(?:https?://|www\.)` 这种前缀规则，
            // matches() 会要求整串命中，导致真正的 URL 一个都匹配不上
            if (noise.get(i).matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /** 是否被 {@code ignore} 白名单排除：既不翻译，也不该写进 missing.txt。 */
    boolean isIgnored(String text) {
        return text != null && neverTranslate.contains(text);
    }

    /** 该文本在当前词库快照下确定没有译文（见 {@link #rememberMiss}）。 */
    boolean isKnownMiss(String text) {
        return text != null && scopes.isEmpty() && missCache.containsKey(text);
    }

    /**
     * 记录一条"确定没命中"的文本。只在无 scope（全局词条）时生效，
     * 因为 scopes 会让同一句话在不同面板里有不同译法。
     */
    void rememberMiss(String text) {
        if (!scopes.isEmpty() || text == null || text.isEmpty() || text.length() > 400) {
            return;
        }
        if (missCache.size() >= MISS_CACHE_MAX) {
            // 粗暴清空比维护 LRU 便宜，而且这种量级下很少触发
            missCache.clear();
        }
        missCache.put(text, Boolean.TRUE);
    }

    /** 是否没有任何翻译能力。为空时整个翻译流程直接短路，省下逐组件扫描的开销。 */
    boolean isEmpty() {
        return entries.isEmpty() && rules.isEmpty() && scopes.isEmpty();
    }

    int size() {
        return entries.size();
    }

    int ruleCount() {
        return rules.size();
    }

    // ------------------------------------------------------------------ 内部

    private static void mergeInto(Map<String, String> target, Map<String, Object> src) {
        for (Map.Entry<String, Object> e : src.entrySet()) {
            if (e.getValue() instanceof String) {
                String k = e.getKey();
                if (k != null && !k.isEmpty()) {
                    target.put(k, (String) e.getValue());
                }
            }
        }
    }

    /** ASCII {@code '} 与排版撇号 U+2019 互换一次。 */
    private static String withSwappedApostrophe(String s) {
        if (s.indexOf('\'') >= 0) {
            return s.replace('\'', '\u2019');
        }
        if (s.indexOf('\u2019') >= 0) {
            return s.replace('\u2019', '\'');
        }
        return s;
    }

    private static boolean hasApostrophe(String s) {
        return s.indexOf('\'') >= 0 || s.indexOf('\u2019') >= 0;
    }

    private static boolean bool(Object o, boolean dflt) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof String) {
            return Boolean.parseBoolean((String) o);
        }
        return dflt;
    }
}
