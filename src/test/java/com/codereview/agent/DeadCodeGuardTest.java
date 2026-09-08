package com.codereview.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 死代码门禁：防止「设计了但未接入生产链路」的类再次溜进 main。
 *
 * <p>背景（2026-09-07 全仓审计）：仓库多次出现「能力组件实现完整、单测齐全，
 * 但生产零调用」的半死代码——{@code getRelevantExperiences}（经验读侧）、
 * {@code core/mq} 整包、{@code core/tool} 旧工具子系统、{@code ReflectionAgent}、
 * {@code ReviewReplay} 等。共同病根是 DemoRunner / 单测提供了"合法引用"，
 * 编译不告警、IDE 不标灰，半接入状态长期存活。
 *
 * <p>本测试对 {@code src/main/java} 下每个顶层类型做静态引用面扫描：
 * 若某个类型在<b>其它 main 文件</b>（去注释后）中零引用，且不满足下列
 * <b>有意的豁免</b>，即判定为死代码并失败：
 * <ul>
 *   <li>纯数据结构（包含 {@code model/dto/domain/exception}、声明为 record/enum/interface）；</li>
 *   <li>Spring 装配入口：{@code @Configuration}/{@code @SpringBootApplication}/
 *       {@code @RestController}/{@code @Controller}/{@code @EnableScheduling}；</li>
 *   <li>框架回调驱动：{@code @Scheduled}/{@code @PostConstruct}、实现
 *       {@code ApplicationRunner}/{@code CommandLineRunner}/{@code ApplicationListener}；</li>
 *   <li>进程入口：含 {@code public static void main}；配置绑定 {@code *Properties}。</li>
 * </ul>
 *
 * <p>注意：{@code @Component}/{@code @Service} 的类<b>不豁免</b>——容器注册不等于被消费，
 * 这正是历史上 {@code ReflectionAgent}/{@code ReviewReplay}/{@code ExternalToolRegistry}
 * 的形态（容器里有、无任何消费者）。
 */
class DeadCodeGuardTest {

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//.*$", Pattern.MULTILINE);
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]+");

    @Test
    void noOrphanTypesInMain() throws IOException {
        Path main = Path.of("src/main/java");
        assertTrue(Files.isDirectory(main), "src/main/java 不存在（测试须在项目根运行）");

        List<String> sources = new ArrayList<>();
        try (var stream = Files.walk(main)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toString()));
        }
        // typeName -> (文件, 去注释后源码, 原始源码)
        List<String[]> files = new ArrayList<>();     // [0]=path
        List<String> codeTexts = new ArrayList<>();   // 去注释
        List<String> rawTexts = new ArrayList<>();    // 原始（含注解）

        for (String p : sources) {
            String raw = Files.readString(Path.of(p));
            codeTexts.add(stripComments(raw));
            rawTexts.add(raw);
            files.add(new String[]{p});
        }

        List<String> orphans = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            String path = files.get(i)[0];
            String typeName = path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
            String code = codeTexts.get(i);
            String raw = rawTexts.get(i);

            if (exempt(typeName, path, raw)) {
                continue;
            }
            // 其它 main 文件中的引用数（不含自身文件，避免内部调用掩盖"无外部消费者"）
            int refs = 0;
            for (int j = 0; j < codeTexts.size(); j++) {
                if (i == j) {
                    continue;
                }
                refs += countWord(codeTexts.get(j), typeName);
            }
            if (refs == 0) {
                orphans.add(path.replace("src/main/java/", ""));
            }
        }

        assertTrue(orphans.isEmpty(),
                "检测到生产零引用的孤儿类型（设计未接入）：\n  " + String.join("\n  ", orphans)
                        + "\n处置：接入生产链路，或删除；若确为有意保留，补充豁免条件。");
    }

    /** 统计文本中作为独立词的 typeName 出现次数。 */
    private static int countWord(String text, String word) {
        int n = 0;
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            if (m.group().equals(word)) {
                n++;
            }
        }
        return n;
    }

    private static String stripComments(String src) {
        String s = BLOCK_COMMENT.matcher(src).replaceAll(" ");
        return LINE_COMMENT.matcher(s).replaceAll(" ");
    }

    /** 有意的豁免：数据结构 / Spring 装配入口 / 框架回调驱动 / 进程入口。 */
    private static boolean exempt(String typeName, String path, String raw) {
        String pkgPath = path.replace('\\', '/');
        // 1) 纯数据结构包
        if (pkgPath.matches(".*/(model|dto|domain|exception)/.*")) {
            return true;
        }
        // 2) record / enum / interface 声明
        if (raw.contains(" record ") || raw.contains(" enum ") || raw.contains(" interface ")) {
            return true;
        }
        // 3) Spring 装配入口注解
        for (String ann : new String[]{"@Configuration", "@SpringBootApplication", "@RestController",
                "@Controller", "@EnableScheduling"}) {
            if (raw.contains(ann)) {
                return true;
            }
        }
        // 4) 框架回调驱动（定时/启动钩子/事件监听，无静态注入者）
        if (raw.contains("@Scheduled") || raw.contains("@PostConstruct")) {
            return true;
        }
        if (raw.contains("implements")
                && (raw.contains("ApplicationRunner") || raw.contains("CommandLineRunner")
                || raw.contains("ApplicationListener"))) {
            return true;
        }
        // Servlet Filter：容器自动注册到过滤链（如 ApiAuthFilter），无静态注入者
        if (raw.contains("OncePerRequestFilter") || raw.contains(" implements Filter")) {
            return true;
        }
        // 5) 进程入口 / 配置绑定
        if (raw.contains("public static void main")) {
            return true;
        }
        if (typeName.endsWith("Properties") || typeName.endsWith("Application")) {
            return true;
        }
        return false;
    }
}
