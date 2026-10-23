package cn.burp.i18n;

import java.awt.Component;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;

/**
 * 反射工具：读写"非标准组件"的文本，以及改写自绘组件里的字符串字段。
 *
 * <p>Burp 新版界面里有些控件是画出来的：文字存在普通字段里，{@code paintComponent}
 * 直接 {@code drawString}，任何 {@code getText()} 都读不到。这里兜住两种情况：
 * 有 {@code getText/setText} 的自定义组件，以及文字只躺在字段里的组件。
 *
 * <p>安全边界：
 * <ul>
 *   <li>深扫只处理 {@code burp.*} 组件，绝不碰 Swing / FlatLaf / 第三方组件；</li>
 *   <li>只改写非 static、字段名不属于数据类的字段；</li>
 *   <li>只认词库整串命中，不做局部替换；</li>
 *   <li>单个字段失败只跳过该字段，异常不外泄。</li>
 * </ul>
 */
final class Reflect {

    /** 快路径取文本的长度上限。超过这个长度的多半是报文或日志，不是界面文案。 */
    private static final int MAX_QUICK_TEXT = 400;

    /** 单个对象的字段预算，防止遇到巨型对象时卡住 EDT。 */
    private static final int FIELD_BUDGET = 150;

    /** 单个 String[] / List 字段最多检查多少个元素，防止十万级集合把 EDT 拖死。 */
    private static final int COLLECTION_ITEMS = 64;

    /** 深扫递归深度上限（只往 burp* 嵌套对象里走两层）。 */
    private static final int MAX_NESTING = 2;

    /**
     * 字段名黑名单：这些字段装的是会被程序按值比较的数据，改掉会破坏逻辑。
     * 按 camelCase / snake_case 拆词后精确比对，不能用 {@code contains} ——
     * 否则 {@code width} 含 {@code id}、{@code directory} 含 {@code dir}，会误杀说明文字字段。
     */
    private static final Set<String> DATA_FIELD_WORDS = new HashSet<>(List.of(
            "url", "uri", "host", "path", "key", "id", "name", "type", "kind", "command",
            "action", "header", "cookie", "class", "code", "payload", "regex", "pattern",
            "token", "hash", "file", "dir", "cmd", "scope", "method", "status"));

    /** 类 -> 声明字段。字段表只取一次，避免每轮扫描重复做权限检查。 */
    private static final Map<Class<?>, Field[]> FIELD_CACHE = new ConcurrentHashMap<>();

    /** "类名#方法名" -> 方法。key 必须带方法名，否则 getText 与 setText 会串。 */
    private static final Map<String, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    /** 字段名 -> 是否属于数据字段。字段名重复率极高，缓存几乎全命中。 */
    private static final Map<String, Boolean> DENY_CACHE = new ConcurrentHashMap<>();

    private Reflect() {
    }

    /** 深扫回调：给出字段名与原始字符串，返回译文；返回 {@code null} 表示不改。 */
    interface FieldSink {
        String translate(String fieldName, String value);
    }

    /** 是否是值得反射深扫的自定义组件。JDK 标准组件没有自绘字段。 */
    static boolean isCustom(Component c) {
        return c != null && !isJdkClass(c.getClass().getName());
    }

    /** 是否是 Burp 自己的组件（深扫只认这个前缀，避免碰第三方 LAF/浏览器组件）。 */
    static boolean isBurpComponent(Component c) {
        return c != null && c.getClass().getName().startsWith("burp.");
    }

    // ------------------------------------------------------------------ 文本读写

    /**
     * 取组件的"主文本"，取不到返回 {@code null}。
     *
     * <p>对 {@link JTextComponent} 先看文档长度：{@code getText()} 会复制整个文档，
     * 而编辑器里可能是几 MB 的 HTTP 报文。这是插件最容易被感知到的卡顿来源。
     */
    static String quickText(Component c) {
        if (c instanceof JLabel label) {
            return label.getText();
        }
        if (c instanceof AbstractButton button) {
            return button.getText();
        }
        if (c instanceof JTextComponent text) {
            // Document.getLength() 是 O(1)，先用它挡掉大文档
            if (text.getDocument() == null || text.getDocument().getLength() > MAX_QUICK_TEXT) {
                return null;
            }
            return text.getText();
        }
        if (!isCustom(c)) {
            return null;
        }
        Optional<Method> getter = getter(c.getClass());
        if (getter.isEmpty()) {
            return null;
        }
        try {
            Object value = getter.get().invoke(c);
            return value instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 反射写回自定义组件的文本。没有 {@code setText(String)} 就什么都不做。 */
    static void setText(Component c, String text) {
        if (!isCustom(c)) {
            return;
        }
        Optional<Method> setter = setter(c.getClass());
        if (setter.isEmpty()) {
            return;
        }
        try {
            setter.get().invoke(c, text);
        } catch (Throwable ignored) {
            // 不可写就放弃
        }
    }

    private static Optional<Method> getter(Class<?> type) {
        return METHOD_CACHE.computeIfAbsent(type.getName() + "#getText", key -> {
            try {
                Method m = type.getMethod("getText");
                if (m.getReturnType() == String.class) {
                    m.setAccessible(true);
                    return Optional.of(m);
                }
            } catch (Throwable ignored) {
                // 没有无参 getText
            }
            return Optional.empty();
        });
    }

    private static Optional<Method> setter(Class<?> type) {
        return METHOD_CACHE.computeIfAbsent(type.getName() + "#setText", key -> {
            try {
                Method m = type.getMethod("setText", String.class);
                m.setAccessible(true);
                return Optional.of(m);
            } catch (Throwable ignored) {
                return Optional.empty();
            }
        });
    }

    // ------------------------------------------------------------------ 深扫

    /**
     * 反射改写组件里的字符串字段。
     *
     * @return 本次是否读到了非空字符串字段。返回 {@code false} 说明字段还没填，
     *         调用方可以稍后补扫一次
     */
    static boolean deepScan(Component c, FieldSink sink) {
        // 只深扫 Burp 自己的组件。FlatLaf / JCEF / 第三方扩展组件里的字段不是界面文案，
        // 而且字段数量不可控，扫进去只会白耗 EDT。
        if (!isBurpComponent(c)) {
            return false;
        }
        Scanner scanner = new Scanner(sink);
        try {
            scanner.walk(c, 0, new IdentityHashMap<>());
        } catch (Throwable ignored) {
            // 反射失败一律放弃，绝不影响界面
        }
        return scanner.sawText;
    }

    private static final class Scanner {

        private final FieldSink sink;
        private boolean sawText;

        Scanner(FieldSink sink) {
            this.sink = sink;
        }

        void walk(Object owner, int depth, Map<Object, Boolean> seen) {
            if (owner == null || depth > MAX_NESTING || seen.size() > 300) {
                return;
            }
            Class<?> type = owner.getClass();
            if (type.isPrimitive() || type.isEnum() || type.isArray() || isJdkClass(type.getName())) {
                return;
            }
            if (seen.put(owner, Boolean.TRUE) != null) {
                return; // 环形引用只走一次
            }
            int budget = 0;
            for (Class<?> cur = type; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                if (isJdkClass(cur.getName())) {
                    break; // 走到 JDK 基类就停，别动 Swing 自己的字段
                }
                for (Field field : fieldsOf(cur)) {
                    if (++budget > FIELD_BUDGET) {
                        return;
                    }
                    try {
                        visit(owner, field, depth, seen);
                    } catch (Throwable ignored) {
                        // 单个字段失败继续
                    }
                }
            }
        }

        private void visit(Object owner, Field field, int depth, Map<Object, Boolean> seen) throws Exception {
            int mod = field.getModifiers();
            if (Modifier.isStatic(mod)) {
                return;
            }
            Class<?> type = field.getType();
            // final 的 String 不能重新赋值（常量字段还会被 javac 内联，反射 set 也不可靠），
            // 但改 String[] / List 的元素是合法的
            boolean mutable = type == String[].class || List.class.isAssignableFrom(type);
            if (Modifier.isFinal(mod) && !mutable) {
                return;
            }
            if (isDataField(field.getName())) {
                return;
            }
            if (type == String.class) {
                field.setAccessible(true);
                String hit = translate(field.getName(), (String) field.get(owner));
                if (hit != null) {
                    field.set(owner, hit);
                }
                return;
            }
            if (type == String[].class) {
                field.setAccessible(true);
                if (field.get(owner) instanceof String[] array) {
                    int limit = Math.min(array.length, COLLECTION_ITEMS);
                    for (int i = 0; i < limit; i++) {
                        String hit = translate(field.getName(), array[i]);
                        if (hit != null) {
                            array[i] = hit;
                        }
                    }
                }
                return;
            }
            if (List.class.isAssignableFrom(type)) {
                field.setAccessible(true);
                if (field.get(owner) instanceof List<?> raw) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) raw;
                    int limit = Math.min(list.size(), COLLECTION_ITEMS);
                    for (int i = 0; i < limit; i++) {
                        Object item;
                        try {
                            item = list.get(i);
                        } catch (Throwable ignored) {
                            break;
                        }
                        if (!(item instanceof String value)) {
                            continue;
                        }
                        String hit = translate(field.getName(), value);
                        if (hit != null) {
                            try {
                                list.set(i, hit);
                            } catch (Throwable ignored) {
                                // 不可变列表就放弃这一项
                            }
                        }
                    }
                }
                return;
            }
            // 只往 burp 自己的对象里钻，别的类型可能是业务数据模型
            if (depth < MAX_NESTING && type.getName().startsWith("burp")) {
                field.setAccessible(true);
                walk(field.get(owner), depth + 1, seen);
            }
        }

        private String translate(String field, String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }
            sawText = true;
            return sink.translate(field, value);
        }
    }

    // ------------------------------------------------------------------ 元数据

    private static Field[] fieldsOf(Class<?> type) {
        return FIELD_CACHE.computeIfAbsent(type, key -> {
            try {
                return key.getDeclaredFields();
            } catch (Throwable ignored) {
                return new Field[0];
            }
        });
    }

    /** 字段名是否属于"数据类"。按 camelCase / snake_case 拆词后精确比对，结果缓存。 */
    static boolean isDataField(String fieldName) {
        return DENY_CACHE.computeIfAbsent(fieldName, name -> {
            for (String part : name.split("(?<=\\p{Lower})(?=\\p{Upper})|_")) {
                if (DATA_FIELD_WORDS.contains(part.toLowerCase())) {
                    return true;
                }
            }
            return false;
        });
    }

    private static boolean isJdkClass(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("sun.")
                || name.startsWith("com.sun.") || name.startsWith("jdk.");
    }

    /** 卸载时清空缓存，别让旧类加载器的 Class 对象被静态表钉住。 */
    static void clearCaches() {
        FIELD_CACHE.clear();
        METHOD_CACHE.clear();
        DENY_CACHE.clear();
    }
}
