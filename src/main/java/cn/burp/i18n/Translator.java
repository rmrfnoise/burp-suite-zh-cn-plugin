package cn.burp.i18n;

import burp.api.montoya.MontoyaApi;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTextPane;
import javax.swing.JToolTip;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ContainerEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 编排层：把 AWT 事件、定时器、组件树遍历、词库生命周期串起来。
 *
 * <h2>解决两个体验问题</h2>
 * <ol>
 *   <li><b>新出现的界面是英文</b>：靠 AWT 的容器/显示事件即时捕获；</li>
 *   <li><b>点一下又变回英文</b>：Burp 大量复用组件、只重新 {@code setText}，
 *       这类改动不产生组件事件，只能靠属性监听当场改回来。</li>
 * </ol>
 *
 * <h2>为什么不会拖慢 Burp</h2>
 * <ul>
 *   <li>四档节奏：属性监听（即时）→ 点击后局部扫描（200ms）→ 热点复核（1s，轮转）
 *       → 全树兜底（默认 300s）；</li>
 *   <li>反射深扫只对自定义组件做，每秒限额、同一组件 60 秒内只扫一次；</li>
 *   <li>表格单元格用渲染器包装，只在绘制时翻译，绝不改写 TableModel 里的数据；</li>
 *   <li>文件 IO 全部走后台单线程，EDT 上不碰磁盘。</li>
 * </ul>
 *
 * <h2>安全边界</h2>
 * <ul>
 *   <li>可编辑文本框、下拉框选项、表格里的 URL/主机名/状态码一律不碰；</li>
 *   <li>任何异常都在本类内部吞掉 —— EDT 崩了等于 Burp 崩了；</li>
 *   <li>词库查不到就保持英文，绝不猜测；</li>
 *   <li>{@link #stop()} 会停掉全部定时器、摘掉 AWT 钩子和组件监听、落盘漏译日志。</li>
 * </ul>
 */
public final class Translator implements AWTEventListener {

    // ================================================================== 常量

    /** 组件树最大递归深度。防御性上限，正常界面远小于这个值。 */
    private static final int MAX_DEPTH = 80;

    /** 热点组件表上限。动态文本（状态栏、计数器）最多就这么多。 */
    private static final int HOT_LIMIT = 600;

    /** 每轮热点复核处理的组件数。轮转覆盖，避免一次遍历几百个组件。 */
    private static final int HOT_BATCH = 100;

    /** 同一组件两次反射深扫的最小间隔。字段里的文字常常是构造后才填进去。 */
    private static final long DEEP_MIN_GAP_MS = 60_000L;

    /** 反射深扫的每秒配额。日志/历史面板每秒 new 组件，不限流会持续吃 CPU。 */
    private static final int DEEP_PER_SECOND = 60;

    /** 没扫到文字的组件，过多久允许再试一次（避免空组件吃掉每秒配额）。 */
    private static final long DEEP_EMPTY_RESCAN_MS = 10_000L;

    /** HTML 富文本的处理长度上限。再长就是整篇帮助文档，翻它没有意义且很贵。 */
    private static final int MAX_HTML_CHARS = 20_000;

    /** 纯文本富文本的处理长度上限。 */
    private static final int MAX_PLAIN_RICH_CHARS = 8_000;

    /** 字体覆盖涉及的 UI 键。只列 Swing 用来画文字的键。 */
    private static final String[] FONT_KEYS = {
            "Label.font", "Button.font", "CheckBox.font", "RadioButton.font", "ToggleButton.font",
            "Menu.font", "MenuItem.font", "CheckBoxMenuItem.font", "RadioButtonMenuItem.font",
            "MenuBar.font", "TabbedPane.font", "TitledBorder.font", "ToolTip.font",
            "ComboBox.font", "List.font", "Table.font", "TableHeader.font",
            "OptionPane.messageFont", "OptionPane.buttonFont", "Panel.font",
            "InternalFrame.titleFont", "PopupMenu.font", "SplitPane.font", "ScrollPane.font",
            "Tree.font", "ProgressBar.font"
    };

    /** 输入框灰色占位符的客户端属性名（新旧 Swing 版本各一个）。 */
    private static final String[] PLACEHOLDER_KEYS = {"JTextField.placeholderText", "promptText"};

    /**
     * 当前活着的实例。
     *
     * <p>Burp 里"移除扩展 → 再添加"时，卸载钩子并不保证被调用；旧实例的定时器和属性监听
     * 仍在跑。两个实例同时改写同一棵组件树会互相触发 PropertyChange，把 EDT 塞满。
     * 所以新实例启动时先掐死旧实例。
     */
    private static final AtomicReference<Translator> CURRENT = new AtomicReference<>();

    // ================================================================== 状态

    private final MontoyaApi api;
    private final Path home;
    private final MissingLog missing = new MissingLog();
    private final TextTranslator engine = new TextTranslator(missing);

    /** 插件是否仍在工作。卸载后所有回调都以它为准，直接返回。 */
    private volatile boolean alive;

    /** 全部登记的定时器，{@link #stop()} 一次性停掉。 */
    private final List<javax.swing.Timer> timers = new ArrayList<>();

    /** 后台单线程：词库解析与漏译落盘。绝不在 EDT 上做磁盘 IO。 */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "burp-suite-zh-cn-plugin-io");
        t.setDaemon(true);
        return t;
    });

    private Path dictPath;
    private volatile Dictionary dict = Dictionary.EMPTY;
    private volatile long dictStamp = -1L;
    private final AtomicBoolean dictionaryLoading = new AtomicBoolean();

    /**
     * 已挂上的属性监听器：组件 -> 监听器。用弱键，组件被回收时条目自动消失。
     * {@link #stop()} 会逐条摘掉，否则组件树还攥着这个类加载器。
     */
    private final Map<Component, PropertyWatcher> watchers = new WeakHashMap<>();

    /**
     * Document / TableColumnModel 监听器的摘除动作。
     * 键是监听目标（弱引用），值只持有目标的弱引用，避免"目标 → 监听器 → 组件"的强引用链。
     */
    private final Map<Object, Runnable> detachers = new WeakHashMap<>();

    /**
     * 已套上翻译渲染器的表格列（弱引用）。卸载时逐列还原，否则表格会一直攥着
     * 插件 ClassLoader 里的 {@code TranslatingCellRenderer} 类，导致扩展无法卸载干净。
     */
    private final List<WeakReference<TableColumn>> translatedColumns = new ArrayList<>();

    /** 已扫过的弹出层窗口；全部关闭后清空，下次重新扫。 */
    private final Set<Window> sweptPopups = newSet();

    /** 需要高频复核的组件（动态文本）。用弱引用，不挡组件回收。 */
    private final List<WeakReference<Component>> hot = new ArrayList<>();
    private final Set<Component> hotSet = newSet();
    private final Map<Component, String> hotText = new WeakHashMap<>();
    private int hotCursor;

    /** 反射深扫的时间戳与补扫次数。 */
    private final Map<Component, Long> deepStamp = new WeakHashMap<>();
    private final Map<Component, Integer> deepRetry = new WeakHashMap<>();
    private long deepSecond;
    private int deepUsed;

    /** 我们正在改写文本。挡住 setText 触发的递归事件。 */
    private boolean rewriting;
    /** 富文本改写中。{@code setText} 会触发 DocumentEvent，用它挡住自触发。 */
    private boolean richBusy;

    /** 统计量，只用于日志。 */
    private int rewrites;
    private int deepRewrites;

    private boolean fontApplied;
    private Component pendingClick;
    private javax.swing.Timer clickTimer;
    private javax.swing.Timer resweepTimer;

    Translator(MontoyaApi api) {
        this.api = api;
        this.home = Paths.get(System.getProperty("user.home", "."), ".burp-i18n");
    }

    // ================================================================== 生命周期

    /** 启动：接管旧实例、装载词库、挂 AWT 钩子、排定时器。 */
    void start() {
        try {
            Translator previous = CURRENT.getAndSet(this);
            if (previous != null && previous != this) {
                previous.stop(); // 同一类加载器内的旧实例
            }
            // Burp 每次 Add 都新建 ClassLoader，静态变量跨不过去，只能顺着 AWT 事件总线找
            killStaleInstances();
            alive = true;

            try {
                Files.createDirectories(home);
            } catch (Throwable t) {
                // 便携环境、受控企业机可能没有可写的 home；不阻塞启动，后面用内存词库
                log("cannot create data directory: " + t);
            }
            try {
                dictPath = resolveDictionaryPath();
                loadDictionaryNow();
            } catch (Throwable t) {
                log("dictionary load failed, falling back to bundled: " + t);
                useBundledDictionary();
            }
            registerHook();
            applyFontIfNeeded();

            log("build " + buildVersion());
            // 必须在挂上钩子之后统计：正常应为 1，大于 1 说明旧实例没死透
            log("AWT listeners on bus: " + countInstances() + " (1 = clean)");
            log("dictionary: " + dict.size() + " entries / " + dict.ruleCount() + " rules <- "
                    + (dictPath == null ? "<bundled in memory>" : dictPath));
            log("missed strings will be collected in " + home.resolve("missing.txt"));

            SwingUtilities.invokeLater(this::startTimers);
        } catch (Throwable t) {
            log("init failed: " + t);
            try {
                stop();
            } catch (Throwable ignored) {
                // 启动失败时尽力清理
            }
        }
    }

    /**
     * 彻底停机：停定时器、摘 AWT 钩子、摘全部监听、落盘漏译、关后台线程。
     *
     * <p>卸载扩展时必须走到这里，否则旧实例会在后台继续扫组件、写文件，
     * 再 Add 一份就变成两个实例互相触发事件，界面卡死。
     *
     * <p>声明为 {@code public} 是有意的：跨类加载器清理旧实例只能靠反射调用它。
     */
    public void stop() {
        alive = false;
        CURRENT.compareAndSet(this, null);

        for (javax.swing.Timer timer : new ArrayList<>(timers)) {
            try {
                timer.stop();
            } catch (Throwable ignored) {
                // 单个定时器停止失败不影响其它
            }
        }
        timers.clear();
        if (clickTimer != null) {
            clickTimer.stop();
            clickTimer = null;
        }

        try {
            Toolkit.getDefaultToolkit().removeAWTEventListener(this);
        } catch (Throwable ignored) {
            // 已经摘掉或从未挂上
        }

        detachListenersSafely();
        io.shutdownNow();
        try {
            missing.flush(home); // 同步落盘，卸载后就没人再写了
        } catch (Throwable ignored) {
            // 卸载阶段不抛异常
        }

        hot.clear();
        hotSet.clear();
        hotText.clear();
        deepStamp.clear();
        deepRetry.clear();
        translatedColumns.clear();
        sweptPopups.clear();
        missing.clear();
        dictionaryLoading.set(false);
        Reflect.clearCaches();
        dict = Dictionary.EMPTY;
        pendingClick = null;
    }

    // ================================================================== 词库

    /**
     * 决定用哪个词库文件，必要时把 jar 内置词库释放到磁盘。
     *
     * <p>路径优先级：{@code -Dburp.i18n.dict=<path>} → {@code ~/.burp-i18n/dict.json}
     */
    private Path resolveDictionaryPath() throws IOException {
        String override = System.getProperty("burp.i18n.dict");
        if (override != null && !override.trim().isEmpty()) {
            Path custom = Paths.get(override.trim());
            if (Files.isReadable(custom)) {
                log("using dictionary from system property: " + custom);
                return custom;
            }
            log("burp.i18n.dict is not readable, falling back to default: " + custom);
        }

        Path target = home.resolve("dict.json");
        byte[] bundled = readBundledDictionary();

        if (!Files.exists(target)) {
            if (bundled != null) {
                Files.write(target, bundled);
                log("wrote bundled dictionary to " + target);
            }
            return target;
        }
        if (bundled != null) {
            upgradeDictionaryIfNewer(target, bundled);
        }
        return target;
    }

    private static byte[] readBundledDictionary() {
        try (InputStream in = Translator.class.getResourceAsStream("/dict.json")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 内置词库版本更高时自动升级磁盘副本，旧文件留备份。
     * 想锁住自己的改动，把 {@code config.version} 改成一个很大的数即可。
     */
    private void upgradeDictionaryIfNewer(Path target, byte[] bundled) {
        try {
            int bundledVersion = Dictionary.parseVersion(new String(bundled, StandardCharsets.UTF_8));
            int diskVersion = Dictionary.parseVersion(Files.readString(target, StandardCharsets.UTF_8));
            if (bundledVersion <= diskVersion) {
                return;
            }
            Path backup = home.resolve("dict.json.bak-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
            // 同一秒内连续升级时备份名会撞车，必须允许覆盖，否则整个升级会被跳过
            Files.copy(target, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // 先写临时文件再原子替换，避免写到一半崩溃留下半截词库
            Path temp = home.resolve("dict.json.tmp");
            Files.write(temp, bundled);
            try {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log("dictionary upgraded v" + diskVersion + " -> v" + bundledVersion
                    + ", backup: " + backup.getFileName());
        } catch (Throwable t) {
            // 升级失败就用旧词库，不影响启动
            log("dictionary upgrade skipped: " + t.getMessage());
        }
    }

    /**
     * 启动时的同步装载：第一次扫描之前必须拿到词库。
     *
     * <p>读盘/解析失败会抛出，由 {@link #start()} 回退到 jar 内置词库；
     * 而文件存在但内容为空时按用户意愿保持英文，不回退。
     */
    private void loadDictionaryNow() throws IOException {
        if (dictPath == null) {
            return;
        }
        Dictionary loaded = Dictionary.load(dictPath);
        if (loaded.isEmpty()) {
            log("dictionary is empty, UI stays English: " + dictPath);
            return;
        }
        dict = loaded;
        dictStamp = Files.getLastModifiedTime(dictPath).toMillis();
    }

    /** 磁盘不可用时，直接用 jar 内置词库工作，保证插件仍然能翻界面。 */
    private void useBundledDictionary() {
        byte[] bundled = readBundledDictionary();
        if (bundled == null) {
            return;
        }
        try {
            Dictionary loaded = Dictionary.fromJson(new String(bundled, StandardCharsets.UTF_8));
            if (!loaded.isEmpty()) {
                dict = loaded;
                dictStamp = -1L;
                log("using bundled dictionary in memory: " + loaded.size() + " entries");
            }
        } catch (Throwable t) {
            log("bundled dictionary is broken: " + t);
        }
    }

    /**
     * 词库热重载：只比对修改时间，变了才读文件。
     *
     * <p>文件读取与 JSON 解析都在后台线程完成，解析好后回 EDT 换快照并重扫，
     * 用户保存词库时不会感到界面停顿。
     */
    private void reloadDictionaryIfChanged() {
        if (!alive || dictPath == null || !dictionaryLoading.compareAndSet(false, true)) {
            return;
        }
        io.execute(() -> {
            try {
                long stamp = Files.getLastModifiedTime(dictPath).toMillis();
                if (stamp == dictStamp) {
                    return;
                }
                Dictionary loaded = Dictionary.load(dictPath);
                if (loaded.isEmpty()) {
                    log("dictionary is empty, keeping current: " + dictPath);
                    dictStamp = stamp; // 避免每 8 秒重试同一个坏文件
                    return;
                }
                SwingUtilities.invokeLater(() -> applyDictionary(loaded, stamp));
            } catch (Throwable t) {
                // 用户很可能正在编辑、JSON 还没写完，下一轮会自动重试
                log("dictionary reload failed, keeping current: " + t.getMessage());
            } finally {
                dictionaryLoading.set(false);
            }
        });
    }

    private void applyDictionary(Dictionary loaded, long stamp) {
        if (!alive) {
            return;
        }
        Dictionary previous = dict;
        dict = loaded;
        dictStamp = stamp;
        if (loaded.applyFont && !fontApplied) {
            applyFont(loaded);
            fontApplied = true;
        }
        log("dictionary reloaded: " + loaded.size() + " entries / " + loaded.ruleCount() + " rules");
        // 允许运行期调整兜底扫描周期，否则改配置得重启 Burp
        if (previous.resweepSeconds != loaded.resweepSeconds) {
            restartResweepTimer();
        }
        // 之前因"没词条"而停下的组件要重新过一遍，否则要等 5 分钟兜底扫描
        hotText.clear();
        deepStamp.clear();
        deepRetry.clear();
        sweepAll("reload");
    }

    // ================================================================== 定时器

    /** 全部节奏集中在这里，调性能只看这一个方法。 */
    private void startTimers() {
        if (!alive) {
            return;
        }
        // 首轮两次：界面基本在 1.5 秒内构建完成，8 秒补一次迟到的组件
        once(1500, () -> sweepAll("initial"));
        once(8000, () -> sweepAll("late"));

        every(8000, this::reloadDictionaryIfChanged); // 词库热重载
        every(5000, () -> io.execute(() -> missing.flush(home))); // 漏译落盘
        every(1000, this::retranslateHot); // 状态栏这类每秒重写的文本
        every(500, this::translateOpenMenus); // 轻量弹层不发窗口事件，只能轮询

        startResweepTimer();
    }

    /** 兜底扫描周期可能被运行期改，先停旧再按新周期重建。 */
    private void restartResweepTimer() {
        if (resweepTimer != null) {
            resweepTimer.stop();
            timers.remove(resweepTimer);
            resweepTimer = null;
        }
        startResweepTimer();
    }

    private void startResweepTimer() {
        int seconds = dict.resweepSeconds;
        if (seconds <= 0) {
            return;
        }
        javax.swing.Timer timer = new javax.swing.Timer(seconds * 1000, e -> guard(() -> sweepAll("resweep")));
        timer.setRepeats(true);
        timers.add(timer);
        timer.start();
        resweepTimer = timer;
    }

    /** 一次性任务。触发后自动从登记表摘掉，避免定时器列表越积越长。 */
    private void once(int delayMs, Runnable task) {
        if (!alive) {
            return;
        }
        javax.swing.Timer timer = new javax.swing.Timer(delayMs, null);
        timer.setRepeats(false);
        timer.addActionListener(e -> {
            timers.remove(timer);
            timer.stop();
            guard(task);
        });
        timers.add(timer);
        timer.start();
    }

    /** 周期性任务。 */
    private void every(int delayMs, Runnable task) {
        if (!alive) {
            return;
        }
        javax.swing.Timer timer = new javax.swing.Timer(delayMs, e -> guard(task));
        timer.setRepeats(true);
        timers.add(timer);
        timer.start();
    }

    /** 定时任务统一入口：停机后直接返回，异常一律吞掉。 */
    private void guard(Runnable task) {
        if (!alive) {
            return;
        }
        try {
            task.run();
        } catch (Throwable ignored) {
            // 单个任务失败不影响后续轮次
        }
    }

    // ================================================================== AWT 钩子

    private void registerHook() {
        long mask = AWTEvent.COMPONENT_EVENT_MASK
                | AWTEvent.CONTAINER_EVENT_MASK
                | AWTEvent.WINDOW_EVENT_MASK
                | AWTEvent.MOUSE_EVENT_MASK;
        Toolkit.getDefaultToolkit().addAWTEventListener(this, mask);

        if (api == null) {
            return; // 离线测试模式
        }
        try {
            api.extension().registerUnloadingHandler(() -> {
                try {
                    stop();
                } catch (Throwable ignored) {
                    // 卸载阶段忽略一切异常
                }
            });
        } catch (Throwable t) {
            log("register unload hook failed: " + t);
        }
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        if (!alive || dict.isEmpty()) {
            return;
        }
        try {
            int id = event.getID();
            if (event instanceof ContainerEvent containerEvent) {
                if (id == ContainerEvent.COMPONENT_ADDED) {
                    // getComponent() 是容器本身，新增的是 getChild()；要扫整棵子树
                    translateTree(containerEvent.getChild(), 0, false);
                }
            } else if (event instanceof WindowEvent windowEvent) {
                if (id == WindowEvent.WINDOW_OPENED) {
                    Window window = windowEvent.getWindow();
                    translateTree(window, 0, false);
                    // 对话框先显示、后填内容，当场扫必然扫到空，补两次延迟扫描
                    scheduleWindowRescan(window, 900);
                    scheduleWindowRescan(window, 2600);
                }
            } else if (event instanceof MouseEvent mouseEvent) {
                // MouseEvent 也是 ComponentEvent，必须排在 ComponentEvent 之前判断
                if (id == MouseEvent.MOUSE_RELEASED) {
                    // 自绘卡片点击后原地重绘、字段被重置，没有 setText，属性监听抓不到
                    queueClickSweep(mouseEvent.getComponent());
                }
            } else if (event instanceof ComponentEvent componentEvent) {
                if (id == ComponentEvent.COMPONENT_SHOWN) {
                    translateComponent(componentEvent.getComponent(), false);
                }
            }
        } catch (Throwable ignored) {
            // 绝不让异常冒泡到 EDT
        }
    }

    /** 点击去抖：一次交互常伴随多次事件，200ms 内只扫最后一处。 */
    private void queueClickSweep(Component c) {
        if (c == null) {
            return;
        }
        pendingClick = c;
        if (clickTimer == null) {
            clickTimer = new javax.swing.Timer(200, e -> {
                Component target = pendingClick;
                pendingClick = null;
                if (alive && target != null) {
                    sweepAround(target);
                }
            });
            clickTimer.setRepeats(false);
        }
        clickTimer.restart();
    }

    /** 只扫点击处及其兄弟/父级，代价很小；大范围重建由容器事件负责。 */
    private void sweepAround(Component c) {
        if (!alive || dict.isEmpty()) {
            return;
        }
        try {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> sweepAround(c));
                return;
            }
            translateComponent(c, false);
            Container parent = c.getParent();
            if (parent != null) {
                for (Component sibling : parent.getComponents()) {
                    if (sibling != c) {
                        translateComponent(sibling, false);
                    }
                }
                translateComponent(parent, false);
            }
        } catch (Throwable ignored) {
            // 绝不让异常冒泡
        }
    }

    /**
     * 弹出菜单是独立窗口/轻量弹层，既不在主窗口组件树里，打开时也不触发
     * {@code WINDOW_OPENED}。只能轮询当前菜单选择路径 + 可见弹层窗口，菜单一露头就翻。
     */
    private void translateOpenMenus() {
        if (!alive || dict.isEmpty()) {
            return;
        }
        boolean anyPopup = false;
        try {
            javax.swing.MenuElement[] path =
                    javax.swing.MenuSelectionManager.defaultManager().getSelectedPath();
            if (path != null && path.length > 0) {
                anyPopup = true;
                for (javax.swing.MenuElement element : path) {
                    if (element == null) {
                        continue;
                    }
                    Component c = element.getComponent();
                    if (c == null) {
                        continue;
                    }
                    translateComponent(c, false);
                    // 同一层的兄弟项不会被选中，必须跟着扫一遍
                    if (c instanceof Container container) {
                        for (Component child : container.getComponents()) {
                            translateComponent(child, false);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 菜单层异常不影响主体翻译
        }

        try {
            for (Window w : Window.getWindows()) {
                if (w == null || !w.isVisible() || w instanceof Frame || w instanceof Dialog) {
                    continue; // 主窗口和对话框由常规扫描负责
                }
                boolean popup = w instanceof JWindow
                        || w.getClass().getName().toLowerCase().contains("popup");
                if (!popup) {
                    continue;
                }
                anyPopup = true;
                if (sweptPopups.add(w)) {
                    translateTree(w, 0, false);
                }
            }
        } catch (Throwable ignored) {
            // 同上
        }

        if (!anyPopup) {
            sweptPopups.clear(); // 弹层都关了，下次重新扫
        }
    }

    // ================================================================== 扫描

    /** 全量扫描：遍历所有正在显示的窗口。用于首轮、兜底周期与词库重载。 */
    private void sweepAll(String reason) {
        if (!alive || dict.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();
        for (Window w : Window.getWindows()) {
            if (w == null || !w.isShowing()) {
                continue;
            }
            try {
                translateTree(w, 0, false);
            } catch (Throwable ignored) {
                // 单个窗口失败不影响其它窗口
            }
        }
        log("sweep(" + reason + ") " + (System.nanoTime() - startedAt) / 1_000_000 + "ms: "
                + rewrites + " rewritten / " + deepRewrites + " deep / " + missing.size() + " missing");
    }

    /**
     * 递归翻译一棵组件树。
     *
     * @param forceDeep 忽略反射深扫的 60 秒节流。窗口刚弹出或字段为空补扫时用
     */
    private void translateTree(Component root, int depth, boolean forceDeep) {
        if (root == null || depth > MAX_DEPTH || !alive || dict.isEmpty()) {
            return;
        }
        // 单元格渲染器是临时组件，里面的文字是用户数据，整棵子树都不用扫
        if (root instanceof javax.swing.CellRendererPane) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> {
                try {
                    translateTree(root, depth, forceDeep);
                } catch (Throwable ignored) {
                    // 绝不让异常冒泡到 EDT
                }
            });
            return;
        }
        try {
            translateComponent(root, forceDeep);
        } catch (Throwable ignored) {
            // 单个组件失败不能中断整窗扫描
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                try {
                    translateTree(child, depth + 1, forceDeep);
                } catch (Throwable ignored) {
                    // 同上：坏掉一个子树，其它子树照常
                }
            }
        }
    }

    /**
     * 翻译单个组件。
     *
     * <p>整个方法都在 {@code rewriting} 保护下执行：我们自己 {@code setText} 会同步触发
     * 属性监听，不挡住的话每个组件都要白白再查一遍词库（含 12 条 noise 正则）。
     */
    private void translateComponent(Component c, boolean forceDeep) {
        boolean previous = rewriting;
        rewriting = true;
        try {
            translateComponentInner(c, forceDeep);
        } catch (Throwable ignored) {
            // 单个组件失败不影响其它组件
        } finally {
            rewriting = previous;
        }
    }

    /** 翻译单个组件。复杂度集中在类型分发上，每类组件一个独立小方法。 */
    private void translateComponentInner(Component c, boolean forceDeep) {
        if (!alive || dict.isEmpty() || c == null
                || c.getClass().getName().startsWith("cn.burp.i18n")) {
            return; // 已卸载、词库为空，或自己的界面
        }
        // 表格/列表/树的单元格渲染器是临时组件，里面的文字是用户数据。
        // 表格本身由 installCellRenderers() 在绘制层处理，这里必须跳过，
        // 否则 URL、主机名会被当成界面文案记进 missing.txt。
        if (c.getParent() instanceof javax.swing.CellRendererPane) {
            return;
        }

        if (c instanceof JEditorPane || c instanceof JTextPane) {
            translateRichText((JTextComponent) c);
            installWatcher(c);
            translateTooltip((JComponent) c);
            translateBorderTitle((JComponent) c);
            return;
        }
        if (c instanceof JTextComponent text) {
            translatePlainText(text);
            translateTooltip(text);
            // 纯文本组件也要挂监听并翻边框标题，否则 Burp 重新 setText 后会回英文
            installWatcher(c);
            translateBorderTitle(text);
            return;
        }

        // 只要被扫到一次就挂监听，之后 Burp 再怎么 setText 都能当场接住
        installWatcher(c);

        if (c instanceof AbstractButton button) {
            translateButton(button);
        } else if (c instanceof JLabel label) {
            translateLabel(label);
        } else if (c instanceof JToolTip tip) {
            String before = tip.getTipText();
            TextTranslator.Result result = engine.translate(dict, c, before, true);
            if (result.changed()) {
                tip.setTipText(result.text());
                rewrites++;
            }
        } else if (c instanceof JTabbedPane tabs) {
            retitleTabs(tabs);
        } else if (c instanceof JTable table) {
            translateTableHeader(table);
            installCellRenderers(table);
        } else if (c instanceof Window window) {
            translateWindowTitle(window);
        } else if (Reflect.isCustom(c)) {
            // 自定义组件：有 getText/setText 就能直接翻；没有的话交给下面的反射深扫
            translateByReflection(c);
        }

        if (c instanceof JComponent component) {
            translateTooltip(component);
            translateBorderTitle(component);
        }

        if (dict.deepScan && Reflect.isBurpComponent(c) && deepDue(c, forceDeep)) {
            // 自绘组件的文字不在任何 Swing 属性里，只能反射改写字段
            deepScanComponent(c);
        }
    }

    private void translateButton(AbstractButton button) {
        String before = button.getText();
        if (before != null && !before.isEmpty()) {
            TextTranslator.Result result = engine.translate(dict, button, before, true);
            if (result.changed()) {
                button.setText(result.text());
                rewrites++;
                markHotIfDynamic(button, result);
            }
        }
    }

    private void translateLabel(JLabel label) {
        String before = label.getText();
        if (before != null && !before.isEmpty()) {
            TextTranslator.Result result = engine.translate(dict, label, before, true);
            if (result.changed()) {
                label.setText(result.text());
                rewrites++;
                markHotIfDynamic(label, result);
            }
        }
    }

    private void translateTooltip(JComponent component) {
        String before = component.getToolTipText();
        if (before == null || before.isEmpty()) {
            return;
        }
        TextTranslator.Result result = engine.translate(dict, component, before, true);
        if (result.changed()) {
            component.setToolTipText(result.text());
        }
    }

    /**
     * 只读文本框：整串命中词库才动，且不记漏译（里面多是 URL、哈希等数据）。
     * 可编辑框的内容一律不碰，但灰色占位文字是界面文案，要翻。
     */
    private void translatePlainText(JTextComponent component) {
        if (component.isEditable()) {
            translatePlaceholder(component);
            return;
        }
        Document document = component.getDocument();
        // 先用文档长度挡住大文本：getText() 会把整个文档复制出来
        if (document == null || document.getLength() > MAX_PLAIN_RICH_CHARS) {
            translatePlaceholder(component);
            return;
        }
        String before = component.getText();
        if (before != null && !before.isEmpty()) {
            TextTranslator.Result result = engine.translate(dict, component, before, false);
            if (result.changed()) {
                component.setText(result.text());
                rewrites++;
                markHotIfDynamic(component, result);
            }
        }
        translatePlaceholder(component);
    }

    /** 自定义组件：只要提供 getText/setText 就认，否则留给反射深扫。 */
    private void translateByReflection(Component c) {
        String before = Reflect.quickText(c);
        if (before == null || before.isEmpty() || before.length() > MAX_PLAIN_RICH_CHARS) {
            return;
        }
        TextTranslator.Result result = engine.translate(dict, c, before, true);
        if (result.changed()) {
            Reflect.setText(c, result.text());
            rewrites++;
            markHotIfDynamic(c, result);
        }
    }

    /** 页签标题单独抽出来：属性监听器也要用，避免两处逻辑漂移。 */
    private void retitleTabs(JTabbedPane tabs) {
        // setTitleAt 会同步触发 indexForTitle；用 rewriting 挡住嵌套的整套重翻
        boolean previous = rewriting;
        rewriting = true;
        try {
            boolean changed = false;
            for (int i = 0; i < tabs.getTabCount(); i++) {
                String before = tabs.getTitleAt(i);
                TextTranslator.Result result = engine.translate(dict, tabs, before, true);
                if (result.changed()) {
                    tabs.setTitleAt(i, result.text());
                    rewrites++;
                    changed = true;
                    markHotIfDynamic(tabs, result);
                }
                String tip = tabs.getToolTipTextAt(i);
                if (tip != null) {
                    TextTranslator.Result tipResult = engine.translate(dict, tabs, tip, true);
                    if (tipResult.changed()) {
                        tabs.setToolTipTextAt(i, tipResult.text());
                    }
                }
            }
            if (changed) {
                tabs.repaint();
            }
        } finally {
            rewriting = previous;
        }
    }

    /** 列头是 {@code setHeaderValue}，不是组件 text；新列加入时补一次翻译。 */
    private void translateTableHeader(JTable table) {
        JTableHeader header = table.getTableHeader();
        if (header == null || header.getColumnModel() == null) {
            return;
        }
        TableColumnModel columns = header.getColumnModel();
        boolean changed = false;
        for (int i = 0; i < columns.getColumnCount(); i++) {
            Object value = columns.getColumn(i).getHeaderValue();
            if (!(value instanceof String text)) {
                continue;
            }
            TextTranslator.Result result = engine.translate(dict, table, text, true);
            if (result.changed()) {
                columns.getColumn(i).setHeaderValue(result.text());
                rewrites++;
                changed = true;
            }
        }
        if (changed) {
            header.repaint();
        }
    }

    /**
     * 给表格的每一列套一层"绘制时翻译"的渲染器。
     *
     * <p>不改 {@code TableModel}：表格里的值可能是 Burp 的项目数据，写回会破坏排序、筛选、
     * 按值比较，甚至被持久化。渲染器只在画的时候替换显示文本，数据保持原样，
     * 也不可能把用户数据（"xxx is empty"）改成半中半英。
     */
    private void installCellRenderers(JTable table) {
        if (!dict.translateCells) {
            return;
        }
        TableColumnModel columns = table.getColumnModel();
        if (columns == null) {
            return;
        }
        for (int i = 0; i < columns.getColumnCount(); i++) {
            try {
                TableColumn column = columns.getColumn(i);
                TableCellRenderer current = column.getCellRenderer();
                if (current instanceof TranslatingCellRenderer wrapper) {
                    if (wrapper.owner() == this) {
                        continue; // 已经是当前实例装的，别重复套
                    }
                    current = wrapper.delegate(); // 旧实例（上一轮 ClassLoader）的包装，拆掉重装
                }
                TableCellRenderer delegate = current;
                if (delegate == null) {
                    delegate = table.getDefaultRenderer(table.getColumnClass(i));
                }
                if (delegate instanceof TranslatingCellRenderer other) {
                    delegate = other.delegate();
                }
                if (delegate == null) {
                    continue;
                }
                column.setCellRenderer(new TranslatingCellRenderer(this, delegate, table));
                translatedColumns.add(new WeakReference<>(column));
            } catch (Throwable ignored) {
                // 单列出问题不影响其它列
            }
        }
    }

    /**
     * 单元格渲染器包装：绘制前把显示文本换成译文。
     *
     * <p>只持有 {@link Translator} 的弱引用，避免表格（可能比扩展活得久）把旧 ClassLoader 钉死。
     * 只做精确词条匹配，不跑正则 —— 单元格里可能是用户数据，规则不应介入。
     */
    private static final class TranslatingCellRenderer implements TableCellRenderer {

        private final WeakReference<Translator> owner;
        private final TableCellRenderer delegate;
        private final List<String> scope;

        TranslatingCellRenderer(Translator owner, TableCellRenderer delegate, JTable table) {
            this.owner = new WeakReference<>(owner);
            this.delegate = delegate;
            this.scope = owner.dict.scopes.isEmpty()
                    ? Collections.emptyList() : TextTranslator.scopeChain(table);
        }

        Translator owner() {
            return owner.get();
        }

        TableCellRenderer delegate() {
            return delegate;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            Component c = delegate.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            Translator translator = owner.get();
            if (c == null || translator == null || !translator.alive || !(value instanceof String text)) {
                return c;
            }
            Dictionary current = translator.dict;
            if (!current.translateCells || !isTranslatableCell(text)
                    || HtmlText.containsCjk(text)) {
                return c;
            }
            List<String> scopes = scope;
            if (!current.scopes.isEmpty() && scopes.isEmpty()) {
                scopes = TextTranslator.scopeChain(table);
            }
            String hit = current.lookup(scopes, text);
            if (hit == null || hit.equals(text)) {
                return c;
            }
            if (c instanceof JLabel label) {
                label.setText(hit);
            } else if (c instanceof AbstractButton button) {
                button.setText(hit);
            } else if (c instanceof JTextComponent textComponent) {
                textComponent.setText(hit);
            }
            return c;
        }
    }

    /** 单元格候选判定：短语，或"首字母大写的纯字母单词"。 */
    private static boolean isTranslatableCell(String s) {
        if (s.length() < 4 || s.length() > 200) {
            return false;
        }
        if (s.indexOf(' ') > 0) {
            return true;
        }
        // Group / Tab / Branch / Window 这类单词；含数字或符号的数据进不来
        if (!Character.isUpperCase(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isLetter(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void translateWindowTitle(Window window) {
        String title;
        if (window instanceof Frame frame) {
            title = frame.getTitle();
        } else if (window instanceof Dialog dialog) {
            title = dialog.getTitle();
        } else {
            return;
        }
        if (title == null || title.isEmpty()) {
            return;
        }
        TextTranslator.Result result = engine.translate(dict, window, title, true);
        if (!result.changed()) {
            return;
        }
        try {
            if (window instanceof Frame frame) {
                frame.setTitle(result.text());
            } else if (window instanceof Dialog dialog) {
                dialog.setTitle(result.text());
            }
            rewrites++;
        } catch (Throwable ignored) {
            // 某些窗口不允许改标题
        }
    }

    private void translateBorderTitle(JComponent component) {
        Border border = component.getBorder();
        if (!(border instanceof TitledBorder titled)) {
            return;
        }
        String before = titled.getTitle();
        if (before == null || before.isEmpty()) {
            return;
        }
        TextTranslator.Result result = engine.translate(dict, component, before, true);
        if (!result.changed()) {
            return;
        }
        titled.setTitle(result.text());
        component.revalidate();
        component.repaint();
    }

    /** 输入框的灰色占位文字不是 text 属性，是客户端属性，得单独翻。 */
    private void translatePlaceholder(JTextComponent component) {
        if (!(component instanceof JComponent)) {
            return;
        }
        JComponent jc = (JComponent) component;
        for (String key : PLACEHOLDER_KEYS) {
            try {
                Object value = jc.getClientProperty(key);
                if (value instanceof String original) {
                    TextTranslator.Result result = engine.translate(dict, component, original, true);
                    if (result.changed()) {
                        jc.putClientProperty(key, result.text());
                    }
                }
            } catch (Throwable ignored) {
                // 个别组件不接受该属性
            }
        }
    }

    // ================================================================== 富文本

    /**
     * {@link JEditorPane} / {@link JTextPane} 的文字存在 Document 里。
     *
     * <p>{@code HTMLDocument.getText(0,len)} 返回去标签后的纯文本，而
     * {@code JEditorPane.getText()} 返回 HTML 源码 —— 只能在源码层分段替换再 setText，
     * 否则会丢掉 {@code <b>}、{@code <br>} 等结构。
     */
    private void translateRichText(JTextComponent component) {
        if (!alive || richBusy) {
            return;
        }
        richBusy = true;
        try {
            // setText 可能换掉 Document，监听器会失效；每次进来先确保当前实例已挂上
            watchDocument(component);

            if (component instanceof JEditorPane editor
                    && "text/html".equalsIgnoreCase(editor.getContentType())) {
                if (!dict.translateHtml || editor.isEditable()) {
                    return; // 可编辑 HTML 面板里是用户内容，不碰
                }
                String source = editor.getText();
                if (source == null || source.isEmpty() || source.length() > MAX_HTML_CHARS) {
                    return;
                }
                // Document 的 base URL 指向外部主机时，这几乎肯定是用户请求回来的网页
                // （Render 视图、响应预览）。整块跳过，连整段压平都不做。
                if (isExternalPage(editor)) {
                    return;
                }
                // 疑似用户网页时只做"整段压平后整串命中"：命中才改，否则原样不动
                String converted = TextTranslator.looksLikeWebPage(source)
                        ? engine.htmlFlat(dict, component, source)
                        // 只读富文本可能是用户响应，不记漏译，避免把用户数据写进 missing.txt
                        : engine.html(dict, component, source, false);
                if (converted != null && !converted.equals(source)) {
                    editor.setText(converted);
                    editor.repaint();
                    rewrites++;
                }
                return;
            }

            if (component.isEditable()) {
                return;
            }
            Document document = component.getDocument();
            // 先用文档长度挡住大文本：getText() 会把整个文档复制出来
            if (document == null || document.getLength() > MAX_PLAIN_RICH_CHARS) {
                return;
            }
            String text = component.getText();
            if (text == null || text.isEmpty() || text.length() > MAX_PLAIN_RICH_CHARS) {
                return;
            }
            TextTranslator.Result result = engine.translate(dict, component, text, false);
            if (result.changed()) {
                component.setText(result.text());
                rewrites++;
            }
        } catch (Throwable ignored) {
            // 富文本结构千变万化，任何异常都只放弃这一个组件
        } finally {
            richBusy = false;
        }
    }

    /**
     * 文档的 base URL 是否指向"用户请求回来的外部网站"。
     *
     * <p>只认 http(s) 且主机不是 PortSwigger 文档站的情况。Burp 自己的帮助面板
     * 通常来自本地资源或 portswigger.net；Render 视图、响应预览则会把 base 设成目标站点，
     * localhost / 内网目标同样按用户页面处理。这是比"看有没有 &lt;script&gt;"更可靠的判据。
     */
    private static boolean isExternalPage(JEditorPane editor) {
        try {
            if (isExternalUrl(editor.getPage())) {
                return true;
            }
            Document document = editor.getDocument();
            if (document instanceof javax.swing.text.html.HTMLDocument html) {
                return isExternalUrl(html.getBase());
            }
        } catch (Throwable ignored) {
            // 拿不到 base 就按原来的启发式处理
        }
        return false;
    }

    private static boolean isExternalUrl(URL url) {
        if (url == null) {
            return false;
        }
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            return false;
        }
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        // 只放行 PortSwigger 自己的文档站；localhost / 内网目标同样算用户页面
        return !(host.equals("portswigger.net") || host.endsWith(".portswigger.net"));
    }

    // ================================================================== 监听器

    /**
     * 给组件挂属性监听。
     *
     * <p>这道钩子解决"点一下又变回英文"：Burp 的点击/切页签/刷新绝大多数是
     * 复用旧组件 + 重新 setText，既不产生 ContainerEvent 也不产生 ComponentEvent。
     *
     * <p>只给"我们真的会改写"的属性挂监听，给表格、面板挂上等于白耗事件。
     */
    private void installWatcher(Component c) {
        if (c == null || !SwingUtilities.isEventDispatchThread() || watchers.containsKey(c)) {
            return;
        }
        if (!(c instanceof JComponent) && !(c instanceof Window)) {
            return;
        }
        if (!(c instanceof Window)) {
            JComponent jc = (JComponent) c;
            boolean textCapable = c instanceof AbstractButton || c instanceof JLabel
                    || c instanceof JToolTip || c instanceof JTabbedPane || c instanceof JTable
                    || c instanceof JTextComponent || Reflect.isCustom(c);
            boolean titledBorder = jc.getBorder() instanceof TitledBorder;
            boolean hasTooltip = jc.getToolTipText() != null;
            // 普通面板（JPanel/JScrollPane…）不挂监听，否则几千个面板白占内存和事件；
            // 它们的气泡/边框标题由全量扫描和点击局部扫描兜底
            if (!textCapable && !titledBorder && !hasTooltip) {
                return;
            }
            PropertyWatcher watcher = new PropertyWatcher(c);
            watchers.put(c, watcher);
            if (textCapable) {
                if (c instanceof AbstractButton || c instanceof JLabel || Reflect.isCustom(c)) {
                    attach(jc, "text", watcher);
                }
                if (c instanceof JToolTip) {
                    // JToolTip.setTipText 发的属性名是小写 tiptext
                    attach(jc, "tiptext", watcher);
                }
                if (c instanceof JTabbedPane) {
                    // setTitleAt 触发的是 indexForTitle，不是 text
                    attach(jc, "indexForTitle", watcher);
                }
                if (c instanceof JTable table) {
                    // setModel 时换表，需要重新挂列监听
                    attach(jc, "model", watcher);
                    watchColumns(table);
                }
                if (c instanceof JEditorPane || c instanceof JTextPane) {
                    // setText 若换掉 Document，只有 "document" 属性会通知
                    attach(jc, "document", watcher);
                    watchDocument((JTextComponent) c);
                } else if (c instanceof JTextComponent text && !text.isEditable()) {
                    // 只读文本域（Burp 大量用它展示标签式文本）也要能接住 setText；
                    // 可编辑框不挂 DocumentListener，否则用户每敲一个键都会触发一轮翻译
                    attach(jc, "document", watcher);
                    watchDocument(text);
                }
            }
            if (textCapable || hasTooltip) {
                attach(jc, "toolTipText", watcher);
            }
            if (textCapable || titledBorder) {
                attach(jc, "border", watcher);
            }
            return;
        }

        PropertyWatcher watcher = new PropertyWatcher(c);
        watchers.put(c, watcher);
        // 窗口标题走顶层 "title" 属性
        attach((Window) c, "title", watcher);
    }

    private void attach(Component component, String property, PropertyWatcher watcher) {
        component.addPropertyChangeListener(property, watcher);
        watcher.names.add(property);
    }

    /**
     * 一个组件一个监听器实例。
     *
     * <p>必须留实例：{@code removePropertyChangeListener} 要传同一个对象才能摘掉。
     * 组件引用用弱引用，否则 map 的 value 会强引用 key，WeakHashMap 形同虚设。
     */
    private final class PropertyWatcher implements PropertyChangeListener {

        private final WeakReference<Component> target;
        private final List<String> names = new ArrayList<>(4);

        PropertyWatcher(Component target) {
            this.target = new WeakReference<>(target);
        }

        @Override
        public void propertyChange(PropertyChangeEvent event) {
            Component c = target.get();
            if (c != null) {
                onProperty(c, event);
            }
        }
    }

    private void onProperty(Component c, PropertyChangeEvent event) {
        if (!alive || rewriting || dict.isEmpty()) {
            return; // 已卸载，或这次改动是我们自己刚写下去的
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> onProperty(c, event));
            return;
        }
        try {
            rewriting = true;
            switch (event.getPropertyName()) {
                case "text" -> {
                    if (event.getNewValue() instanceof String value) {
                        applyText(c, value);
                    }
                }
                case "toolTipText" -> {
                    if (event.getNewValue() instanceof String value && c instanceof JComponent jc) {
                        TextTranslator.Result result = engine.translate(dict, c, value, true);
                        if (result.changed()) {
                            jc.setToolTipText(result.text());
                        }
                    }
                }
                case "tiptext" -> {
                    if (c instanceof JToolTip tip && event.getNewValue() instanceof String value) {
                        TextTranslator.Result result = engine.translate(dict, c, value, true);
                        if (result.changed()) {
                            tip.setTipText(result.text());
                        }
                    }
                }
                case "indexForTitle" -> {
                    if (c instanceof JTabbedPane tabs) {
                        retitleTabs(tabs);
                    }
                }
                case "border" -> {
                    if (c instanceof JComponent jc) {
                        translateBorderTitle(jc);
                    }
                }
                case "title" -> {
                    if (c instanceof Window window) {
                        translateWindowTitle(window);
                    }
                }
                case "model" -> {
                    if (c instanceof JTable table) {
                        watchColumns(table);
                        installCellRenderers(table);
                        translateTableHeader(table);
                    }
                }
                case "document" -> {
                    if (c instanceof JTextComponent text) {
                        watchDocument(text);
                        // 不能直接调 scheduleRichRetranslate：此刻 rewriting 还是 true，会被它挡掉。
                        // 推到本轮事件末尾，等状态复位后再翻。
                        SwingUtilities.invokeLater(() -> {
                            if (alive && !richBusy && text.isShowing()) {
                                translateRichText(text);
                            }
                        });
                    }
                }
                default -> {
                    // 其它属性不关心
                }
            }
        } catch (Throwable ignored) {
            // 绝不让异常冒泡到 EDT
        } finally {
            rewriting = false;
        }
    }

    /** 把 Burp 刚写进来的英文换成中文（只处理有 text 语义的控件）。 */
    private void applyText(Component c, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        TextTranslator.Result result = engine.translate(dict, c, text, true);
        if (!result.changed()) {
            return;
        }
        if (c instanceof AbstractButton button) {
            button.setText(result.text());
        } else if (c instanceof JLabel label) {
            label.setText(result.text());
        } else if (Reflect.isCustom(c)) {
            Reflect.setText(c, result.text());
        } else {
            return;
        }
        rewrites++;
        markHotIfDynamic(c, result);
    }

    /** 列头变更时补翻。切换视图会换新的 TableColumnModel，必须按实例去重。 */
    private void watchColumns(JTable table) {
        JTableHeader header = table.getTableHeader();
        if (header == null) {
            return;
        }
        TableColumnModel columns = header.getColumnModel();
        if (columns == null || detachers.containsKey(columns)) {
            return;
        }
        WeakReference<JTable> tableRef = new WeakReference<>(table);
        TableColumnModelListener listener = new TableColumnModelListener() {
            @Override
            public void columnAdded(TableColumnModelEvent e) {
                JTable target = tableRef.get();
                if (target == null) {
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    if (alive) {
                        translateTableHeader(target);
                        installCellRenderers(target);
                    }
                });
            }

            @Override
            public void columnRemoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnMoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnMarginChanged(ChangeEvent e) {
            }

            @Override
            public void columnSelectionChanged(ListSelectionEvent e) {
            }
        };
        columns.addColumnModelListener(listener);
        WeakReference<TableColumnModel> columnsRef = new WeakReference<>(columns);
        detachers.put(columns, () -> {
            TableColumnModel c = columnsRef.get();
            if (c != null) {
                c.removeColumnModelListener(listener);
            }
        });
    }

    /**
     * 富文本的 Document 一变（Burp 每次 setText 都可能换新 Document）就重新翻。
     *
     * <p>监听器里只放组件的弱引用，避免给组件加一条永久的 GC 根。
     */
    private void watchDocument(JTextComponent component) {
        Document document;
        try {
            document = component.getDocument();
        } catch (Throwable t) {
            return;
        }
        if (document == null || detachers.containsKey(document)) {
            return;
        }
        WeakReference<JTextComponent> componentRef = new WeakReference<>(component);
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleRichRetranslate(componentRef);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                // 我们自己的改写也会走这里，不重复触发
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleRichRetranslate(componentRef);
            }
        };
        document.addDocumentListener(listener);
        WeakReference<Document> documentRef = new WeakReference<>(document);
        detachers.put(document, () -> {
            Document d = documentRef.get();
            if (d != null) {
                d.removeDocumentListener(listener);
            }
        });
    }

    private void scheduleRichRetranslate(WeakReference<JTextComponent> ref) {
        if (!alive || rewriting || richBusy) {
            return;
        }
        scheduleRichRetranslate(ref.get());
    }

    private void scheduleRichRetranslate(JTextComponent target) {
        if (!alive || target == null || rewriting || richBusy || target.isEditable()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (alive && !rewriting && !richBusy && target.isShowing()) {
                translateRichText(target);
            }
        });
    }

    /**
     * 在 EDT 上摘监听。卸载回调可能来自 Burp 的后台线程，而 {@code watchers}/{@code detachers}
     * 是普通 {@link WeakHashMap}，和 EDT 上的扫描并发会撞出并发修改异常。
     */
    private void detachListenersSafely() {
        if (SwingUtilities.isEventDispatchThread()) {
            detachAllListeners();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(this::detachAllListeners);
        } catch (Throwable t) {
            // EDT 已关闭时只能直接摘，尽力而为
            detachAllListeners();
        }
    }

    /** 摘掉全部属性监听与 Document/列模型监听。 */
    private void detachAllListeners() {
        for (Map.Entry<Component, PropertyWatcher> entry : new ArrayList<>(watchers.entrySet())) {
            Component target = entry.getKey();
            PropertyWatcher watcher = entry.getValue();
            if (target == null) {
                continue;
            }
            for (String name : watcher.names) {
                try {
                    if (target instanceof JComponent jc) {
                        jc.removePropertyChangeListener(name, watcher);
                    } else if (target instanceof Window window) {
                        window.removePropertyChangeListener(name, watcher);
                    }
                } catch (Throwable ignored) {
                    // 组件可能已被销毁
                }
            }
        }
        watchers.clear();

        for (Runnable detach : new ArrayList<>(detachers.values())) {
            try {
                detach.run();
            } catch (Throwable ignored) {
                // 目标可能已被销毁
            }
        }
        detachers.clear();
        uninstallCellRenderers();
    }

    /** 把表格列上的翻译渲染器换回原渲染器，摘掉对插件 ClassLoader 的最后一条引用。 */
    private void uninstallCellRenderers() {
        for (WeakReference<TableColumn> ref : new ArrayList<>(translatedColumns)) {
            TableColumn column = ref.get();
            if (column == null) {
                continue;
            }
            TableCellRenderer current = column.getCellRenderer();
            if (current instanceof TranslatingCellRenderer wrapper && wrapper.owner() == this) {
                try {
                    column.setCellRenderer(wrapper.delegate());
                } catch (Throwable ignored) {
                    // 表格可能已被销毁
                }
            }
        }
        translatedColumns.clear();
    }

    // ================================================================== 热点复核

    /** 动态文本组件登记为热点。它们的文本由 Burp 周期性重写，需要每秒复核。 */
    private void markHotIfDynamic(Component c, TextTranslator.Result result) {
        if (result.dynamic()) {
            markHot(c);
        }
    }

    private void markHot(Component c) {
        if (c == null || !alive) {
            return;
        }
        if (hot.size() >= HOT_LIMIT) {
            compactHot();
            if (hot.size() >= HOT_LIMIT) {
                return;
            }
        }
        if (hotSet.add(c)) {
            hot.add(new WeakReference<>(c));
        }
    }

    /** 清掉已被回收的弱引用，避免热点表被"死条目"占满。 */
    private void compactHot() {
        int write = 0;
        for (int read = 0; read < hot.size(); read++) {
            if (hot.get(read).get() != null) {
                hot.set(write++, hot.get(read));
            }
        }
        if (write < hot.size()) {
            hot.subList(write, hot.size()).clear();
        }
        if (hotCursor > hot.size()) {
            hotCursor = 0;
        }
    }

    /**
     * 只复核热点表里的组件，不整树遍历。
     *
     * <p>两道限流：文本没变直接跳过；每次只处理 {@link #HOT_BATCH} 个，轮转覆盖。
     */
    private void retranslateHot() {
        if (!alive || dict.isEmpty() || hot.isEmpty()) {
            return;
        }
        int size = hot.size();
        int done = 0;
        for (int guard = 0; guard < size && done < HOT_BATCH; guard++) {
            if (hotCursor >= hot.size()) {
                hotCursor = 0;
            }
            if (hot.isEmpty()) {
                break;
            }
            Component c = hot.get(hotCursor).get();
            if (c == null) {
                hot.remove(hotCursor); // 组件已被回收
                continue;
            }
            hotCursor++;
            done++;
            try {
                if (!c.isShowing()) {
                    continue;
                }
                String before = Reflect.quickText(c);
                if (before != null && before.equals(hotText.get(c))) {
                    continue; // 文本没变，上一轮已经翻过了
                }
                translateComponent(c, false);
                if (before != null) {
                    String after = Reflect.quickText(c);
                    if (after != null) {
                        hotText.put(c, after);
                    }
                }
            } catch (Throwable ignored) {
                // 单个组件失败无所谓
            }
        }
    }

    // ================================================================== 反射深扫

    /**
     * 该不该做反射深扫。
     *
     * @param force 窗口刚弹出、或上次因字段为空而补扫时为 {@code true}
     */
    private boolean deepDue(Component c, boolean force) {
        Long last = deepStamp.get(c);
        boolean dueByTime = last == null || System.currentTimeMillis() - last >= DEEP_MIN_GAP_MS;
        if (!force && !dueByTime) {
            return false;
        }
        return deepBudget();
    }

    /** 每秒配额。日志面板这类每秒 new 组件的地方，不限流会一直全反射。 */
    private boolean deepBudget() {
        long second = System.currentTimeMillis() / 1000L;
        if (second != deepSecond) {
            deepSecond = second;
            deepUsed = 0;
        }
        return ++deepUsed <= DEEP_PER_SECOND;
    }

    private void deepScanComponent(Component c) {
        List<String> scope = dict.scopes.isEmpty()
                ? Collections.emptyList() : TextTranslator.scopeChain(c);
        int[] hits = {0};
        boolean sawText = Reflect.deepScan(c, (field, value) -> {
            String hit = translateField(scope, value);
            if (hit != null) {
                hits[0]++;
            }
            return hit;
        });
        if (hits[0] > 0) {
            deepRewrites += hits[0];
            if (c.isShowing()) {
                c.repaint();
            }
        }
        if (sawText) {
            deepStamp.put(c, System.currentTimeMillis());
            deepRetry.remove(c);
        } else {
            // 字段还是空的：过 10 秒再允许扫一次，而不是每次被访问都重新到期。
            // 否则几十个空组件就能把每秒 60 个的深扫配额吃光。
            deepStamp.put(c, System.currentTimeMillis() - DEEP_MIN_GAP_MS + DEEP_EMPTY_RESCAN_MS);
            // 文字一般在这之后才填进来，安排几次短延迟补扫
            scheduleDeepRetry(c);
        }
    }

    /** 深扫回调：只认整串命中，命中就返回译文；没命中的记进 missing.txt。 */
    private String translateField(List<String> scope, String value) {
        if (value == null || value.isEmpty() || value.length() > 400
                || HtmlText.containsCjk(value)) {
            return null;
        }
        String probe = HtmlText.probe(value);
        String hit = dict.lookupLoose(scope, probe);
        if (hit == null) {
            hit = dict.applyRules(probe);
        }
        if (hit == null) {
            hit = engine.numbered(dict, scope, probe);
        }
        if (hit == null) {
            missing.note(dict, probe);
            return null;
        }
        return hit.equals(value) ? null : hit;
    }

    /** 每个组件最多补扫 3 次，防止"空 → 补扫 → 还是空"无限循环。 */
    private void scheduleDeepRetry(Component c) {
        Integer previous = deepRetry.get(c);
        int attempt = previous == null ? 0 : previous;
        if (attempt >= 3) {
            // 补扫用完就回到普通 60 秒节奏，别每 10 秒再试
            deepStamp.put(c, System.currentTimeMillis());
            return;
        }
        deepRetry.put(c, attempt + 1);
        once(600 + attempt * 900, () -> {
            if (!alive) {
                return;
            }
            deepStamp.remove(c);
            translateComponent(c, true);
        });
    }

    /** 窗口显示后延迟再扫一次：说明文字常常晚于窗口显示才填进去。 */
    private void scheduleWindowRescan(Window window, int delayMillis) {
        once(delayMillis, () -> {
            if (window == null || !window.isShowing()) {
                return;
            }
            translateTree(window, 0, true);
        });
    }

    // ================================================================== 字体

    private void applyFontIfNeeded() {
        Dictionary current = dict;
        if (!current.applyFont || fontApplied) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            applyFont(current);
            fontApplied = true;
        });
    }

    /**
     * 覆盖 UI 字体。只有中文显示成方块时才需要，所以默认关闭。
     * 必须在 EDT 上执行：{@code updateComponentTreeUI} 会重建整棵树。
     */
    private void applyFont(Dictionary dictionary) {
        try {
            String family = dictionary.fontFamily.isEmpty() ? pickCjkFamily() : dictionary.fontFamily;
            Font fallback = UIManager.getFont("Label.font");
            for (String key : FONT_KEYS) {
                // 只换字体族，保留 Burp 原本的字号和字形；原来固定 12pt 会让整个界面变小
                Font old = UIManager.getFont(key);
                int style = old != null ? old.getStyle()
                        : (fallback != null ? fallback.getStyle() : Font.PLAIN);
                int size = old != null ? old.getSize()
                        : (fallback != null ? fallback.getSize() : 12);
                UIManager.put(key, new Font(family, style, size));
            }
            for (Window w : Window.getWindows()) {
                if ((w instanceof Frame || w instanceof Dialog) && w.isShowing()) {
                    try {
                        SwingUtilities.updateComponentTreeUI(w);
                    } catch (Throwable ignored) {
                        // 个别组件重建失败无妨
                    }
                }
            }
            log("UI font overridden to " + family);
        } catch (Throwable t) {
            log("apply font failed: " + t);
        }
    }

    private static String pickCjkFamily() {
        String[] preferred = {"Microsoft YaHei UI", "Microsoft YaHei", "微软雅黑", "PingFang SC",
                "Noto Sans CJK SC", "Source Han Sans SC", "SimHei", "SimSun"};
        try {
            String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            for (String want : preferred) {
                for (String has : available) {
                    if (has.equalsIgnoreCase(want)) {
                        return has;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 拿不到字体列表就用逻辑字体
        }
        return Font.SANS_SERIF;
    }

    // ================================================================== 旧实例清理

    /**
     * 拆掉 AWT 自己的包装。
     *
     * <p>{@code addAWTEventListener} 会把监听器包成 {@code AWTEventListenerProxy}，
     * {@code getAWTEventListeners()} 返回的是代理。不拆开就永远匹配不到旧实例。
     */
    private static Object unwrapListener(AWTEventListener listener) {
        try {
            if (listener instanceof java.awt.event.AWTEventListenerProxy) {
                // JDK 21 把 getEventListener() 改名成 getListener()，两个名字都试
                for (String name : new String[]{"getListener", "getEventListener"}) {
                    try {
                        java.lang.reflect.Method m = listener.getClass().getMethod(name);
                        m.setAccessible(true);
                        Object inner = m.invoke(listener);
                        if (inner != null) {
                            return inner;
                        }
                    } catch (Throwable ignored) {
                        // 换下一个名字
                    }
                }
            }
        } catch (Throwable ignored) {
            // 取不到就用原对象
        }
        return listener;
    }

    /**
     * 找出仍挂在 AWT 事件总线上的旧实例并停机。
     *
     * <p>不能靠静态变量：Burp 每次 Add 都新建 ClassLoader，旧实例的静态字段与新实例无关。
     * 但 AWT 事件总线是 JVM 级的，顺着它找过去直接调 {@code stop()} 最可靠。
     */
    private void killStaleInstances() {
        try {
            for (AWTEventListener listener : Toolkit.getDefaultToolkit().getAWTEventListeners()) {
                if (listener == null) {
                    continue;
                }
                Object real = unwrapListener(listener);
                if (real == null || real == this
                        || !real.getClass().getName().equals(Translator.class.getName())) {
                    continue;
                }
                try {
                    java.lang.reflect.Method stopMethod = real.getClass().getMethod("stop");
                    // 跨 ClassLoader 属于不同运行时包，public 方法也要放开访问检查
                    stopMethod.setAccessible(true);
                    stopMethod.invoke(real);
                    log("stopped stale instance from a previous classloader");
                } catch (Throwable ignored) {
                    // 反射失败就交给卸载钩子，不能因为清理失败挡住启动
                }
            }
        } catch (Throwable ignored) {
            // 取监听器列表失败不影响启动
        }
    }

    /** 当前挂在 AWT 总线上的同类实例数。正常恒为 1。 */
    private int countInstances() {
        int count = 0;
        try {
            for (AWTEventListener listener : Toolkit.getDefaultToolkit().getAWTEventListeners()) {
                Object real = unwrapListener(listener);
                if (real != null && real.getClass().getName().equals(Translator.class.getName())) {
                    count++;
                }
            }
        } catch (Throwable ignored) {
            return -1;
        }
        return count;
    }

    // ================================================================== 工具

    /** jar 的 Manifest 里写了"版本+构建时间"，读不到就退化成 dev。 */
    private static String buildVersion() {
        try {
            Package pkg = Translator.class.getPackage();
            String version = pkg == null ? null : pkg.getImplementationVersion();
            return version == null || version.isEmpty() ? "dev" : version;
        } catch (Throwable ignored) {
            return "dev";
        }
    }

    private void log(String message) {
        try {
            if (api != null) {
                api.logging().logToOutput("[burp-suite-zh-cn-plugin] " + message);
            } else {
                System.out.println("[burp-suite-zh-cn-plugin] " + message);
            }
        } catch (Throwable ignored) {
            // 日志通道不可用时静默
        }
    }

    /** 弱键集合：组件被回收后条目自动消失。 */
    private static <T> Set<T> newSet() {
        return Collections.newSetFromMap(new WeakHashMap<T, Boolean>());
    }
}
