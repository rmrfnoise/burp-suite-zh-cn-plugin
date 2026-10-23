package cn.burp.i18n;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

/**
 * 扩展入口。
 *
 * <p>Burp 加载 jar 后扫描 {@link BurpExtension} 的实现并调用 {@link #initialize(MontoyaApi)}。
 * 这里只做两件事：给扩展起个中文名，然后把控制权交给 {@link Translator}。
 * 真正的逻辑全在 {@code Translator}，入口类保持"一眼看完"。
 *
 * <p>所有异常都在这里收口：扩展初始化抛异常会让 Burp 弹出"扩展加载失败"并禁用该扩展，
 * 而汉化插件最坏的情况也只是"界面没变化"，没有理由把 Burp 拖下水。
 */
public final class BurpChineseI18n implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        try {
            api.extension().setName("Burp 中文界面");
        } catch (Throwable ignored) {
            // setName 不可用（旧版 Burp）不影响主流程
        }
        try {
            new Translator(api).start();
        } catch (Throwable t) {
            try {
                api.logging().logToError("[burp-suite-zh-cn-plugin] failed to start: " + t);
            } catch (Throwable ignored) {
                // 日志通道也不可用时彻底放弃
            }
        }
    }
}
