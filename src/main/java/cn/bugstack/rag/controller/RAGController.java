package cn.bugstack.rag.controller;

import cn.bugstack.rag.model.dto.*;
import cn.bugstack.rag.model.response.Response;
import cn.bugstack.rag.service.RAGService;
import cn.bugstack.rag.service.SplitterConfigService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG控制器 - HTTP触发层
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/rag/")
public class RAGController {

    @Resource
    private RAGService ragService;

    @Resource
    private SplitterConfigService splitterConfigService;




/**
 * ---------------------- 用例相关------------------------
 */


    /**
     * 生成测试用例（流式）
     */
    @GetMapping(value = "generate_cases_stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> generateCasesStream(
            @RequestParam("content") String content,
            @RequestParam("ragTag") String ragTag) {
        log.info("生成测试用例流式请求, ragTag: {}", ragTag);

        GenerateCasesRequest request = GenerateCasesRequest.builder()
                .content(content)
                .ragTag(ragTag)
                .build();

        return ragService.generateCasesStream(request);
    }


    /**
     * 查询知识库中的测试用例
     */
    @GetMapping("testcase/query")
    public Response<QueryTestCaseResponse> queryTestCases(
            @RequestParam("ragTag") String ragTag,
            @RequestParam(value = "topK", required = false) Integer topK) {
        log.info("查询测试用例请求, ragTag: {}", ragTag);
        return ragService.queryTestCases(ragTag, topK);
    }



/**
     * ---------------------- 采纳率------------------------
     */


    /**
     * 采纳率实现：保存测试用例到知识库
     */
    @PostMapping("testcase/save")
    public Response<String> saveTestCases(
            @Valid @RequestBody SaveTestCaseRequest request) {
        log.info("保存测试用例请求, ragTag: {}", request.getRagTag());
        return ragService.saveTestCases(request.getRagTag(), request.getTestCases());
    }


    /**
     * 采纳测试用例
     */
    @PostMapping("testcase/adopt")
    public Response<String> adoptTestCase(
            @RequestParam("ragTag") String ragTag,
            @RequestParam("caseId") String caseId) {
        log.info("采纳测试用例, ragTag: {}, caseId: {}", ragTag, caseId);
        return ragService.adoptTestCase(ragTag, caseId);
    }

    /**
     * 拒绝测试用例
     */
    @PostMapping("testcase/reject")
    public Response<String> rejectTestCase(
            @RequestParam("ragTag") String ragTag,
            @RequestParam("caseId") String caseId,
            @RequestParam(value = "reason", required = false) String reason) {
        log.info("拒绝测试用例, ragTag: {}, caseId: {}, reason: {}", ragTag, caseId, reason);
        return ragService.rejectTestCase(ragTag, caseId, reason);
    }

    /**
     * 批量更新用例采纳状态
     */
    @PostMapping("testcase/batch_adopt")
    public Response<String> batchAdoptTestCases(
            @RequestParam("ragTag") String ragTag,
            @RequestBody List<String> caseIds) {
        log.info("批量采纳测试用例, ragTag: {}, 数量: {}", ragTag, caseIds.size());
        return ragService.batchAdoptTestCases(ragTag, caseIds);
    }

    /**
     * 查询测试用例统计
     */
    @GetMapping("testcase/stats")
    public Response<QueryTestCaseStatsResponse> queryTestCaseStats(@RequestParam("ragTag") String ragTag) {
        log.info("查询测试用例统计, ragTag: {}", ragTag);
        return ragService.queryTestCaseStats(ragTag);
    }


/**
 * ---------------------- 知识库相关------------------------
 */

    /**
     * 查询知识库标签列表
     */
    @GetMapping("query_rag_tag_list")
    public Response<QueryTagListResponse> queryRagTagList() {
        log.info("查询RAG标签列表");
        return ragService.queryRagTagList();
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
                Response<String> result = ragService.uploadFileBytes(ragTag, fileBytes, fileName);
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
    @PostMapping("add_knowledge")
    public Response<String> addKnowledge(
            @Valid @RequestBody AddKnowledgeRequest request) {
        log.info("添加知识, ragTag: {}", request.getRagTag());
        return ragService.addKnowledge(request.getRagTag(), request.getContent());
    }

    /**
     * 创建知识库标签（不添加内容）
     */
    @PostMapping("create_rag_tag")
    public Response<String> createRagTag(@RequestParam("ragTag") String ragTag) {
        log.info("创建知识库标签, ragTag: {}", ragTag);
        return ragService.createRagTag(ragTag);
    }

    /**
     * 查询知识库文档（语义检索）
     */
    @GetMapping("knowledge/query")
    public Response<QueryKnowledgeResponse> queryKnowledge(
            @RequestParam("ragTag") String ragTag,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "topK", required = false) Integer topK) {
        log.info("查询知识库文档, ragTag: {}, query: {}", ragTag, query);
        return ragService.queryKnowledge(ragTag, query, topK);
    }

    /**
     * 删除知识库文档
     */
    @DeleteMapping("knowledge/delete")
    public Response<String> deleteKnowledge(
            @RequestParam("ragTag") String ragTag,
            @RequestParam("docId") String docId) {
        log.info("删除知识库文档, ragTag: {}, docId: {}", ragTag, docId);
        return ragService.deleteKnowledge(ragTag, docId);
    }


    /**
     * 查询切分配置
     */
    @GetMapping("splitter/config")
    public Response<SplitterConfigDTO> getSplitterConfig() {
        log.info("查询切分配置");
        SplitterConfigService.SplitterConfig config = splitterConfigService.getConfig();
        SplitterConfigDTO dto = SplitterConfigDTO.builder()
                .maxTokens(config.getMaxTokens())
                .minTokens(config.getMinTokens())
                .minChunkLengthToEmbed(config.getMinChunkLengthToEmbed())
                .mergeChunkLength(config.getMergeChunkLength())
                .keepSeparator(config.isKeepSeparator())
                .build();
        return Response.ok(dto);
    }

    /**
     * 更新切分配置
     */
    @PostMapping("splitter/config")
    public Response<String> updateSplitterConfig(@RequestBody SplitterConfigDTO config) {
        log.info("更新切分配置");
        try {
            SplitterConfigService.SplitterConfig serviceConfig = new SplitterConfigService.SplitterConfig();
            serviceConfig.setMaxTokens(config.getMaxTokens() != null ? config.getMaxTokens() : 500);
            serviceConfig.setMinTokens(config.getMinTokens() != null ? config.getMinTokens() : 50);
            serviceConfig.setMinChunkLengthToEmbed(config.getMinChunkLengthToEmbed() != null ? config.getMinChunkLengthToEmbed() : 5);
            serviceConfig.setMergeChunkLength(config.getMergeChunkLength() != null ? config.getMergeChunkLength() : 200);
            serviceConfig.setKeepSeparator(config.getKeepSeparator() != null ? config.getKeepSeparator() : true);
            splitterConfigService.updateConfig(serviceConfig);
            return Response.ok("配置更新成功");
        } catch (Exception e) {
            log.error("更新切分配置失败", e);
            return Response.error("更新失败: " + e.getMessage());
        }
    }



}
