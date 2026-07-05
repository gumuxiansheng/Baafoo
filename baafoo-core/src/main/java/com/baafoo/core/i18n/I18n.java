package com.baafoo.core.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight i18n message resolver for Baafoo Server.
 * <p>
 * No external dependency — loads {@code .properties} files from
 * {@code i18n/messages[_xx].properties} on the classpath.
 * Messages use {@link java.text.MessageFormat} placeholders ({@code {0}}, {@code {1}}, …).
 * <p>
 * Usage:
 * <pre>{@code
 * I18n i18n = I18n.of(Locale.SIMPLIFIED_CHINESE);
 * String msg = i18n.get("rule.not_found", "my-rule-id");
 * // → "规则未找到: my-rule-id"
 * }</pre>
 */
public final class I18n {

    private static final String BASE_NAME = "i18n/messages";
    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    private final Locale locale;
    private final Properties props;
    private final I18n fallback;

    private static final Map<Locale, I18n> CACHE = new ConcurrentHashMap<>();

    private I18n(Locale locale) {
        this.locale = locale;
        this.props = loadProperties(locale);
        if (!locale.equals(DEFAULT_LOCALE)) {
            this.fallback = of(DEFAULT_LOCALE);
        } else {
            this.fallback = null;
        }
    }

    /**
     * Get an I18n instance for the given locale (cached).
     */
    public static I18n of(Locale locale) {
        if (locale == null) return of(DEFAULT_LOCALE);
        return CACHE.computeIfAbsent(locale, I18n::new);
    }

    /**
     * Get an I18n instance for the given language tag (e.g. "zh-CN", "en").
     */
    public static I18n of(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return of(DEFAULT_LOCALE);
        return of(Locale.forLanguageTag(languageTag));
    }

    /**
     * Get the default (zh-CN) I18n instance.
     */
    public static I18n defaultInstance() {
        return of(DEFAULT_LOCALE);
    }

    /**
     * Resolve a message by key, with optional arguments.
     * <p>
     * Falls back to the default locale if the key is not found in the current locale,
     * and finally returns the raw key if no translation exists at all.
     */
    public String get(String key, Object... args) {
        String pattern = props.getProperty(key);
        if (pattern == null && fallback != null) {
            pattern = fallback.props.getProperty(key);
        }
        if (pattern == null) {
            return key;
        }
        if (args.length == 0) {
            return pattern;
        }
        try {
            return MessageFormat.format(pattern, args);
        } catch (IllegalArgumentException e) {
            return pattern;
        }
    }

    /**
     * Check whether a message key is defined for this locale.
     */
    public boolean containsKey(String key) {
        return props.containsKey(key) || (fallback != null && fallback.containsKey(key));
    }

    /**
     * Return the locale this instance was created for.
     */
    public Locale getLocale() {
        return locale;
    }

    private Properties loadProperties(Locale locale) {
        Properties result = new Properties();
        // Try the most specific resource first: messages_zh_CN.properties
        // Java resource bundle naming:
        //   messages_zh_CN.properties, messages_zh.properties, messages_en.properties, messages.properties
        // We keep it simple: messages_xx-XX or messages_xx
        String[] candidates = buildResourceNames(locale);
        for (String name : candidates) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
                if (is != null) {
                    try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                        result.load(reader);
                    }
                }
            } catch (IOException e) {
                // skip — this resource may simply not exist
            }
        }
        return result;
    }

    private String[] buildResourceNames(Locale locale) {
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if (!lang.isEmpty() && !country.isEmpty()) {
            return new String[]{
                    BASE_NAME + "_" + lang + "_" + country + ".properties",
                    BASE_NAME + "_" + lang + ".properties",
                    BASE_NAME + ".properties"
            };
        }
        if (!lang.isEmpty()) {
            return new String[]{
                    BASE_NAME + "_" + lang + ".properties",
                    BASE_NAME + ".properties"
            };
        }
        return new String[]{BASE_NAME + ".properties"};
    }
}
