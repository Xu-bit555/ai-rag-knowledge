package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.model.dto.GenerateCasesRequest;
import cn.bugstack.rag.model.dto.QueryTestCaseResponse;
import cn.bugstack.rag.model.dto.QueryTestCaseStatsResponse;
import cn.bugstack.rag.model.dto.TestCaseStatsDTO;
import cn.bugstack.rag.model.response.Response;
import cn.bugstack.rag.repository.IVectorStoreRepository;
import cn.bugstack.rag.repository.IRagTagRepository;
import cn.bugstack.rag.service.RequirementExtractService;
import cn.bugstack.rag.service.RerankService;
import cn.bugstack.rag.service.TestCaseGenerateService;
import cn.bugstack.rag.service.TestCaseService;
import cn.bugstack.rag.service.ThinkStreamFilter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 测试用例服务实现
 */
@Slf4j
@Service
public class TestCaseServiceImpl implements TestCaseService {

    @Resource
    private RequirementExtractService requirementExtractService;

    @Resource
    private RerankService rerankService;

    @Resource
    private TestCaseGenerateService testCaseGenerateService;

    @Resource
    private OpenAiChatClient chatClient;

    @Resource
    private IVectorStoreRepository vectorStoreRepository;

    @Resource
    private IRagTagRepository ragTagRepository;

    @Resource
    private ThinkStreamFilter thinkStreamFilter;

    @Value("${spring.ai.minimax.model:MiniMax-M2.7}")
    private String defaultModel;

    @Override
    public Flux<String> generateCasesStream(GenerateCasesRequest request) {
        return Flux.defer(() -> {
            try {
                log.info("流式生成测试用例（逐条渲染模式）, ragTag: {}", request.getRagTag());

                // 1. 提炼需求
                String extractPrompt = requirementExtractService.buildExtractPrompt(request.getContent());
                String extractedResult = stripThinkStream(extractPrompt);
                List<String> scenarios = requirementExtractService.parseExtractedScenarios(extractedResult);

                // 2. 分别检索 知识库文档 和 历史测试用例
                // a. 知识库文档（检索时已过滤 type == 'knowledge'）
                List<String> knowledgeDocs = vectorStoreRepository.similaritySearchWithDeduplication(
                        scenarios, request.getRagTag(), 5);

                // b. 用例库检索：向量检索 → MMR → Rerank（仅查已采纳用例）
                List<String> rerankedCases = rerankService.rerankTestCases(
                        request.getRagTag(), request.getContent(), 10);

                // 3. 生成用例Prompt
                String generatePrompt = testCaseGenerateService.buildGeneratePrompt(
                        request.getContent(), rerankedCases, knowledgeDocs);

                log.info("检索完成, 知识库文档: {}, 历史用例: {}", knowledgeDocs.size(), rerankedCases.size());

                // 先发送思考中提示
                Flux<String> thinkFlux = Flux.just(toSse("think", "正在智能分析需求生成测试用例..."));

                // 流式发送用例内容，按 __CASE_END__ 分隔符逐条切分
                Flux<String> generateFlux = streamChat(generatePrompt)
                        .map(thinkStreamFilter::stripThink)
                        .filter(s -> s != null && !s.isEmpty())
                        .scan(new StringBuilder(), (buffer, chunk) -> {
                            buffer.append(chunk);
                            return buffer;
                        })
                        .flatMap(buffer -> Flux.fromIterable(extractCompleteCases(buffer.toString())))
                        .filter(caseJson -> caseJson != null && !caseJson.isBlank())
                        .map(caseJson -> toSse("case", caseJson))
                        .concatWithValues(toSse("done", "ok"));

                return Flux.concat(thinkFlux, generateFlux);
            } catch (Exception e) {
                log.error("流式生成失败", e);
                return Flux.just(toSse("error", e.getMessage()));
            }
        });
    }

    @Override
    public Response<String> saveTestCases(String ragTag, List<String> testCasesJson) {
        try {
            log.info("保存测试用例, ragTag: {}, 数量: {}", ragTag, testCasesJson.size());

            if (testCasesJson == null || testCasesJson.isEmpty()) {
                return Response.error("测试用例列表不能为空");
            }

            ragTagRepository.addRagTag(ragTag);
            vectorStoreRepository.saveTestCases(ragTag, testCasesJson);

            log.info("测试用例保存成功, ragTag: {}, 数量: {}", ragTag, testCasesJson.size());
            return Response.ok("测试用例保存成功");
        } catch (Exception e) {
            log.error("保存测试用例失败", e);
            return Response.error("保存失败: " + e.getMessage());
        }
    }

    @Override
    public Response<QueryTestCaseResponse> queryTestCases(String ragTag, Integer topK) {
        try {
            log.info("查询测试用例, ragTag: {}, topK: {}", ragTag, topK);

            int limit = topK != null && topK > 0 ? topK : 10;
            List<String> testCases = vectorStoreRepository.queryTestCases(ragTag, limit);

            return Response.ok(QueryTestCaseResponse.builder()
                    .testCases(testCases)
                    .count(testCases.size())
                    .build());
        } catch (Exception e) {
            log.error("查询测试用例失败", e);
            return Response.error("查询失败: " + e.getMessage());
        }
    }

    @Override
    public Response<String> adoptTestCase(String ragTag, String caseId) {
        try {
            log.info("采纳测试用例, ragTag: {}, caseId: {}", ragTag, caseId);
            vectorStoreRepository.updateTestCaseStatus(ragTag, caseId, "ADOPTED", null);
            return Response.ok("用例已采纳");
        } catch (Exception e) {
            log.error("采纳用例失败", e);
            return Response.error("采纳失败: " + e.getMessage());
        }
    }

    @Override
    public Response<String> rejectTestCase(String ragTag, String caseId, String reason) {
        try {
            log.info("拒绝测试用例, ragTag: {}, caseId: {}, reason: {}", ragTag, caseId, reason);
            vectorStoreRepository.updateTestCaseStatus(ragTag, caseId, "REJECTED", reason);
            return Response.ok("用例已拒绝");
        } catch (Exception e) {
            log.error("拒绝用例失败", e);
            return Response.error("拒绝失败: " + e.getMessage());
        }
    }

    @Override
    public Response<String> batchAdoptTestCases(String ragTag, List<String> caseIds) {
        try {
            log.info("批量采纳测试用例, ragTag: {}, 数量: {}", ragTag, caseIds.size());
            for (String caseId : caseIds) {
                vectorStoreRepository.updateTestCaseStatus(ragTag, caseId, "ADOPTED", null);
            }
            return Response.ok("已批量采纳 " + caseIds.size() + " 条用例");
        } catch (Exception e) {
            log.error("批量采纳失败", e);
            return Response.error("批量采纳失败: " + e.getMessage());
        }
    }

    @Override
    public Response<QueryTestCaseStatsResponse> queryTestCaseStats(String ragTag) {
        try {
            log.info("查询测试用例统计, ragTag: {}", ragTag);
            TestCaseStatsDTO stats = vectorStoreRepository.queryTestCaseStats(ragTag);

            return Response.ok(QueryTestCaseStatsResponse.builder()
                    .total(stats.getTotal())
                    .adopted(stats.getAdopted())
                    .rejected(stats.getRejected())
                    .pending(stats.getPending())
                    .adoptionRate(stats.getAdoptionRate())
                    .ragTag(ragTag)
                    .build());
        } catch (Exception e) {
            log.error("查询测试用例统计失败", e);
            return Response.error("查询失败: " + e.getMessage());
        }
    }

    private Flux<String> streamChat(String prompt) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(defaultModel)
                .build();
        return chatClient.stream(new Prompt(prompt, options))
                .filter(chatResponse -> chatResponse != null && chatResponse.getResult() != null
                        && chatResponse.getResult().getOutput() != null
                        && chatResponse.getResult().getOutput().getContent() != null)
                .map(chatResponse -> chatResponse.getResult().getOutput().getContent())
                .filter(s -> !s.isEmpty());
    }

    private String stripThinkStream(String prompt) {
        StringBuilder sb = new StringBuilder();
        streamChat(prompt).subscribe(
                chunk -> sb.append(chunk),
                error -> log.error("流式处理错误", error)
        );
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }
        return thinkStreamFilter.stripThink(sb.toString());
    }

    private String toSse(String event, String data) {
        if (data == null) data = "";
        String payload = data.replace("\r", "");
        String[] lines = payload.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("event:").append(event).append("\n");
        for (String line : lines) {
            sb.append("data:").append(line).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private static final String CASE_END_DELIMITER = "__CASE_END__";

    private List<String> extractCompleteCases(String content) {
        List<String> completeCases = new java.util.ArrayList<>();
        StringBuilder buffer = new StringBuilder(content);

        content = thinkStreamFilter.stripThink(content);
        buffer.append(content);

        String str = buffer.toString();
        int delimiterIndex;

        while ((delimiterIndex = str.indexOf(CASE_END_DELIMITER)) != -1) {
            String caseJson = str.substring(0, delimiterIndex).trim();
            caseJson = caseJson.replaceAll(",\\s*$", "");

            if (!caseJson.isBlank() && caseJson.startsWith("{")) {
                completeCases.add(caseJson);
            }

            buffer.delete(0, delimiterIndex + CASE_END_DELIMITER.length());
            str = buffer.toString();
        }

        return completeCases;
    }
}
