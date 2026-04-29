package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.config.RedisStreamConfigProperties;
import cn.bugstack.rag.model.dto.QueryKnowledgeResponse;
import cn.bugstack.rag.model.dto.QueryTagListResponse;
import cn.bugstack.rag.model.entity.IngestTask;
import cn.bugstack.rag.model.response.Response;
import cn.bugstack.rag.repository.IIngestTaskRepository;
import cn.bugstack.rag.repository.IVectorStoreRepository;
import cn.bugstack.rag.repository.IRagTagRepository;
import cn.bugstack.rag.service.DocumentParserService;
import cn.bugstack.rag.service.KnowledgeService;
import cn.bugstack.rag.service.ParagraphIngestService;
import cn.bugstack.rag.service.QueryRewriteService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 知识库服务实现
 */
@Slf4j
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    @Resource
    private IRagTagRepository ragTagRepository;

    @Resource
    private IVectorStoreRepository vectorStoreRepository;

    @Resource
    private DocumentParserService documentParserService;

    @Resource
    private ParagraphIngestService paragraphIngestService;

    @Resource
    private QueryRewriteService queryRewriteService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedisStreamConfigProperties streamConfig;

    @Resource
    private IIngestTaskRepository ingestTaskRepository;

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
            log.info("上传文件到知识库(异步模式), ragTag: {}, 文件名: {}, 大小: {} bytes",
                    ragTag, fileName, fileBytes != null ? fileBytes.length : 0);

            if (fileBytes == null || fileBytes.length == 0) {
                return Response.error("文件内容为空");
            }

            // 1. 生成任务ID
            String taskId = UUID.randomUUID().toString().replace("-", "");

            // 2. 文件内容存入 Redis（key: rag:file:{taskId}，TTL 24小时）
            String redisKey = "rag:file:" + taskId;
            RBucket<byte[]> bucket = redissonClient.getBucket(redisKey);
            bucket.set(fileBytes, java.time.Duration.ofHours(24));

            // 3. 创建任务记录（PENDING 状态）
            IngestTask task = IngestTask.createPending(taskId, ragTag, fileName);
            ingestTaskRepository.insert(task);

            // 4. 发布消息到 Redis Stream（retryCount=0，初始重试次数）
            RStream<String, String> stream = redissonClient.getStream(streamConfig.getStreamKey());
            stream.add(org.redisson.api.stream.StreamAddArgs
                    .<String, String>entries("taskId", taskId, "ragTag", ragTag, "retryCount", "0"));

            // 5. 添加标签
            ragTagRepository.addRagTag(ragTag);

            log.info("文件上传任务已提交, taskId: {}, fileName: {}", taskId, fileName);
            return Response.ok("任务已提交，taskId: " + taskId);
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

            ragTagRepository.addRagTag(ragTag);

            // Markdown文本也需要切分，与文件上传保持一致
            // 将content作为字节数组传入，文件名固定为text.md
            DocumentParserService.ParseResult parseResult =
                    documentParserService.parseFromBytes(content.getBytes(), "text.md");

            paragraphIngestService.ingestDocumentFromParagraphs(
                    parseResult.getParagraphs(),
                    "text.md",
                    ragTag
            );

            log.info("知识添加成功, ragTag: {}, 段落数: {}", ragTag, parseResult.getParagraphs().size());
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
            ragTagRepository.addRagTag(ragTag);
            return Response.ok("知识库创建成功");
        } catch (Exception e) {
            log.error("创建知识库失败", e);
            return Response.error("创建失败: " + e.getMessage());
        }
    }

    @Override
    public Response<QueryKnowledgeResponse> queryKnowledge(String ragTag, String query, Integer topK) {
        try {
            log.info("查询知识库文档, ragTag: {}, query: {}", ragTag, query);

            int limit = topK != null && topK > 0 ? topK : 50;

            List<QueryKnowledgeResponse.KnowledgeDoc> docs;
            if (query == null || query.trim().isBlank()) {
                docs = vectorStoreRepository.queryKnowledgeDocs(ragTag, limit);
            } else {
                String rewrittenQuery = queryRewriteService.rewriteToSingle(query.trim());
                log.info("Query Rewrite: {} -> {}", query, rewrittenQuery);

                List<String> allQueries = queryRewriteService.rewrite(query.trim());
                log.info("多查询检索, 查询数量: {}, queries: {}", allQueries.size(), allQueries);

                List<QueryKnowledgeResponse.KnowledgeDoc> allDocs = new ArrayList<>();
                for (String q : allQueries) {
                    List<QueryKnowledgeResponse.KnowledgeDoc> docsForQuery =
                            vectorStoreRepository.queryKnowledgeDocsWithScore(ragTag, q, limit);
                    allDocs.addAll(docsForQuery);
                }

                Map<String, QueryKnowledgeResponse.KnowledgeDoc> uniqueDocs = new LinkedHashMap<>();
                for (QueryKnowledgeResponse.KnowledgeDoc doc : allDocs) {
                    String key = doc.getDocId();
                    if (!uniqueDocs.containsKey(key) ||
                            (doc.getScore() != null && doc.getScore() > uniqueDocs.get(key).getScore())) {
                        uniqueDocs.put(key, doc);
                    }
                }

                docs = new ArrayList<>(uniqueDocs.values());
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
}
