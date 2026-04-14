package cn.bugstack.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档 ETL 服务
 * Extract: TikaDocumentReader 读取 PDF/Word 等多格式文档
 * Transform: TokenTextSplitter 进行文本切分（使用 SplitterConfigService 的最新配置）
 * Load: 向量库存储
 */
@Slf4j
@Service
public class DocumentETLService {

    @Autowired
    private SplitterConfigService splitterConfigService;

    @Autowired(required = false)
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    /**
     * ETL 流水线：读取文件 → 切分 → 存入向量库
     *
     * @param filePath 文件路径
     * @param ragTag   知识库标签
     */
    public void etlPipeline(String filePath, String ragTag) {
        log.info("开始 ETL 流程, filePath: {}, ragTag: {}", filePath, ragTag);

        // 1. Extract: 使用 TikaDocumentReader 提取文档内容
        List<Document> documents = extract(filePath);
        if (documents.isEmpty()) {
            log.warn("文档提取结果为空, filePath: {}", filePath);
            return;
        }

        // 2. Transform: 使用 TokenTextSplitter 切分文档
        List<Document> chunks = transform(documents, ragTag);

        // 3. Load: 存入向量库
        load(chunks);

        log.info("ETL 流程完成, filePath: {}, 切分块数: {}", filePath, chunks.size());
    }

    /**
     * Extract: 从文件提取文档内容
     */
    private List<Document> extract(String filePath) {
        try {
            Path path = Paths.get(filePath);
            TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(path));
            List<Document> documents = reader.get();
            log.info("文档提取完成, filePath: {}, 文档数: {}", filePath, documents.size());
            return documents;
        } catch (Exception e) {
            log.error("文档提取失败, filePath: {}", filePath, e);
            throw new RuntimeException("文档提取失败: " + e.getMessage(), e);
        }
    }

    /**
     * Transform: 切分文档
     * 每次调用时从 SplitterConfigService 获取最新配置，动态创建 TokenTextSplitter
     *
     * @param documents 原始文档列表
     * @param ragTag    知识库标签
     * @return 切分后的文档块列表
     */
    private List<Document> transform(List<Document> documents, String ragTag) {
        try {
            // 从配置服务获取最新切分参数
            SplitterConfigService.SplitterConfig config = splitterConfigService.getConfig();

            // 为每个文档设置知识库标签
            for (Document doc : documents) {
                doc.getMetadata().put("knowledge", ragTag);
                doc.getMetadata().put("type", "knowledge");
            }

            // 动态创建 TokenTextSplitter（使用最新配置）
            TokenTextSplitter splitter = new TokenTextSplitter(
                    config.getMaxTokens(),        // maxTokens
                    config.getMinChunkLengthToEmbed(),  // minChunkSizeToEmbed
                    0,                            // minNullOverlap（不使用）
                    config.getMinTokens(),        // minChunkLength
                    Integer.MAX_VALUE             // numberOfChunks（不限制）
            );

            // 使用 TokenTextSplitter 切分
            List<Document> chunks = new ArrayList<>();
            for (Document doc : documents) {
                List<Document> splitDocs = splitter.apply(List.of(doc));
                chunks.addAll(splitDocs);
            }

            // 为每个 chunk 设置元数据
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                chunk.getMetadata().put("knowledge", ragTag);
                chunk.getMetadata().put("type", "knowledge");
                chunk.getMetadata().put("chunkIndex", i);
            }

            log.info("文档切分完成, 原始文档数: {}, 切分块数: {}, maxTokens: {}",
                    documents.size(), chunks.size(), config.getMaxTokens());
            return chunks;
        } catch (Exception e) {
            log.error("文档切分失败", e);
            throw new RuntimeException("文档切分失败: " + e.getMessage(), e);
        }
    }

    /**
     * Load: 将文档块存入向量库
     */
    private void load(List<Document> chunks) {
        if (chunks.isEmpty()) {
            return;
        }

        if (vectorStore != null) {
            vectorStore.accept(chunks);
            log.info("文档块已存入向量库, 块数: {}", chunks.size());
        } else {
            log.warn("VectorStore 未配置，跳过向量存储");
        }
    }
}
