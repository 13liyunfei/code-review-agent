package com.codereview.agent.core.i18n;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 对外文案国际化工具（静态访问 + Spring 注入）。
 *
 * <p>为开源适配多语言：审查报告 / 修复建议 / review 标题 / 质量报告等<b>开发者可见</b>文案
 * 通过 MessageSource 按语言解析；日志与代码注释保留中文（内部调试用，不参与国际化）。
 *
 * <p>语言由配置 {@code review.lang=zh|en} 决定（默认 {@code zh}）；测试或独立使用时
 * 未注入 MessageSource 则回退返回 key 本身。
 */
@Component
public class ReviewMessages {

    private static final Locale ZH = Locale.SIMPLIFIED_CHINESE;
    private static volatile Locale locale = ZH;
    private static volatile MessageSource source;

    /**
     * Spring 装配构造（显式 {@code @Autowired}，避免与测试便捷构造混淆）：注入消息源并解析语言配置。
     *
     * @param lang          {@code review.lang}（zh / en，默认 zh）
     * @param messageSource 消息源（i18n/messages*）
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ReviewMessages(@Value("${review.lang:zh}") String lang, MessageSource messageSource) {
        source = messageSource;
        locale = (lang != null && lang.toLowerCase().startsWith("en")) ? Locale.ENGLISH : ZH;
    }

    /** 测试便捷构造（仅设置语言，不注入消息源 → get 回退 key）。 */
    public ReviewMessages(String lang) {
        source = null;
        locale = (lang != null && lang.toLowerCase().startsWith("en")) ? Locale.ENGLISH : ZH;
    }

    /**
     * 解析一条消息（支持 {@code {0}} 占位符）。
     *
     * @param key  消息 key
     * @param args 占位参数
     * @return 本地化文本；未配置 MessageSource 或 key 缺失时回退 key 本身
     */
    public static String get(String key, Object... args) {
        MessageSource s = source;
        if (s == null) {
            return key;
        }
        return s.getMessage(key, args, key, locale);
    }

    /** 当前生效语言。 */
    public static Locale currentLocale() {
        return locale;
    }
}
