package com.codereview.agent.core.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 上传文件文本提取器（离线、零依赖）。
 *
 * <ul>
 *   <li>纯文本 / 源码 / 标记语言：直接按 UTF-8 读取；</li>
 *   <li>Word（.docx）：解压后抽取 {@code word/document.xml} 文本；</li>
 *   <li>PDF / PPT / 视频等二进制：无法离线提取文本，返回 {@code null}，
 *       由调用方仅保存元数据（视频可另附文字稿经 {@code text} 参数入库）。</li>
 * </ul>
 */
@Component
public class TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);

    private static final Set<String> TEXT_EXT = Set.of(
            "txt", "md", "markdown", "java", "js", "ts", "tsx", "jsx", "py", "go", "c", "cpp",
            "h", "hpp", "cs", "json", "csv", "xml", "html", "htm", "yml", "yaml", "properties",
            "log", "sql", "sh", "conf", "ini", "kt", "rb", "php", "swift");

    private static final Set<String> DOCX_EXT = Set.of("docx");

    /**
     * 提取上传文件的文本；无法提取时返回 null。
     */
    public String extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        String ext = ext(file.getOriginalFilename());
        try {
            if (TEXT_EXT.contains(ext)) {
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            }
            if (DOCX_EXT.contains(ext)) {
                return extractDocx(file.getInputStream());
            }
            log.info("[TextExtractor] 不支持直接提取的二进制类型 {}，跳过文本解析（视频/PDF 可附文字稿）", ext);
            return null;
        } catch (Exception e) {
            log.warn("[TextExtractor] 提取失败（{}）：{}", ext, e.getMessage());
            return null;
        }
    }

    private String ext(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    private String extractDocx(InputStream in) throws IOException {
        Path tmp = Files.createTempFile("docx-", ".docx");
        try {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            StringBuilder sb = new StringBuilder();
            try (ZipFile zip = new ZipFile(tmp.toFile())) {
                ZipEntry entry = zip.getEntry("word/document.xml");
                if (entry == null) {
                    return "";
                }
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                }
            }
            return sb.toString()
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&amp;", "&").replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">").replaceAll("&quot;", "\"")
                    .replaceAll("&apos;", "'")
                    .replaceAll("[ \\t]+", " ")
                    .replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n")
                    .trim();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
