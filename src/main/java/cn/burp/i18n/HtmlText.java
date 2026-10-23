package cn.burp.i18n;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 富文本与纯文本工具集。
 *
 * <p>Burp 大量用 HTML 排版界面文字（说明段落、空状态提示、设置页），而词库里存的是纯文本整句。
 * 这个类负责把 HTML 压成"可查词的一行文本"，以及实体还原、转义、CJK 判定这些周边工作。
 *
 * <p>设计约束：
 * <ul>
 *   <li><b>全部静态无状态</b>。早先压平结果的首尾位置存在两个静态字段里，
 *       一旦发生嵌套调用（按块翻译时又去压平）就会互相覆盖，译文位置错乱。现在改用 {@link Flat} 返回值携带。</li>
 *   <li><b>正则全部预编译</b>。{@code String.replaceAll} 每次调用都会重新编译 Pattern，
 *       而这些方法在界面刷新时每秒要跑几百次。</li>
 * </ul>
 */
final class HtmlText {

    /** 连续空白（含不换行空格）。{@code \s} 不认 U+00A0，必须显式列出来。 */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]+");

    /** {@code <style>} / {@code <script>} 整段（含内容）。 */
    private static final Pattern STYLE_SCRIPT = Pattern.compile("(?is)<(style|script)[^>]*>.*?</\\1>");

    /** 任意标签。 */
    private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]*>");

    private HtmlText() {
    }

    /**
     * 压平结果。
     *
     * @param text  去掉标签后的纯文本（标签位置替换成空格，保留词间分隔）
     * @param first 第一个非空白字符在原始 HTML 中的下标（-1 表示整段没有文字）
     * @param last  最后一个非空白字符的下一位下标
     */
    record Flat(String text, int first, int last) {
    }

    /**
     * 把 HTML 压成一段纯文本。
     *
     * <p>处理规则：
     * <ul>
     *   <li>{@code <style>} / {@code <script>} 的内容整段丢弃 —— CSS、JS 混进待查句子只会让匹配必然失败</li>
     *   <li>注释、DOCTYPE、XML 声明直接跳过</li>
     *   <li>其余标签一律只当分隔符：压平结果必须"整串命中词条"才会被写回，
     *       所以即使遇到 {@code <img>}、{@code <table>} 也不会误改结构</li>
     * </ul>
     *
     * @return 压平结果；HTML 残破到无法解析时返回 {@code null}（调用方保持原文不动）
     */
    static Flat flatten(String html) {
        int len = html.length();
        StringBuilder plain = new StringBuilder(len);
        int first = -1;
        int last = -1;
        int i = 0;
        while (i < len) {
            char ch = html.charAt(i);
            if (ch != '<') {
                if (!Character.isWhitespace(ch)) {
                    if (first < 0) {
                        first = i;
                    }
                    last = i + 1;
                }
                plain.append(ch);
                i++;
                continue;
            }
            // 注释整段跳过：注释里可能有标签或引号，逐字符解析会把它们当成真实结构
            if (html.startsWith("<!--", i)) {
                int end = html.indexOf("-->", i + 4);
                if (end < 0) {
                    return null;
                }
                plain.append(' ');
                i = end + 3;
                continue;
            }
            int gt = html.indexOf('>', i);
            if (gt < 0) {
                return null;
            }
            String raw = html.substring(i + 1, gt).trim();
            boolean closing = raw.startsWith("/");
            String tag = closing ? raw.substring(1).trim() : raw;
            if (tag.endsWith("/")) {
                tag = tag.substring(0, tag.length() - 1).trim();
            }
            int sp = tag.indexOf(' ');
            if (sp > 0) {
                tag = tag.substring(0, sp);
            }
            // <!doctype html> / <?xml …> 这类声明没有对应结束标签。只比较前两个字符，避免 toLowerCase 整串拷贝
            if (tag.startsWith("!") || tag.startsWith("?")) {
                plain.append(' ');
                i = gt + 1;
                continue;
            }
            if (!closing && (tag.equalsIgnoreCase("style") || tag.equalsIgnoreCase("script"))) {
                // 找对应的结束标签时不区分大小写，且不能拷贝整篇 HTML 去做 toLowerCase
                int endTag = indexOfIgnoreCase(html, "</" + tag, gt);
                if (endTag < 0) {
                    return null;
                }
                int e2 = html.indexOf('>', endTag);
                if (e2 < 0) {
                    return null;
                }
                plain.append(' ');
                i = e2 + 1;
                continue;
            }
            plain.append(' ');
            i = gt + 1;
        }
        return new Flat(plain.toString(), first, last);
    }

    /**
     * 生成"用于查词"的字符串。
     *
     * <p>Burp 把同一句话序列化成 HTML 时，标点和空白有好几种表示，逐一对齐才能命中词条：
     * <ul>
     *   <li>撇号：{@code &#8217;} / {@code &rsquo;} / 直接写 {@code ’} —— 统一成 ASCII {@code '}</li>
     *   <li>引号：{@code &#8220;} 解出来是弯引号 —— 统一成 ASCII {@code "}</li>
     *   <li>空白：{@code &nbsp;} 解出来是 U+00A0，Java 正则的 {@code \s} 不认它，必须显式换成普通空格</li>
     * </ul>
     */
    static String probe(String s) {
        String t = decodeEntities(s);
        if (t.indexOf('&') >= 0) {
            // 命名实体单遍解析。处理两轮是为了 &amp;lt; 这种二次转义：
            // 第一轮得到 &lt;，第二轮才还原成 <
            for (int pass = 0; pass < 2; pass++) {
                String decoded = decodeNamed(t);
                if (decoded.equals(t)) {
                    break;
                }
                t = decoded;
            }
        }
        // 绝大多数界面文本没有弯引号和特殊空格，先判断再替换，省掉几次整串扫描
        if (t.indexOf('\u201c') >= 0 || t.indexOf('\u201d') >= 0
                || t.indexOf('\u2018') >= 0 || t.indexOf('\u2019') >= 0) {
            t = t.replace('\u201c', '"').replace('\u201d', '"')
                    .replace('\u2018', '\'').replace('\u2019', '\'');
        }
        if (t.indexOf('\u00A0') >= 0 || t.indexOf('\u2007') >= 0 || t.indexOf('\u202F') >= 0
                || t.indexOf('\u200B') >= 0 || t.indexOf('\uFEFF') >= 0) {
            t = t.replace('\u00A0', ' ').replace('\u2007', ' ').replace('\u202F', ' ')
                    .replace('\u200B', ' ').replace('\uFEFF', ' ');
        }
        return WHITESPACE.matcher(t).replaceAll(" ").trim();
    }

    /** 单遍还原 {@code &amp;}、{@code &lt;}、{@code &rsquo;} 这类命名实体。 */
    private static String decodeNamed(String s) {
        int amp = s.indexOf('&');
        if (amp < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        out.append(s, 0, amp);
        int i = amp;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch != '&') {
                out.append(ch);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            // 实体名最长 8 个字符左右，隔太远说明这个 & 只是普通符号
            if (semi < 0 || semi - i > 12) {
                out.append('&');
                i++;
                continue;
            }
            String replacement = named(s.substring(i + 1, semi));
            if (replacement == null) {
                out.append('&');
                i++;
                continue;
            }
            out.append(replacement);
            i = semi + 1;
        }
        return out.toString();
    }

    private static String named(String name) {
        return switch (name) {
            case "rsquo" -> "\u2019";
            case "lsquo" -> "\u2018";
            case "apos" -> "'";
            case "quot" -> "\"";
            case "nbsp" -> " ";
            case "mdash" -> "\u2014";
            case "ndash" -> "\u2013";
            case "hellip" -> "\u2026";
            case "lt" -> "<";
            case "gt" -> ">";
            case "amp" -> "&";
            default -> null;
        };
    }

    /**
     * 还原 {@code &#31449;}、{@code &#x7AD9;} 这类数字实体。
     *
     * <p>只处理数字实体；命名实体（{@code &amp;}、{@code &quot;}）由 {@link #probe} 负责，
     * 免得把用户数据里的实体也改坏。
     */
    static String decodeEntities(String s) {
        if (s.indexOf("&#") < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int len = s.length();
        while (i < len) {
            char ch = s.charAt(i);
            if (ch == '&' && i + 2 < len && s.charAt(i + 1) == '#') {
                int j = i + 2;
                boolean hex = false;
                if (j < len && (s.charAt(j) == 'x' || s.charAt(j) == 'X')) {
                    hex = true;
                    j++;
                }
                int numStart = j;
                while (j < len && Character.digit(s.charAt(j), hex ? 16 : 10) >= 0) {
                    j++;
                }
                if (j > numStart && j < len && s.charAt(j) == ';') {
                    try {
                        int cp = Integer.parseInt(s.substring(numStart, j), hex ? 16 : 10);
                        if (cp > 0 && cp <= 0x10FFFF) {
                            out.appendCodePoint(cp);
                            i = j + 1;
                            continue;
                        }
                    } catch (NumberFormatException ignored) {
                        // 数字不合法，原样输出
                    }
                }
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    /** HTML 转义，用于把译文安全地写回富文本（译文里可能出现 {@code &}、{@code <}）。 */
    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 粗暴去标签：先整段丢弃 style/script，再删掉所有标签。用于压平失败时的最后兜底。 */
    static String stripTags(String html) {
        return ANY_TAG.matcher(STYLE_SCRIPT.matcher(html).replaceAll(" ")).replaceAll(" ");
    }

    /**
     * 文本里是否已经含有中文（或全角标点）。
     *
     * <p>两个用途：① 已经是中文就别再翻；② 判断漏译日志该不该记。
     * 全角标点也要算 —— 插件把 {@code CPU:} 译成 {@code CPU：} 之后，下一轮若不认全角冒号，
     * 就会把 {@code CPU：} 当成新的未命中项写进日志，词表越跑越脏。
     *
     * <p>性能：这里按码点区间判断，不调 {@code Character.UnicodeScript.of()}。
     * 那个方法每次都要查 Unicode 表，而本方法在每次界面刷新时要对几百个字符串各跑一遍。
     */
    static boolean containsCjk(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            // 汉字（含扩展 A）、中日韩标点、全角字符、CJK 兼容区
            if ((ch >= '\u4E00' && ch <= '\u9FFF')
                    || (ch >= '\u3400' && ch <= '\u4DBF')
                    || (ch >= '\u3000' && ch <= '\u303F')
                    || (ch >= '\uFF01' && ch <= '\uFF65')) {
                return true;
            }
        }
        return false;
    }

    /** 不区分大小写的 {@code indexOf}，避免为一个子串判断把整篇文本 toLowerCase 复制一遍。 */
    static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int n = needle.length();
        int last = haystack.length() - n;
        for (int i = Math.max(0, from); i <= last; i++) {
            if (haystack.regionMatches(true, i, needle, 0, n)) {
                return i;
            }
        }
        return -1;
    }

    /** 是否包含某个片段（不区分大小写）。 */
    static boolean containsIgnoreCase(String haystack, String needle) {
        return indexOfIgnoreCase(haystack, needle, 0) >= 0;
    }

    /**
     * 给定的 HTML 前缀（第一个正文文字之前的部分）是否停在某个内联元素内部。
     *
     * <p>用于决定能不能安全地在文字前插入 {@code <center>}：把块级元素塞进
     * {@code <b>}、{@code <span>} 里会产生非法嵌套，Swing 的 HTML 排版可能错位。
     */
    static boolean isInsideInlineElement(String htmlBeforeText) {
        Deque<String> stack = new ArrayDeque<>();
        int i = 0;
        while (i < htmlBeforeText.length()) {
            int lt = htmlBeforeText.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            if (htmlBeforeText.startsWith("<!--", lt)) {
                int end = htmlBeforeText.indexOf("-->", lt + 4);
                if (end < 0) {
                    break;
                }
                i = end + 3;
                continue;
            }
            int gt = htmlBeforeText.indexOf('>', lt);
            if (gt < 0) {
                break;
            }
            String raw = htmlBeforeText.substring(lt + 1, gt).trim();
            if (raw.isEmpty()) {
                i = gt + 1;
                continue;
            }
            boolean closing = raw.charAt(0) == '/';
            boolean selfClosing = raw.endsWith("/");
            boolean declaration = raw.charAt(0) == '!' || raw.charAt(0) == '?';
            if (closing) {
                String name = tagName(raw.substring(1));
                // 容忍残破 HTML：一直弹到同名标签为止
                while (!stack.isEmpty()) {
                    if (stack.pop().equals(name)) {
                        break;
                    }
                }
            } else if (!selfClosing && !declaration) {
                String name = tagName(raw);
                if (!name.isEmpty() && !VOID_TAGS.contains(name)) {
                    stack.push(name);
                }
            }
            i = gt + 1;
        }
        return !stack.isEmpty() && INLINE_TAGS.contains(stack.peek());
    }

    static String tagName(String raw) {
        int i = 0;
        if (i < raw.length() && raw.charAt(i) == '/') {
            i++; // 结束标签 </style>
        }
        while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == ':') {
                i++;
            } else {
                break;
            }
        }
        return raw.substring(start, i).toLowerCase(Locale.ROOT);
    }

    private static final Set<String> INLINE_TAGS = Set.of(
            "b", "strong", "i", "em", "u", "s", "strike", "span", "font", "a",
            "code", "small", "big", "sub", "sup", "label", "mark", "abbr", "cite");

    private static final Set<String> VOID_TAGS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr");
}
