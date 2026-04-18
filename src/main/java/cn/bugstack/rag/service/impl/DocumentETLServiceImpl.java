package cn.bugstack.rag.service.impl;

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
 * 文档 ETL 服务实现
 * Extract: TikaDocumentReader 读取 PDF/Word 等多格式文档
 * Transform: TokenTextSplitter 进行文本切分（使用 SplitterConfigService 的最新配置）
 * Load: 向量库存储
 */
@Slf4j
@Service
public class DocumentETLServiceImpl implements cn.bugstack.rag.service.DocumentETLService {

    @Autowired
    private cn.bugstack.rag.service.SplitterConfigService splitterConfigService;

    @Autowired(required = false)
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @Override
    public void etlPipeline(String filePath, String ragTag) {
        log.info("开始 ETL 流程, filePath: {}, ragTag: {}", filePath, ragTag);

        List<Document> documents = extract(filePath);
        if (documents.isEmpty()) {
            log.warn("文档提取结果为空, filePath: {}", filePath);
            return;
        }

        List<Document> chunks = transform(documents, ragTag);
        load(chunks);

        log.info("ETL 流程完成, filePath: {}, 切分块数: {}", filePath, chunks.size());
    }

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

    private List<Document> transform(List<Document> documents, String ragTag) {
        try {
            cn.bugstack.rag.service.SplitterConfigService.SplitterConfig config = splitterConfigService.getConfig();

            for (Document doc : documents) {
                doc.getMetadata().put("knowledge", ragTag);
                doc.getMetadata().put("type", "knowledge");
            }

            TokenTextSplitter splitter = new TokenTextSplitter(
                    config.getMaxTokens(),
                    config.getMinChunkLengthToEmbed(),
                    0,
                    config.getMinTokens(),
                    config.isKeepSeparator()
            );

            List<Document> chunks = new ArrayList<>();
            for (Document doc : documents) {
                List<Document> splitDocs = splitter.apply(List.of(doc));
                chunks.addAll(splitDocs);
            }

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