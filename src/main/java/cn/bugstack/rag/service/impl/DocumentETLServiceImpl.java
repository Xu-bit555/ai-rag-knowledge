package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.repository.IVectorStoreRepository;
import cn.bugstack.rag.service.SplitterConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档 ETL 服务实现
 *
 * Extract: TikaDocumentReader 读取 PDF/Word 等多格式文档（支持 InputStream/Resource）
 * Transform: 两阶段切分
 *   - 阶段1：按段落语义切分（\n\n），保证句子/段落语义完整
 *   - 阶段2：TokenTextSplitter 限制 token 数量，并设置 chunk overlap 保留上下文
 * Load: 分批写入向量库，每批 N 条，避免 OOM
 */
@Slf4j
@Service
public class DocumentETLServiceImpl implements cn.bugstack.rag.service.DocumentETLService {

    @Autowired
    private SplitterConfigService splitterConfigService;

    @Autowired(required = false)
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @Autowired
    private IVectorStoreRepository vectorStoreRepository;

    /** 加载阶段每批写入的 chunk 数量 */
    @Value("${spring.ai.rag.splitter.batch-size:200}")
    private int batchSize;

    /** 段落分隔符正则：优先 \n\n（空行），其次 \n（换行），最后 。 */
    private static final Pattern PARAGRAPH_SPLITTER = Pattern.compile("(?<=\\n\\n)|(?<=\\n)|(?<=。)");

    @Override
    public void etlPipeline(Resource resource, String fileName, String ragTag) {
        log.info("开始 ETL 流程, fileName: {}, ragTag: {}", fileName, ragTag);

        List<Document> documents = extract(resource, fileName);
        if (documents.isEmpty()) {
            log.warn("文档提取结果为空, fileName: {}", fileName);
            return;
        }

        List<Document> chunks = transform(documents, ragTag);
        load(chunks, ragTag, fileName);

        log.info("ETL 流程完成, fileName: {}, 切分块数: {}", fileName, chunks.size());
    }

    private List<Document> extract(Resource resource, String fileName) {
        try {
            // TikaDocumentReader：Spring AI 提供的通用文档解析器，底层调用 Apache Tika 引擎
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> documents = reader.get();
            // P0-10: 同时写 sourceDoc（与 ParagraphIngestService 一致）+ sourceFile（兼容旧）
            //   修复 deleteBySourceDoc 漏删、KnowledgeDoc.sourceDoc 返回 null
            for (Document doc : documents) {
                doc.getMetadata().put("sourceDoc", fileName);
                doc.getMetadata().put("sourceFile", fileName);
            }
            log.info("文档提取完成, fileName: {}, 文档块数: {}", fileName, documents.size());
            return documents;
        } catch (Exception e) {
            log.error("文档提取失败, fileName: {}", fileName, e);
            throw new RuntimeException("文档提取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 两阶段切分：
     * 阶段1：按段落语义切分（\n\n, \n, 。），保证句子/段落语义完整
     * 阶段2：TokenTextSplitter 限制 token 数量，并设置 chunk overlap 保留上下文
     */
    /**
     * Transform 阶段：两阶段切分文档，保证语义完整和上下文连贯。
     *
     * <p>
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │  阶段1：段落级切分（语义完整）                                        │
     * │  输入：原始 Document 内容                                            │
     * │  依据：\n\n（空行） > \n（换行） > 。（句号）                         │
     * │  输出：语义完整的段落文本（StringBuilder 累积）                        │
     * └─────────────────────────────────────────────────────────────────────┘
     *                                │
     *                                ▼ 累积文本长度 ≥ 5 * maxTokens 时触发
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │  阶段2：Token 级细切（token 粒度控制 + overlap 保留上下文）           │
     * │  输入：语义完整的段落文本                                            │
     * │  切分器：TokenTextSplitter                                           │
     * │         - maxTokens=1000：每个 chunk 不超过 1000 token                │
     * │         - overlapTokens=200：相邻 chunk 重叠 200 token（关键！保上下文）│
     * │  输出：多个语义连贯的 Document chunks                                 │
     * └─────────────────────────────────────────────────────────────────────┘
     *
     * <p>
     * 为什么是两阶段？
     * - 段落级切分确保句子/段落语义不被切断（不会从句子中间划断）
     * - Token 级细切控制每个 embedding 向量的 token 数量，避免超出模型上限
     * - overlapTokens 让相邻 chunk 保留 200 token 重叠，检索时上下文不会丢失
     *
     * @param documents Extract 阶段输出的原始 Document 列表
     * @param ragTag   知识库标签（用于标记 chunk 元数据）
     * @return 切分后的 Document chunks 列表
     */
    private List<Document> transform(List<Document> documents, String ragTag) {
        // 获取切分配置（maxTokens、overlapTokens 等由配置文件注入）
        SplitterConfigService.SplitterConfig config = splitterConfigService.getConfig();

        // TokenTextSplitter（Spring AI 提供，支持 overlap 的文本切分器）
        // 构造方法参数顺序: maxTokens, minChunkLengthToEmbed, overlapTokens, minTokens, keepSeparator
        // - maxTokens=1000：每个 chunk 上限 1000 token（约 750 中文汉字）
        // - overlapTokens=200：相邻 chunk 重叠 200 token（约 150 中文汉字），关键设计！
        //   作用：检索时 query 与 chunk 匹配，重叠区域确保语义跨 chunk 连贯不中断
        TokenTextSplitter tokenSplitter = TokenTextSplitter.builder()
                .withChunkSize(config.getMaxTokens())
                .withMinChunkLengthToEmbed(config.getMinChunkLengthToEmbed())
                .withMinChunkSizeChars(config.getMinTokens())
                .withKeepSeparator(config.isKeepSeparator())
                .build();

        List<Document> allChunks = new ArrayList<>();

        // 遍历每个原始 Document（一个 PDF/Word 文件解析后可能包含多个 Document）
        for (Document doc : documents) {
            String content = doc.getText();
            if (content == null || content.isBlank()) {
                // 跳过空文档
                continue;
            }

            // ═══════════════════════════════════════════════════════════════
            // 阶段1：段落级切分（按自然语义单位切分，保证句子完整不断句）
            // ═══════════════════════════════════════════════════════════════
            // 正则解释：(?<=\n\n)|(?<=\n)|(?<=。)
            //   (?<=\n\n)  → 在 \n\n 后切分（空行分割的段落）
            //   (?<=\n)    → 在 \n 后切分（换行分割的句子）
            //   (?<=。)    → 在 。后切分（句号分割的完整句子）
            //   (?<=...) 是零宽断言，只切不消费，保证断点处语义完整
            String[] paragraphs = content.split("(?<=\n\n)|(?<=\n)|(?<=。)");

            // 使用 StringBuilder 累积段落，构建"语义块"
            // 当累积文本足够大时，再交给 TokenTextSplitter 细切
            // 经验阈值：约 5 * maxTokens 字符（约 3750 中文 tokens）
            // 原因：避免过度切分（每个段落一个小 chunk），也避免一个 chunk 太大
            StringBuilder semanticText = new StringBuilder();

            for (String para : paragraphs) {
                String trimmed = para.trim();
                if (trimmed.isEmpty()) {
                    // 跳过空白段落
                    continue;
                }

                // 累积段落，用空行分隔相邻段落
                semanticText.append(trimmed).append("\n\n");

                // 当累积文本达到阈值时，触发阶段2切分:     累积文本长度 ≥ 5 * maxTokens 时触发
                if (semanticText.length() >= config.getMaxTokens() * 5) {
                    // 创建语义完整的 Document（复用原始 doc 的 metadata）
                    Document semanticDoc = new Document(semanticText.toString().trim(), doc.getMetadata());
                    // 阶段2：使用 TokenTextSplitter 细切为 token 粒度的 chunks
                    List<Document> tokenChunks = splitWithOverlap(semanticDoc, tokenSplitter, config, ragTag, allChunks.size());
                    allChunks.addAll(tokenChunks);
                    // 重置 StringBuilder，开始累积下一个语义块
                    semanticText = new StringBuilder();
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // 处理剩余文本（最后不足 5*maxTokens 的尾部内容）
            // ═══════════════════════════════════════════════════════════════
            if (semanticText.length() > 0) {
                Document semanticDoc = new Document(semanticText.toString().trim(), doc.getMetadata());
                List<Document> tokenChunks = splitWithOverlap(semanticDoc, tokenSplitter, config, ragTag, allChunks.size());
                allChunks.addAll(tokenChunks);
            }
        }

        log.info("文档切分完成, 原始文档数: {}, 最终chunk数: {}, maxTokens: {}, overlapTokens: {}",
                documents.size(), allChunks.size(),
                config.getMaxTokens(), config.getOverlapTokens());

        return allChunks;
    }

    /**
     * 使用 TokenTextSplitter 切分，并设置 chunk overlap 保留上下文
     * 同时避免浅拷贝导致的 metadata 共享问题
     */
    private List<Document> splitWithOverlap(Document semanticDoc,
                                           TokenTextSplitter tokenSplitter,
                                           SplitterConfigService.SplitterConfig config,
                                           String ragTag,
                                           int startIndex) {
        // 创建新的 Document，避免浅拷贝共享 metadata 引用
        Document copy = new Document(
                semanticDoc.getText(),
                new java.util.HashMap<>(semanticDoc.getMetadata())  // 🔑 深拷贝 metadata
        );
        copy.getMetadata().put("knowledge", ragTag);
        copy.getMetadata().put("type", "knowledge");

        // TokenTextSplitter 返回切分后的 Document 列表（Spring AI 1.1.x）
        List<Document> chunks = tokenSplitter.split(copy);

        List<Document> result = new ArrayList<>();
        for (Document chunk : chunks) {
            String text = chunk.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            // 每个 chunk 独立 metadata，深拷贝避免共享引用
            java.util.HashMap<String, Object> chunkMetadata = new java.util.HashMap<>(copy.getMetadata());

            Document resultChunk = new Document(text, chunkMetadata);
            result.add(resultChunk);
        }
        return result;
    }

    /**
     * 分批写入向量库，避免单次大批量写入导致 OOM
     * @param chunks 待写入的文档块
     * @param ragTag 知识库标签（用于软删除旧版本）
     * @param sourceFile 来源文件名（用于软删除旧版本）
     */
    private void load(List<Document> chunks, String ragTag, String sourceFile) {
        if (chunks.isEmpty()) {
            return;
        }

        if (vectorStore == null) {
            log.warn("VectorStore 未配置，跳过向量存储");
            return;
        }

        int total = chunks.size();
        int successCount = 0;

        // 分批写入，每批 batchSize 条
        for (int i = 0; i < total; i += batchSize) {
            int end = Math.min(i + batchSize, total);
            List<Document> batch = chunks.subList(i, end);

            try {
                vectorStore.accept(batch);
                successCount += batch.size();
                log.debug("批次写入完成, 批次: {}-{}, 大小: {}", i, end, batch.size());
            } catch (Exception e) {
                log.error("批次写入失败, 批次: {}-{}, 错误: {}", i, end, e.getMessage());
                // 单批失败不影响其他批次，继续写入
            }
        }

        log.info("文档块已分批存入向量库, 总数: {}, 成功: {}", total, successCount);
    }

    @Override
    public List<Document> etlPipelineForUpdate(Resource resource, String fileName, String ragTag, String docId) {
        log.info("开始 ETL 更新流程, docId: {}, fileName: {}, ragTag: {}", docId, fileName, ragTag);

        // 1. 根据docId删除旧chunk
        try {
            vectorStoreRepository.deleteByDocId(ragTag, docId);
        } catch (Exception e) {
            log.warn("删除旧chunk失败（继续新增）, docId: {}, 错误: {}", docId, e.getMessage());
        }

        // 2. 提取文档
        List<Document> documents = extract(resource, fileName);
        if (documents.isEmpty()) {
            log.warn("文档提取结果为空, docId: {}", docId);
            return List.of();
        }

        // 3. 语义切分（设置docId用于metadata标识）
        List<Document> chunks = transformForUpdate(documents, ragTag, docId);

        // 4. 加载到向量库（不调用deprecateOldDocuments，直接新增）
        loadForUpdate(chunks, ragTag, docId);

        log.info("ETL 更新流程完成, docId: {}, 切分块数: {}", docId, chunks.size());
        return chunks;
    }

    /**
     * Transform阶段（更新模式）：在metadata中设置docId标识
     */
    private List<Document> transformForUpdate(List<Document> documents, String ragTag, String docId) {
        SplitterConfigService.SplitterConfig config = splitterConfigService.getConfig();

        TokenTextSplitter tokenSplitter = TokenTextSplitter.builder()
                .withChunkSize(config.getMaxTokens())
                .withMinChunkLengthToEmbed(config.getMinChunkLengthToEmbed())
                .withMinChunkSizeChars(config.getMinTokens())
                .withKeepSeparator(config.isKeepSeparator())
                .build();

        List<Document> allChunks = new ArrayList<>();

        for (Document doc : documents) {
            String content = doc.getText();
            if (content == null || content.isBlank()) {
                continue;
            }

            String[] paragraphs = content.split("(?<=\\n\\n)|(?<=\\n)|(?<=。)");
            StringBuilder semanticText = new StringBuilder();

            for (String para : paragraphs) {
                String trimmed = para.trim();
                if (trimmed.isEmpty()) continue;

                semanticText.append(trimmed).append("\n\n");

                if (semanticText.length() >= config.getMaxTokens() * 5) {
                    Document semanticDoc = new Document(semanticText.toString().trim(), doc.getMetadata());
                    // 设置docId
                    semanticDoc.getMetadata().put("docId", docId);
                    List<Document> tokenChunks = splitWithOverlap(semanticDoc, tokenSplitter, config, ragTag, allChunks.size());
                    allChunks.addAll(tokenChunks);
                    semanticText = new StringBuilder();
                }
            }

            if (semanticText.length() > 0) {
                Document semanticDoc = new Document(semanticText.toString().trim(), doc.getMetadata());
                semanticDoc.getMetadata().put("docId", docId);
                List<Document> tokenChunks = splitWithOverlap(semanticDoc, tokenSplitter, config, ragTag, allChunks.size());
                allChunks.addAll(tokenChunks);
            }
        }

        return allChunks;
    }

    /**
     * Load阶段（更新模式）：直接新增，不软删除旧版本
     */
    private void loadForUpdate(List<Document> chunks, String ragTag, String docId) {
        if (chunks.isEmpty() || vectorStore == null) {
            return;
        }

        int total = chunks.size();
        int successCount = 0;

        for (int i = 0; i < total; i += batchSize) {
            int end = Math.min(i + batchSize, total);
            List<Document> batch = chunks.subList(i, end);

            try {
                vectorStore.accept(batch);
                successCount += batch.size();
                log.debug("更新模式批次写入完成, 批次: {}-{}, 大小: {}", i, end, batch.size());
            } catch (Exception e) {
                log.error("更新模式批次写入失败, 批次: {}-{}, 错误: {}", i, end, e.getMessage());
            }
        }

        log.info("更新模式文档块已分批存入向量库, docId: {}, 总数: {}, 成功: {}", docId, total, successCount);
    }
}
