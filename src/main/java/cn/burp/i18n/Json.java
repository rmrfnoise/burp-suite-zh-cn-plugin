package cn.burp.i18n;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器。
 *
 * <p>只为读取词库存在：不引第三方库，避免扩展 jar 带上几百 KB 依赖，
 * 也避免在 Burp 的 ClassLoader 里和其他扩展的 gson / jackson 打架。
 *
 * <p>支持 object / array / string / number / true / false / null。
 * 所有失败都抛 {@link IllegalArgumentException} 并带上字符位置，方便用户改坏词库时自己定位。
 *
 * <p>线程安全性：解析器实例持有游标，<b>不可复用</b>；每次调用 {@link #parse(String)} 都会新建实例，
 * 因此这个方法本身是无状态的。
 */
final class Json {

    /** 数字字符集。数字解析不需要严格的 JSON 语法，能认出来即可 —— 词库里只有配置项是数字。 */
    private static final String NUMBER_CHARS = "+-0123456789.eE";

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    /**
     * 解析一段 JSON 文本。
     *
     * @param s JSON 源码，允许带 UTF-8 BOM（Windows 记事本另存为时经常带上）
     * @return 顶层值：{@link Map}、{@link List}、{@link String}、{@link Double}、{@link Boolean} 或 {@code null}
     * @throws IllegalArgumentException 语法错误，消息里带位置
     */
    static Object parse(String s) {
        Json p = new Json(s == null ? "" : s);
        p.skipWs();
        // 记事本保存的 UTF-8 会带 BOM，不跳过会让第一个字符看起来不是 '{'
        if (p.pos < p.src.length() && p.src.charAt(p.pos) == '\uFEFF') {
            p.pos++;
            p.skipWs();
        }
        Object v = p.value();
        p.skipWs();
        if (p.pos < p.src.length()) {
            throw new IllegalArgumentException("JSON 尾部存在多余内容，位置 " + p.pos);
        }
        return v;
    }

    private Object value() {
        skipWs();
        if (pos >= src.length()) {
            throw new IllegalArgumentException("JSON 意外结束");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        pos++; // 跳过 '{'
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return m;
        }
        while (true) {
            skipWs();
            String k = string();
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != ':') {
                throw new IllegalArgumentException("JSON 缺少冒号，位置 " + pos);
            }
            pos++;
            // 重复键后写覆盖前写，和浏览器的 JSON.parse 一致
            m.put(k, value());
            skipWs();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("JSON 对象未闭合");
            }
            char c = src.charAt(pos++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw new IllegalArgumentException("JSON 对象分隔符错误，位置 " + (pos - 1));
            }
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        pos++; // 跳过 '['
        skipWs();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return l;
        }
        while (true) {
            l.add(value());
            skipWs();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("JSON 数组未闭合");
            }
            char c = src.charAt(pos++);
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw new IllegalArgumentException("JSON 数组分隔符错误，位置 " + (pos - 1));
            }
        }
    }

    /**
     * 读字符串。这里逐字符判断边界：词库是用户手改的，
     * 少一个引号或把转义写坏都很常见，报位置比抛 StringIndexOutOfBounds 有用得多。
     */
    private String string() {
        if (pos >= src.length() || src.charAt(pos) != '"') {
            throw new IllegalArgumentException("JSON 期望字符串，位置 " + pos);
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("JSON 字符串未闭合");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            // 反斜杠出现在文件末尾 —— 转义符后面什么都没有
            if (pos >= src.length()) {
                throw new IllegalArgumentException("JSON 转义符后缺少字符");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw new IllegalArgumentException("JSON \\u 转义不完整，位置 " + (pos - 2));
                    }
                    int code = 0;
                    for (int k = 0; k < 4; k++) {
                        int digit = Character.digit(src.charAt(pos + k), 16);
                        if (digit < 0) {
                            throw new IllegalArgumentException("JSON \\u 转义含非十六进制字符，位置 " + (pos + k));
                        }
                        code = code * 16 + digit;
                    }
                    pos += 4;
                    sb.append((char) code);
                }
                default -> throw new IllegalArgumentException("JSON 非法转义 \\" + e);
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < src.length() && NUMBER_CHARS.indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("JSON 非法值，位置 " + start);
        }
        String raw = src.substring(start, pos);
        try {
            Double value = Double.valueOf(raw);
            if (!Double.isFinite(value)) {
                // 1e999 会解析成 Infinity；词库配置里出现这种值一定是写错了
                throw new IllegalArgumentException("JSON 数字超出范围：" + raw + "，位置 " + start);
            }
            return value;
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("JSON 数字格式错误：" + raw + "，位置 " + start);
        }
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw new IllegalArgumentException("JSON 期望 " + literal + "，位置 " + pos);
        }
        pos += literal.length();
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                return;
            }
        }
    }
}
