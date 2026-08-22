package com.codereview.agent.core.prompt;

import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 从 classpath 加载提示词模板的加载器。
 *
 * <p>模板文件约定存放于 {@code resources/prompts/<name>.txt}（或 .ftl），
 * 与文档“Prompt 走模板文件”的设计一致。
 */
public class ClasspathPromptLoader implements PromptTemplateLoader {

    /** 模板目录。 */
    private static final String PROMPT_DIR = "prompts/";

    @Override
    public PromptTemplate load(String templateName) {
        String content = readTemplate(templateName);
        return new SimplePromptTemplate(templateName, content);
    }

    /**
     * 读取模板文件全文。
     *
     * @param templateName 模板名
     * @return 模板文本
     */
    private String readTemplate(String templateName) {
        // 优先 .ftl，其次 .txt
        for (String ext : new String[]{".ftl", ".txt"}) {
            String path = PROMPT_DIR + templateName + ext;
            try {
                ClassPathResource resource = new ClassPathResource(path);
                if (resource.exists()) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                        return reader.lines().collect(Collectors.joining("\n"));
                    }
                }
            } catch (IOException e) {
                // 继续尝试下一个扩展名
            }
        }
        throw new IllegalArgumentException("未找到提示词模板: " + templateName);
    }
}
