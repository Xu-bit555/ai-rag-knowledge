package cn.bugstack.rag.controller;

import cn.bugstack.rag.model.dto.GenerateCasesRequest;
import cn.bugstack.rag.model.dto.QueryTestCaseResponse;
import cn.bugstack.rag.model.dto.QueryTestCaseStatsResponse;
import cn.bugstack.rag.model.dto.SaveTestCaseRequest;
import cn.bugstack.rag.model.response.Response;
import cn.bugstack.rag.service.TestCaseService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 测试用例控制器
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/testcase/")
public class TestCaseController {

    @Resource
    private TestCaseService testCaseService;

    /**
     * 生成测试用例（流式）
     */
    @GetMapping(value = "generate_stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> generateCasesStream(
            @RequestParam("content") String content,
            @RequestParam("ragTag") String ragTag) {
        log.info("生成测试用例流式请求, ragTag: {}", ragTag);

        GenerateCasesRequest request = GenerateCasesRequest.builder()
                .content(content)
                .ragTag(ragTag)
                .build();

        return testCaseService.generateCasesStream(request);
    }

    /**
     * 查询知识库中的测试用例
     */
    @GetMapping("query")
    public Response<QueryTestCaseResponse> queryTestCases(
            @RequestParam("ragTag") String ragTag,
            @RequestParam(value = "topK", required = false) Integer topK) {
        log.info("查询测试用例请求, ragTag: {}", ragTag);
        return testCaseService.queryTestCases(ragTag, topK);
    }

    /**
     * 保存测试用例到知识库
     */
    @PostMapping("save")
    public Response<String> saveTestCases(
            @Valid @RequestBody SaveTestCaseRequest request) {
        log.info("保存测试用例请求, ragTag: {}", request.getRagTag());
        return testCaseService.saveTestCases(request.getRagTag(), request.getTestCases());
    }

    /**
     * 采纳测试用例
     */
    @PostMapping("adopt")
    public Response<String> adoptTestCase(
            @RequestParam("ragTag") String ragTag,
            @RequestParam("caseId") String caseId) {
        log.info("采纳测试用例, ragTag: {}, caseId: {}", ragTag, caseId);
        return testCaseService.adoptTestCase(ragTag, caseId);
    }

    /**
     * 拒绝测试用例
     */
    @PostMapping("reject")
    public Response<String> rejectTestCase(
            @RequestParam("ragTag") String ragTag,
            @RequestParam("caseId") String caseId,
            @RequestParam(value = "reason", required = false) String reason) {
        log.info("拒绝测试用例, ragTag: {}, caseId: {}, reason: {}", ragTag, caseId, reason);
        return testCaseService.rejectTestCase(ragTag, caseId, reason);
    }

    /**
     * 批量更新用例采纳状态
     */
    @PostMapping("batch_adopt")
    public Response<String> batchAdoptTestCases(
            @RequestParam("ragTag") String ragTag,
            @RequestBody List<String> caseIds) {
        log.info("批量采纳测试用例, ragTag: {}, 数量: {}", ragTag, caseIds.size());
        return testCaseService.batchAdoptTestCases(ragTag, caseIds);
    }

    /**
     * 查询测试用例统计
     */
    @GetMapping("stats")
    public Response<QueryTestCaseStatsResponse> queryTestCaseStats(@RequestParam("ragTag") String ragTag) {
        log.info("查询测试用例统计, ragTag: {}", ragTag);
        return testCaseService.queryTestCaseStats(ragTag);
    }
}
