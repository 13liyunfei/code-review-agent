package com.codereview.agent.core.admin;

import com.codereview.agent.core.admin.dto.KnowledgeInfo;
import com.codereview.agent.core.admin.dto.KnowledgeUpload;
import com.codereview.agent.tenant.Teams;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 团队知识控制器（规范文档 / 操作手册 / 视频上传后端）。
 *
 * <p>接收 multipart 文件与可选的文字稿 {@code text}，经 {@link TextExtractor}
 * 解析后由 {@link KnowledgeIngestionService} 写入 RAG 与本地存储。所有内容按团队隔离。
 */
@RestController
@RequestMapping("/api/admin/knowledge")
public class KnowledgeController {

    private final KnowledgeIngestionService ingestion;
    private final TextExtractor extractor;

    public KnowledgeController(KnowledgeIngestionService ingestion, TextExtractor extractor) {
        this.ingestion = ingestion;
        this.extractor = extractor;
    }

    /** 上传一份知识（文件或纯文字稿）。 */
    @PostMapping
    public KnowledgeInfo upload(@RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                               @RequestParam(value = "team", required = false) String teamParam,
                               @RequestParam(value = "file", required = false) MultipartFile file,
                               @RequestParam(value = "source", required = false) String source,
                               @RequestParam(value = "category", required = false) String category,
                               @RequestParam(value = "type", required = false, defaultValue = "document") String type,
                               @RequestParam(value = "text", required = false) String text) throws Exception {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        String originalFilename = (file != null && !file.isEmpty()) ? file.getOriginalFilename() : null;
        String storedPath = null;
        long sizeBytes = 0;
        String extracted = (text != null && !text.isBlank()) ? text : null;

        if (file != null && !file.isEmpty()) {
            if (extracted == null) {
                extracted = extractor.extract(file);
            }
            Path tmp = Files.createTempFile("kb-up-", ".bin");
            file.transferTo(tmp.toFile());
            storedPath = tmp.toString();
            sizeBytes = Files.size(tmp);
        }

        KnowledgeUpload upload = new KnowledgeUpload(source, category, type,
                extracted, originalFilename, sizeBytes, storedPath);
        return ingestion.ingest(teamId, upload);
    }

    /** 列出某团队全部知识。 */
    @GetMapping
    public java.util.List<KnowledgeInfo> list(@RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                                             @RequestParam(value = "team", required = false) String teamParam) throws Exception {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        return ingestion.list(teamId);
    }

    /** 删除某团队的知识。 */
    @DeleteMapping("/{id}")
    public java.util.Map<String, Object> delete(@RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
                                               @RequestParam(value = "team", required = false) String teamParam,
                                               @PathVariable String id) throws Exception {
        String teamId = Teams.fromRequest(teamHeader, teamParam);
        ingestion.delete(teamId, id);
        return java.util.Map.of("team", teamId, "id", id, "deleted", true);
    }
}
