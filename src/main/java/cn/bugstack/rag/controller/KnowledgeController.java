package cn.bugstack.rag.controller;

import cn.bugstack.rag.model.dto.AddKnowledgeRequest;
import cn.bugstack.rag.model.dto.QueryKnowledgeResponse;
import cn.bugstack.rag.model.dto.QueryTagListResponse;
import cn.bugstack.rag.model.response.Response;
import cn.bugstack.rag.service.IngestRetryService;
import cn.bugstack.rag.service.KnowledgeService;
import cn.bugstack.rag.service.VectorMirrorRetryService;   // Phase 3
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库控制器
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/knowledge/")
public class KnowledgeController {

    @Resource
    private KnowledgeService knowledgeService;

    @Resource
    private IngestRetryService ingestRetryService;   // P0-9: DLQ 重试端点

    @Resource
    private VectorMirrorRetryService vectorMirrorRetryService;   // Phase 3: 向量镜像 DLQ 重试

    /**
     * 查询知识库标签列表
     */
    @GetMapping("tag_list")
    public Response<QueryTagListResponse> queryRagTagList() {
        log.info("查询RAG标签列表");
        return knowledgeService.queryRagTagList();
    }

    /**
     * 上传文件到知识库（不落盘，直接从字节流解析）
     */
    @PostMapping(value = "file/upload", headers = "content-type=multipart/form-data")
    public Response<String> uploadFile(
            @RequestParam("ragTag") String ragTag,
            @RequestParam("file") List<MultipartFile> files) {
        log.info("上传文件到知识库, ragTag: {}, 文件数量: {}", ragTag, files.size());

        try {
            // 逐个处理文件，直接从字节流解析
            for (MultipartFile file : files) {
                String fileName = file.getOriginalFilename();
                if (fileName == null || fileName.isBlank()) {
                    fileName = "upload_" + System.currentTimeMillis();
                }
                byte[] fileBytes = file.getBytes();
                Response<String> result = knowledgeService.uploadFileBytes(ragTag, fileBytes, fileName);
                if (!"200".equals(result.getCode())) {
                    return result;
                }
            }

            return Response.ok("文件上传成功，已解析并存入知识库");
        } catch (Exception e) {
            log.error("上传文件失败", e);
            return Response.error("上传失败: " + e.getMessage());
        }
    }

    /**
     * 添加文本内容到知识库
     */
    @PostMapping("add")
    public Response<String> addKnowledge(
            @Valid @RequestBody AddKnowledgeRequest request) {
        log.info("添加知识, ragTag: {}", request.getRagTag());
        return knowledgeService.addKnowledge(request.getRagTag(), request.getContent());
    }

    /**
     * 创建知识库标签（不添加内容）
     */
    @PostMapping("tag/create")
    public Response<String> createRagTag(@RequestParam("ragTag") String ragTag) {
        log.info("创建知识库标签, ragTag: {}", ragTag);
        return knowledgeService.createRagTag(ragTag);
    }

    /**
     * 查询知识库文档（语义检索）
     */
    @GetMapping("query")
    public Response<QueryKnowledgeResponse> queryKnowledge(
            @RequestParam("ragTag") String ragTag,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "topK", required = false) Integer topK) {
        log.info("查询知识库文档, ragTag: {}, query: {}", ragTag, query);
        return knowledgeService.queryKnowledge(ragTag, query, topK);
    }

    /**
     * 删除知识库文档
     */
    @DeleteMapping("delete")
    public Response<String> deleteKnowledge(
            @RequestParam("ragTag") String ragTag,
            @RequestParam("docId") String docId) {
        log.info("删除知识库文档, ragTag: {}, docId: {}", ragTag, docId);
        return knowledgeService.deleteKnowledge(ragTag, docId);
    }

    // ─────────────────────────────────────────────────────────────
    // P0-9: 摄入任务 DLQ 重试端点
    // ─────────────────────────────────────────────────────────────

    /**
     * 重试单个失败任务 (从 DLQ 回放到主 stream)
     */
    @PostMapping("ingest/retry")
    public Response<String> retryIngest(@RequestParam("taskId") String taskId) {
        log.info("ingest retry: taskId={}", taskId);
        return ingestRetryService.retry(taskId);
    }

    /**
     * 批量重试 DLQ 中的所有任务 (单次最多 100 条)
     */
    @PostMapping("ingest/retry-all")
    public Response<Integer> retryAllIngest() {
        log.info("ingest retry-all from DLQ");
        return ingestRetryService.retryAll();
    }

    // ─────────────────────────────────────────────────────────────
    // Phase 3: 向量镜像 DLQ retry 端点
    // ─────────────────────────────────────────────────────────────

    @PostMapping("vector/retry")
    public Response<String> retryVectorMirror(@RequestParam("caseId") String caseId) {
        log.info("vector mirror retry: caseId={}", caseId);
        return vectorMirrorRetryService.retry(caseId);
    }

    @PostMapping("vector/retry-all")
    public Response<Integer> retryAllVectorMirror() {
        log.info("vector mirror retry-all from DLQ");
        return vectorMirrorRetryService.retryAll();
    }

    @GetMapping("vector/dlq-stats")
    public Response<Map<String, Object>> getVectorMirrorDlqStats() {
        log.info("vector mirror DLQ stats query");
        return vectorMirrorRetryService.getStats();
    }
}
