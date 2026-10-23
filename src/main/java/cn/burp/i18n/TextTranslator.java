package cn.burp.i18n;

import java.awt.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本翻译引擎：输入一段界面文本，输出译文。
 *
 * <p>这一层只做字符串运算，不装监听器、不启动定时器、不写文件，便于单独推理和测试。
 *
 * <h2>匹配顺序（自上而下，先命中者生效）</h2>
 * <ol>
 *   <li>噪声过滤：字符集名、文件大小、URL、哈希，直接放弃；</li>
 *   <li>数字实体还原：{@code &#31449;&#28857;} 解成"站点"，避免已译中文被再翻一次；</li>
 *   <li>整串精确匹配 → 去首尾空白 → 解码实体 → 容错匹配（末尾句号）；</li>
 *   <li>硬换行压平（{@code text/plain} 面板会在句中插 {@code \n}）；</li>
 *   <li>正则规则（带数字的动态文本）；</li>
 *   <li>编号前缀（{@code 2. Live audit from Proxy…}）；</li>
 *   <li>HTML：整段压平 → 块级元素 → {@code <br>} 分行 → 标签间片段。</li>
 * </ol>
 *
 * <p>任何一步都不做"半句替换"：要么整段命中并整体替换，要么原样返回。
 * 局部替换会把 {@code Send to Repeater} 翻成"发送 to Repeater"这种四不像。
 */
final class TextTranslator {

    /**
     * 一次翻译的结果。
     *
     * @param text    输出文本（未命中时就是原文）
     * @param changed 是否真的改动了
     * @param dynamic 是否由正则/编号规则命中。这类文本 Burp 会周期性重写，
     *                调用方需要把组件登记为热点做高频复核，否则会闪回英文
     */
    record Result(String text, boolean changed, boolean dynamic) {
        static Result unchanged(String text) {
            return new Result(text, false, false);
        }
    }

    /** 块级元素。整篇说明文档压不平，就按块逐个查。 */
    private static final Pattern BLOCK = Pattern.compile(
            "(?is)<(p|li|div|section|article|h[1-6]|td|th|blockquote|dd|dt)(\\s[^>]*)?>(.*?)</\\1>");

    /** 按 {@code <br>} 切行。Burp 的说明常是"一行一句 + 手工换行"。 */
    private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");

    /** 行首项目符号（·、•、-、–、—、*），不算正文，去掉才好查词。 */
    private static final Pattern LINE_MARK = Pattern.compile("^([\u00b7\u2022\\-\u2013\u2014*]\\s*)");

    /** 连续空白，用于把被硬换行打断的整句压回一行。 */
    private static final Pattern SQUASH = Pattern.compile("\\s+");

    /** 整段压平后允许查词的最大长度。再长就是整篇文档，交给逐块/逐段处理。 */
    private static final int MAX_FLAT_PROBE = 1500;
    /** 单个块级元素的最大长度。 */
    private static final int MAX_BLOCK = 1500;
    /** 单行的最大长度。 */
    private static final int MAX_LINE = 900;
    /** 两个标签之间一段文本的最大长度。超过就不查，避免对整篇报文做一次哈希。 */
    private static final int MAX_SEGMENT = 2000;

    private final MissingLog missing;

    TextTranslator(MissingLog missing) {
        this.missing = missing;
    }

    /** 一次翻译用的小上下文，把作用域链等只算一次。 */
    private record Ctx(Dictionary dict, List<String> scope) {
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 翻译一段文本。
     *
     * @param comp          文本所在组件（用于作用域限定，可为 {@code null}）
     * @param recordMissing 未命中时是否记进 missing.txt。只读文本框传 {@code false}，
     *                      避免把 URL、哈希这类数据灌进词表
     */
    Result translate(Dictionary dict, Component comp, String text, boolean recordMissing) {
        if (text == null || text.isEmpty() || dict.isEmpty()) {
            return Result.unchanged(text);
        }

        // Burp 里有 "Capturing " 这种带尾空格的字面量，替换时必须把首尾空白原样还回去
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        String core = text.substring(start, end);
        if (core.isEmpty()) {
            return Result.unchanged(text);
        }
        // 负结果缓存：热点/兜底/点击都会反复扫同一批没命中的英文。
        // 只缓存纯文本；HTML 还要走分段策略，不能因为整串没命中就跳过。
        if (core.indexOf('<') < 0 && dict.isKnownMiss(core)) {
            // 之前已经确认没译文（第一次未命中时已经记过日志），直接短路。
            // 这里不再调 missing.note：note 内部的噪声正则本身就是热点路径的大头。
            return Result.unchanged(text);
        }
        if (dict.isNoise(core)) {
            // URL、日期、哈希这类数据也缓存，避免每秒重复跑 13 条噪声正则
            dict.rememberMiss(core);
            return Result.unchanged(text);
        }

        Ctx ctx = context(dict, comp);

        // 0) 数字实体：富文本会把已译中文再转义成 "&#31449;&#28857;…"，
        //    不解码就会当成"没汉字"的新文本，既翻不出，还会写进 missing.txt
        if (core.indexOf("&#") >= 0) {
            String decoded = HtmlText.decodeEntities(core);
            if (!decoded.equals(core)) {
                String hit = dict.lookup(ctx.scope(), decoded);
                if (hit != null || HtmlText.containsCjk(decoded)) {
                    return changed(wrap(text, start, end, hit != null ? hit : decoded));
                }
            }
        }

        // 已经是中文（含全角标点）就不用再查了。热组件每秒复核，这一步能省掉大量无谓查表
        if (HtmlText.containsCjk(core)) {
            return Result.unchanged(text);
        }

        // 1) 整串精确匹配（保留尾空格的字面量靠这一步）
        String hit = dict.lookup(ctx.scope(), text);
        // 2) 去掉首尾空白后再匹配
        if (hit == null && !core.equals(text)) {
            hit = dict.lookup(ctx.scope(), core);
        }
        // 2.5) 含 & 实体（&rsquo; / &nbsp; 等）时先还原再查
        if (hit == null && core.indexOf('&') >= 0) {
            String decoded = HtmlText.probe(core);
            if (!decoded.equals(core)) {
                hit = dict.lookupLoose(ctx.scope(), decoded);
            }
        }
        // 2.6) 容忍末尾句号有无
        if (hit == null) {
            hit = dict.lookupLoose(ctx.scope(), core);
        }
        // 2.7) 硬换行：text/plain 的 JEditorPane 会按显示宽度在句中插 \n
        if (hit == null && hasHardWrap(core)) {
            String squashed = SQUASH.matcher(core).replaceAll(" ").trim();
            if (!squashed.equals(core)) {
                hit = dict.lookupLoose(ctx.scope(), squashed);
                if (hit == null) {
                    hit = dict.applyRules(squashed);
                }
            }
        }
        if (hit != null) {
            return changed(wrap(text, start, end, hit));
        }

        // 3) 正则动态文本：Memory: 192.7MB of 3.97GB / Length: 107 / 5 highlights
        String replaced = dict.applyRules(core);
        if (replaced != null && !replaced.equals(core)) {
            return dynamic(wrap(text, start, end, replaced));
        }

        // 3.5) 序号前缀："2. Live audit from Proxy (all traffic)" 的编号是运行时拼的
        String numbered = numbered(dict, ctx.scope(), core);
        if (numbered != null) {
            return dynamic(wrap(text, start, end, numbered));
        }

        // 4) HTML 分段
        if (dict.translateHtml && text.indexOf('<') >= 0 && text.indexOf('>') > 0) {
            String converted = html(ctx, text, recordMissing);
            if (converted != null && !converted.equals(text)) {
                return changed(converted);
            }
            // 结构太怪导致压平失败时，粗暴去标签再查一次整句
            String brute = flatLookup(ctx, text);
            if (brute != null && !brute.equals(text)) {
                return changed(brute);
            }
            return Result.unchanged(text);
        }

        if (core.indexOf('<') < 0) {
            dict.rememberMiss(core);
        }
        if (recordMissing) {
            missing.note(dict, core);
        }
        return Result.unchanged(text);
    }

    // ------------------------------------------------------------------ HTML

    /**
     * 翻译一段 HTML，按"整段 → 块级元素 → 行 → 标签间片段"逐级退让。
     *
     * @return 新 HTML；没有任何改动返回 {@code null}
     */
    String html(Dictionary dict, Component comp, String src, boolean recordMissing) {
        return html(context(dict, comp), src, recordMissing);
    }

    private String html(Ctx ctx, String src, boolean recordMissing) {
        String flat = htmlFlat(ctx, src);
        if (flat != null) {
            return flat;
        }
        String byElements = byElements(ctx, src, recordMissing);
        if (byElements != null) {
            return byElements;
        }
        String byLines = byLines(ctx, src, recordMissing);
        if (byLines != null) {
            return byLines;
        }
        return bySegments(ctx, src, recordMissing);
    }

    /**
     * 只做"整段压平"这一种尝试。
     *
     * <p>用于渲染视图里疑似用户网页的内容：不能逐段乱翻，但整段压平后正好命中词条，
     * 说明它其实就是界面文案，此时替换是安全的。
     *
     * @return 新 HTML；未命中返回 {@code null}
     */
    String htmlFlat(Dictionary dict, Component comp, String src) {
        return htmlFlat(context(dict, comp), src);
    }

    private String htmlFlat(Ctx ctx, String src) {
        HtmlText.Flat flat = HtmlText.flatten(src);
        if (flat == null) {
            return null;
        }
        // 实体（&#8217; / &rsquo;）必须还原，否则撇号对不上，整句永远查不到
        String probe = HtmlText.probe(flat.text());
        if (probe.isEmpty() || probe.length() > MAX_FLAT_PROBE) {
            return null;
        }
        String hit = match(ctx, probe, true);
        if (hit == null) {
            return null;
        }
        // 保留原文首尾结构：空状态那种 <center><br><br>…</center> 一旦被压成
        // <html><body>译文</body></html>，居中和间距就没了，看起来像"图标和文字错位"
        if (flat.first() >= 0 && flat.last() > flat.first()) {
            String before = src.substring(0, flat.first());
            String after = src.substring(flat.last());
            // 短文案且原文没有对齐信息时补居中：中文比英文短，容易明显偏左。
            // 但如果文字正处在 <b>/<span> 这类内联元素里，插入块级 <center> 会造成非法嵌套，
            // 这种情况宁可不居中也不能破坏结构。
            if (flat.first() > 0 && probe.length() <= 60
                    && !HtmlText.containsIgnoreCase(before, "<center")
                    && !HtmlText.containsIgnoreCase(before, "align")
                    && !HtmlText.isInsideInlineElement(before)) {
                before = before + "<center>";
                after = "</center>" + after;
            }
            return before + HtmlText.escape(hit) + after;
        }
        return "<html><body>" + HtmlText.escape(hit) + "</body></html>";
    }

    /**
     * 按块级元素逐段翻译，保留标签结构。
     * Burp 的设置页说明常把帮助文档整篇塞进富文本面板，一次压平必然对不上。
     */
    private String byElements(Ctx ctx, String src, boolean recordMissing) {
        Matcher m = BLOCK.matcher(src);
        StringBuilder out = new StringBuilder(src.length() + 64);
        boolean changed = false;
        int last = 0;
        while (m.find()) {
            String inner = m.group(3);
            String hit = null;
            HtmlText.Flat flat = HtmlText.flatten(inner);
            if (flat != null) {
                String probe = HtmlText.probe(flat.text());
                if (!probe.isEmpty() && probe.length() <= MAX_BLOCK) {
                    hit = match(ctx, probe, true);
                    if (hit == null && recordMissing) {
                        missing.note(ctx.dict(), probe);
                    }
                }
            }
            out.append(src, last, m.start());
            if (hit != null) {
                out.append('<').append(m.group(1));
                if (m.group(2) != null) {
                    out.append(m.group(2));
                }
                out.append('>').append(HtmlText.escape(hit)).append("</").append(m.group(1)).append('>');
                changed = true;
            } else {
                out.append(m.group(0));
            }
            last = m.end();
        }
        out.append(src.substring(last));
        return changed ? out.toString() : null;
    }

    /**
     * 按 {@code <br>} 切成行，逐行整句匹配。Burp 的说明常是"一行一句 + 手工换行"，
     * 整篇压平对不上，但逐行每句都能命中。
     *
     * <p>只替换行内的文字，{@code <br>} 和行外的标签原样保留 —— 早期版本用 split
     * 重新拼回去，会把 {@code <center>} 这类外层结构吃掉。
     */
    private String byLines(Ctx ctx, String src, boolean recordMissing) {
        Matcher br = BR.matcher(src);
        StringBuilder out = new StringBuilder(src.length() + 32);
        boolean changed = false;
        boolean anyBreak = false;
        int last = 0;
        while (br.find()) {
            anyBreak = true;
            String line = src.substring(last, br.start());
            String result = translateLine(ctx, line, recordMissing);
            changed |= !result.equals(line);
            out.append(result).append(br.group());
            last = br.end();
        }
        if (!anyBreak) {
            return null;
        }
        String tail = src.substring(last);
        String result = translateLine(ctx, tail, recordMissing);
        changed |= !result.equals(tail);
        out.append(result);
        return changed ? out.toString() : null;
    }

    /** 翻译一行（内部可能还有标签，命中后按纯文本回写）。 */
    private String translateLine(Ctx ctx, String line, boolean recordMissing) {
        HtmlText.Flat flat = HtmlText.flatten(line);
        if (flat == null) {
            return line;
        }
        String probe = HtmlText.probe(flat.text());
        // 行首项目符号不属于正文，去掉才好查词，命中后原样补回
        String mark = "";
        Matcher mm = LINE_MARK.matcher(probe);
        if (mm.find()) {
            mark = mm.group(1);
            probe = probe.substring(mark.length());
        }
        if (probe.length() < 2 || probe.length() > MAX_LINE) {
            return line;
        }
        String hit = match(ctx, probe, true);
        if (hit == null) {
            if (recordMissing) {
                missing.note(ctx.dict(), probe);
            }
            return line;
        }
        // 只替换文字本身，行内前缀标签（<b> 等）和行尾标签都保留
        if (flat.first() >= 0 && flat.last() > flat.first()) {
            return line.substring(0, flat.first()) + mark + HtmlText.escape(hit) + line.substring(flat.last());
        }
        return mark + HtmlText.escape(hit);
    }

    /**
     * 最保守的一层：逐个标签切开，只翻标签之间的纯文本，且要求整段命中词条。
     * 所有原有标签都保留，不会破坏 HTML 结构。
     *
     * <p>{@code <style>} / {@code <script>} 内部原样跳过：CSS 选择器和 JS 代码不是界面文案，
     * 既不该翻译，也不该写进 missing.txt。
     */
    private String bySegments(Ctx ctx, String src, boolean recordMissing) {
        StringBuilder out = new StringBuilder(src.length() + 32);
        boolean changed = false;
        boolean inRawText = false;
        String rawTag = null;
        int i = 0;
        while (i < src.length()) {
            int lt = src.indexOf('<', i);
            if (lt < 0) {
                String seg = src.substring(i);
                if (inRawText) {
                    out.append(seg);
                } else {
                    String result = segment(ctx, seg, recordMissing);
                    changed |= !result.equals(seg);
                    out.append(result);
                }
                break;
            }
            if (lt > i) {
                String seg = src.substring(i, lt);
                if (inRawText) {
                    out.append(seg);
                } else {
                    String result = segment(ctx, seg, recordMissing);
                    changed |= !result.equals(seg);
                    out.append(result);
                }
            }
            int gt = src.indexOf('>', lt);
            if (gt < 0) {
                out.append(src, lt, src.length());
                break;
            }
            String raw = src.substring(lt + 1, gt).trim();
            boolean closing = raw.startsWith("/");
            String name = HtmlText.tagName(raw);
            out.append(src, lt, gt + 1);
            if (!closing && ("style".equals(name) || "script".equals(name))) {
                inRawText = true;
                rawTag = name;
            } else if (closing && name.equals(rawTag)) {
                inRawText = false;
                rawTag = null;
            }
            i = gt + 1;
        }
        return changed ? out.toString() : null;
    }

    /** 翻译两个标签之间的一段纯文本，保留其首尾空白。 */
    private String segment(Ctx ctx, String seg, boolean recordMissing) {
        if (seg.isEmpty() || seg.trim().isEmpty()) {
            return seg;
        }
        int start = 0;
        int end = seg.length();
        while (start < end && Character.isWhitespace(seg.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(seg.charAt(end - 1))) {
            end--;
        }
        String core = seg.substring(start, end);
        // 段里带实体时先还原（&rsquo; / &nbsp;），否则撇号、连字符一变就查不到
        String probe = core.indexOf('&') >= 0 ? HtmlText.probe(core) : core;
        String squashed = hasHardWrap(probe) ? SQUASH.matcher(probe).replaceAll(" ").trim() : probe;
        String hit = null;
        if (probe.length() <= MAX_SEGMENT) {
            hit = match(ctx, probe, true);
        }
        if (hit == null && !squashed.equals(probe) && squashed.length() <= MAX_SEGMENT) {
            hit = match(ctx, squashed, true);
        }
        if (hit == null) {
            // 日志里记压平后的形态，才能直接抄进词库
            if (recordMissing) {
                missing.note(ctx.dict(), squashed);
            }
            return seg;
        }
        return seg.substring(0, start) + HtmlText.escape(hit) + seg.substring(end);
    }

    /** 粗暴去标签后按整句查词，作为压平失败时的最后一道保险。 */
    private String flatLookup(Ctx ctx, String html) {
        String probe = HtmlText.probe(HtmlText.stripTags(html));
        if (probe.length() < 6 || probe.length() > MAX_FLAT_PROBE) {
            return null;
        }
        return match(ctx, probe, true);
    }

    // ------------------------------------------------------------------ 编号

    /**
     * Burp 给任务卡自动编号（{@code "1. Live passive crawl from Proxy (all traffic)"}），
     * 编号是运行时拼的，静态词条永远匹配不上。这里剥掉序号后翻译剩余部分。
     *
     * @return 译文；不适用或未命中返回 {@code null}
     */
    String numbered(Dictionary dict, List<String> scope, String core) {
        int dot = core.indexOf(". ");
        // 序号最长三位（1. / 12. / 123.），再长就不是序号了
        if (dot < 1 || dot > 3) {
            return null;
        }
        for (int i = 0; i < dot; i++) {
            if (!Character.isDigit(core.charAt(i))) {
                return null;
            }
        }
        String rest = core.substring(dot + 2);
        // 用 lookupLoose：Burp 的任务标题有时带句号，精确匹配会漏
        String translated = dict.lookupLoose(scope, rest);
        if (translated == null) {
            translated = dict.applyRules(rest);
        }
        if (translated == null || translated.equals(rest)) {
            return null;
        }
        return core.substring(0, dot) + ". " + translated;
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 是否为"真网页"（渲染视图里用户请求回来的页面）。
     *
     * <p>只认真正的交互结构。{@code <meta charset>} 不算 —— Burp 自己拼的说明面板也带它，
     * 早先因为误判，把整块设置页说明当用户网页跳过了。
     */
    static boolean looksLikeWebPage(String html) {
        return HtmlText.containsIgnoreCase(html, "<script")
                || HtmlText.containsIgnoreCase(html, "<form")
                || HtmlText.containsIgnoreCase(html, "<iframe")
                || HtmlText.containsIgnoreCase(html, "<input");
    }

    /** 组件自身到根的类简单名链，用于 {@code scopes} 限定译法。 */
    static List<String> scopeChain(Component c) {
        if (c == null) {
            return Collections.emptyList();
        }
        List<String> chain = new ArrayList<>(6);
        Component cur = c;
        int guard = 0;
        while (cur != null && guard++ < 16) {
            String name = cur.getClass().getName();
            int dot = name.lastIndexOf('.');
            chain.add(dot >= 0 ? name.substring(dot + 1) : name);
            cur = cur.getParent();
        }
        return chain;
    }

    /**
     * 统一的"查词 → 正则 → 编号"流程。
     *
     * @return 译文；未命中或与原文相同返回 {@code null}
     */
    private String match(Ctx ctx, String text, boolean allowNumbered) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        // 已经含中文的片段不翻译，也不该继续跑 45 条正则 —— HTML 逐段处理时
        // 大量段落是已译内容，这一步能省掉可观的重复匹配
        if (HtmlText.containsCjk(text)) {
            return null;
        }
        String hit = ctx.dict().lookupLoose(ctx.scope(), text);
        if (hit == null) {
            hit = ctx.dict().applyRules(text);
        }
        if (hit == null && allowNumbered) {
            hit = numbered(ctx.dict(), ctx.scope(), text);
        }
        return hit == null || hit.equals(text) ? null : hit;
    }

    private static Ctx context(Dictionary dict, Component comp) {
        return new Ctx(dict,
                dict.scopes.isEmpty() ? Collections.emptyList() : scopeChain(comp));
    }

    private static boolean hasHardWrap(String s) {
        return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\t') >= 0;
    }

    /** 把译文塞回原文的首尾空白之间。 */
    private static String wrap(String text, int start, int end, String core) {
        if (start == 0 && end == text.length()) {
            return core;
        }
        return text.substring(0, start) + core + text.substring(end);
    }

    private static Result changed(String text) {
        return new Result(text, true, false);
    }

    private static Result dynamic(String text) {
        return new Result(text, true, true);
    }
}
