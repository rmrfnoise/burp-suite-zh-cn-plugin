package cn.burp.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 漏译收集器：把界面上没翻出来的英文攒起来，定期写进 {@code ~/.burp-i18n/missing.txt}。
 * 这是词库能持续长大的唯一来源。
 *
 * <p>三条原则：
 * <ul>
 *   <li>EDT 上只做内存收集，磁盘 IO 交给后台线程；</li>
 *   <li>同一条文本只记一次，避免一句话刷满日志；</li>
 *   <li>写盘时先换出缓冲、再在锁外写文件，界面不会被磁盘拖住。</li>
 * </ul>
 */
final class MissingLog {

    /** 最多收集多少条，防止长期运行撑爆内存和文件。 */
    private static final int MAX_ENTRIES = 3000;
    /** 超过这个长度的文本不记：多半是整段文档或报文，抄进词库没有意义。 */
    private static final int MAX_TEXT_LENGTH = 400;

    private final Set<String> seen = new HashSet<>();
    private final List<String> pending = new ArrayList<>();
    private boolean headerWritten;

    /**
     * 记录一条未命中的界面文本。
     *
     * @param dict 当前词库，用来过滤字符集名、URL、哈希等纯数据
     */
    void note(Dictionary dict, String text) {
        if (text == null || !dict.logMissing || text.length() > MAX_TEXT_LENGTH) {
            return;
        }
        // ignore 白名单里的词（Burp / HTTP / Repeater）本来就"永远不翻"，
        // 不应该反复写进 missing.txt 把词表搅脏
        if (dict.isIgnored(text)) {
            return;
        }
        // 带硬换行的文本压成一行（\n 写成字面量），否则日志会被拆散
        String line = text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                ? text.replace("\r", "").replace("\n", "\\n")
                : text;
        if (HtmlText.containsCjk(line) || dict.isNoise(line)) {
            return;
        }
        // 纯符号/纯数字不记；空格太多说明是排版或报文数据，也不记
        boolean hasLetter = false;
        int spaces = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (Character.isLetter(ch)) {
                hasLetter = true;
            } else if (Character.isWhitespace(ch)) {
                spaces++;
            }
        }
        if (!hasLetter || spaces > 40) {
            return;
        }
        synchronized (this) {
            if (seen.size() >= MAX_ENTRIES || !seen.add(line)) {
                return;
            }
            pending.add(line);
        }
    }

    /**
     * 把攒下的内容写盘。可在任意线程调用。
     *
     * @param home 插件数据目录（{@code ~/.burp-i18n}）
     */
    void flush(Path home) {
        List<String> batch;
        boolean withHeader;
        synchronized (this) {
            batch = new ArrayList<>(pending);
            pending.clear();
            withHeader = !headerWritten && !batch.isEmpty();
            headerWritten |= withHeader;
        }
        if (batch.isEmpty()) {
            return;
        }
        // 锁外拼串、写盘：磁盘慢不应该卡住界面
        StringBuilder sb = new StringBuilder(batch.size() * 24);
        if (withHeader) {
            sb.append("# 未命中的英文界面文本，可直接复制进 dict.json 的 groups\n");
            sb.append("# 生成时间 ").append(LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        }
        for (String s : batch) {
            sb.append(s).append('\n');
        }
        append(home.resolve("missing.txt"), sb.toString());
    }

    private static void append(Path file, String content) {
        try {
            Files.write(file, content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 磁盘满、目录只读都可能发生；写日志失败不能影响翻译
        }
    }

    /** 当前累计条数，仅用于日志。 */
    synchronized int size() {
        return seen.size();
    }

    synchronized void clear() {
        seen.clear();
        pending.clear();
    }
}
