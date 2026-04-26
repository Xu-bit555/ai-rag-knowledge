package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.model.dto.*;
import cn.bugstack.rag.model.response.Response;
import cn.bugstack.rag.repository.IVectorStoreRepository;
import cn.bugstack.rag.repository.IRagTagRepository;
import cn.bugstack.rag.service.DocumentParserService;
import cn.bugstack.rag.service.ThinkStreamFilter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * RAG服务实现
 */
@Slf4j
@Service
public class RAGServiceImpl implements cn.bugstack.rag.service.RAGService {

    @Resource
    private cn.bugstack.rag.service.RequirementExtractService requirementExtractService;
    @Resource
    private cn.bugstack.rag.service.RerankService rerankService;
    @Resource
    private cn.bugstack.rag.service.TestCaseGenerateService testCaseGenerateService;
    @Resource
    private OpenAiChatClient chatClient;
    @Resource
    private IVectorStoreRepository vectorStoreRepository;
    @Resource
    private IRagTagRepository ragTagRepository;
    @Resource
    private cn.bugstack.rag.service.DocumentParserService documentParserService;
    @Resource
    private cn.bugstack.rag.service.ParagraphIngestService paragraphIngestService;
    @Resource
    private ThinkStreamFilter thinkStreamFilter;
    @Resource
    private cn.bugstack.rag.service.QueryRewriteService queryRewriteService;

    @Value("${spring.ai.minimax.model:MiniMax-M2.7}")
    private String defaultModel;


    /**
     * 1. 流式生成测试用例
     * @param request 请求参数
     * @return
     */
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
                        .map(thinkStreamFilter::stripThink) // 过滤<think>标签
                        .filter(s -> s != null && !s.isEmpty())
                        .flatMap(chunk -> {
                            // 缓冲并检测完整用例
                            return Flux.fromIterable(extractCompleteCases(chunk));  //按__CASE_END__切分
                        })
                        .filter(caseJson -> caseJson != null && !caseJson.isBlank())
                        .map(caseJson -> toSse("case", caseJson))  // 封装SSE
                        .concatWithValues(toSse("done", "ok"));

                return Flux.concat(thinkFlux, generateFlux);
            } catch (Exception e) {
                log.error("流式生成失败", e);
                return Flux.just(toSse("error", e.getMessage()));
            }
        });
    }



    @Override
    public Response<QueryTagListResponse> queryRagTagList() {
        try {
            List<String> tags = ragTagRepository.getAllRagTags();
            return Response.ok(QueryTagListResponse.builder()
                    .tags(tags)
                    .count(tags.size())
                    .build());
        } catch (Exception e) {
            log.error("查询RAG标签失败", e);
            return Response.error("查询失败: " + e.getMessage());
        }
    }

    @Override
    public Response<String> uploadFileBytes(String ragTag, byte[] fileBytes, String fileName) {
        try {
            log.info("上传文件到知识库(不落盘), ragTag: {}, 文件名: {}, 大小: {} bytes",
                    ragTag, fileName, fileBytes != null ? fileBytes.length : 0);

            if (fileBytes == null || fileBytes.length == 0) {
                return Response.error("文件内容为空");
            }

            // 添加标签
            ragTagRepository.addRagTag(ragTag);

            // 直接从字节流解析文档
            DocumentParserService.ParseResult parseResult = documentParserService.parseFromBytes(fileBytes, fileName);

            // 使用段落级摄入服务进行语义切分和存储（使用预分段模式，保留智能分段结果）
            paragraphIngestService.ingestDocumentFromParagraphs(
                    parseResult.getParagraphs(),
                    fileName,
                    ragTag
            );

            log.info("文件上传并解析成功, ragTag: {}, 文件名: {}, 内容长度: {}",
                    ragTag, fileName, parseResult.getContent().length());

            return Response.ok("文件上传成功，已解析并存入知识库");
        } catch (Exception e) {
            log.error("上传文件失败", e);
            return Response.error("上传失败: " + e.getMessage());
        }
    }

    @Override
    public Response<String> addKnowledge(String ragTag, String content) {
        try {
            log.info("添加知识到向量库, ragTag: {}, content长度: {}", ragTag, content != null ? content.length() : 0);

            if (content == null || content.isBlank()) {
                return Response.error("内容不能为空");
            }

            // 添加标签
            ragTagRepository.addRagTag(ragTag);

            // 直接存入向量库
            org.springframework.ai.document.Document document = new org.springframework.ai.document.Document(content);
            document.getMetadata().put("knowledge", ragTag);
            document.getMetadata().put("type", "knowledge");
            document.getMetadata().put("createdAt", System.currentTimeMillis());
            vectorStoreRepository.addDocuments(List.of(document));

            log.info("知识添加成功, ragTag: {}", ragTag);
            return Response.ok("知识添加成功");
        } catch (Exception e) {
            log.error("添加知识失败", e);
            return Response.error("添加失败: " + e.getMessage());
        }
    }

    @Override
    public Response<String> createRagTag(String ragTag) {
        try {
            log.info("创建知识库标签, ragTag: {}", ragTag);
            if (ragTag == null || ragTag.isBlank()) {
                return Response.error("知识库名称不能为空");
            }
            // 只添加标签，不添加内容
            ragTagRepository.addRagTag(ragTag);
            return Response.ok("知识库创建成功");
        } catch (Exception e) {
            log.error("创建知识库失败", e);
            return Response.error("创建失败: " + e.getMessage());
        }
    }



    /**
     * 简单统计JSON中的用例数量（备选方案）
     */
    private int countJsonCases(String json) {
        if (json == null || json.isEmpty()) {
            return 0;
        }
        // 简单计数 "title": 出现的次数
        int count = 0;
        String target = "\"title\":";
        int index = 0;
        while ((index = json.indexOf(target, index)) != -1) {
            count++;
            index += target.length();
        }
        return count;
    }


    /**
     * 从流式chunk中提取完整用例，缓冲不完整的片段
     */
    private static final ThreadLocal<StringBuilder> caseBuffer = ThreadLocal.withInitial(StringBuilder::new);
    private static final String CASE_END_DELIMITER = "__CASE_END__";

    private List<String> extractCompleteCases(String chunk) {
        List<String> completeCases = new java.util.ArrayList<>();
        StringBuilder buffer = caseBuffer.get();

        // 清理chunk中的think标签内容
        chunk = thinkStreamFilter.stripThink(chunk);

        buffer.append(chunk);

        String content = buffer.toString();
        int delimiterIndex;

        // 循环提取所有完整用例
        while ((delimiterIndex = content.indexOf(CASE_END_DELIMITER)) != -1) {
            String caseJson = content.substring(0, delimiterIndex).trim();
            // 移除可能的末尾逗号
            caseJson = caseJson.replaceAll(",\\s*$", "");

            if (!caseJson.isBlank() && caseJson.startsWith("{")) {
                completeCases.add(caseJson);
            }

            // 移除已提取的内容
            buffer.delete(0, delimiterIndex + CASE_END_DELIMITER.length());
            content = buffer.toString();
        }

        return completeCases;
    }

    /**
     * 从文档列表中过滤掉测试用例
     */
    // ==================== 私有方法 ====================

    /**
     * 流式对话
     */
    private Flux<String> streamChat(String prompt) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(defaultModel)
                .build();
        return chatClient.stream(new Prompt(prompt, options))
                .map(chatResponse -> {
                    if (chatResponse == null || chatResponse.getResult() == null) {
                        return "";
                    }
                    String content = chatResponse.getResult().getOutput().getContent();
                    return content != null ? content : "";
                })
                .filter(s -> !s.isEmpty());
    }

    private String stripThinkStream(String prompt) {
        StringBuilder sb = new StringBuilder();
        streamChat(prompt).subscribe(
                chunk -> sb.append(chunk),
                error -> log.error("流式处理错误", error)
        );
        // 等待完成
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }
        return thinkStreamFilter.stripThink(sb.toString());
    }

    private String toSse(String event, String data) {
        if (data == null) data = "";
        // 正确处理多行数据：每行都加 data: 前缀
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

    /**
     * 将测试用例保存到用例库中
     * @param ragTag 知识库标签
     * @param testCasesJson 测试用例JSON列表
     * @return
     */
    @Override
    public Response<String> saveTestCases(String ragTag, List<String> testCasesJson) {
        try {
            log.info("保存测试用例, ragTag: {}, 数量: {}", ragTag, testCasesJson.size());

            if (testCasesJson == null || testCasesJson.isEmpty()) {
                return Response.error("测试用例列表不能为空");
            }

            // 添加标签
            ragTagRepository.addRagTag(ragTag);

            // 保存到向量库
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

    /**
     * 对指定用例进行采纳
     * @param ragTag 知识库标签
     * @param caseId 用例ID
     * @return
     */
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
    public Response<QueryKnowledgeResponse> queryKnowledge(String ragTag, String query, Integer topK) {
        try {
            log.info("查询知识库文档, ragTag: {}, query: {}", ragTag, query);

            int limit = topK != null && topK > 0 ? topK : 50;

            List<QueryKnowledgeResponse.KnowledgeDoc> docs;
            if (query == null || query.trim().isBlank()) {
                // 无 query 时返回最新文档（保持向后兼容）
                docs = vectorStoreRepository.queryKnowledgeDocs(ragTag, limit);
            } else {
                // 有 query 时进行 Query Rewrite + 语义检索
                String rewrittenQuery = queryRewriteService.rewriteToSingle(query.trim());
                log.info("Query Rewrite: {} -> {}", query, rewrittenQuery);

                // 获取所有重写后的查询，进行多查询检索
                List<String> allQueries = queryRewriteService.rewrite(query.trim());
                log.info("多查询检索, 查询数量: {}, queries: {}", allQueries.size(), allQueries);

                // 对每个查询进行检索，然后合并去重
                List<QueryKnowledgeResponse.KnowledgeDoc> allDocs = new java.util.ArrayList<>();
                for (String q : allQueries) {
                    List<QueryKnowledgeResponse.KnowledgeDoc> docsForQuery =
                            vectorStoreRepository.queryKnowledgeDocsWithScore(ragTag, q, limit);
                    allDocs.addAll(docsForQuery);
                }

                // 按 score 去重（保留最高分）
                Map<String, QueryKnowledgeResponse.KnowledgeDoc> uniqueDocs = new java.util.LinkedHashMap<>();
                for (QueryKnowledgeResponse.KnowledgeDoc doc : allDocs) {
                    String key = doc.getDocId();
                    if (!uniqueDocs.containsKey(key) ||
                            (doc.getScore() != null && doc.getScore() > uniqueDocs.get(key).getScore())) {
                        uniqueDocs.put(key, doc);
                    }
                }

                docs = new java.util.ArrayList<>(uniqueDocs.values());
                // 限制返回数量
                if (docs.size() > limit) {
                    docs = docs.subList(0, limit);
                }
            }

            return Response.ok(QueryKnowledgeResponse.builder()
                    .documents(docs)
                    .count(docs.size())
                    .ragTag(ragTag)
                    .build());
        } catch (Exception e) {
            log.error("查询知识库文档失败", e);
            return Response.error("查询失败: " + e.getMessage());
        }
    }

    @Override
    public Response<String> deleteKnowledge(String ragTag, String docId) {
        try {
            log.info("删除知识库文档, ragTag: {}, docId: {}", ragTag, docId);
            vectorStoreRepository.deleteKnowledgeDoc(ragTag, docId);
            return Response.ok("文档删除成功");
        } catch (Exception e) {
            log.error("删除知识库文档失败", e);
            return Response.error("删除失败: " + e.getMessage());
        }
    }

    /**
     * 统计采纳率
     * @param ragTag 知识库标签
     * @return
     */
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

}
