package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.repository.IVectorStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落级文档摄入服务实现
 */
@Slf4j
@Service
public class ParagraphIngestServiceImpl implements cn.bugstack.rag.service.ParagraphIngestService {

    @Autowired
    private cn.bugstack.rag.service.SplitterConfigService splitterConfigService;

    @Autowired
    private IVectorStoreRepository vectorStoreRepository;

    @Override
    public void ingestDocumentFromParagraphs(List<cn.bugstack.rag.service.DocumentParserService.Paragraph> paragraphs, String sourceDoc, String ragTag) {
        log.info("开始摄入文档(预分段模式), sourceDoc: {}, ragTag: {}, 段落数: {}", sourceDoc, ragTag, paragraphs.size());

        List<ParagraphChunk> chunks = paragraphs.stream()
                .map(p -> ParagraphChunk.builder()
                        .content(p.getContent())
                        .sourceDoc(p.getSourceDoc())
                        .paragraphIndex(p.getParagraphIndex())
                        .pageNumber(1)
                        .charCount(p.getCharCount())
                        .build())
                .toList();

        log.info("预分段转换完成, 段落数: {}", chunks.size());

        List<Document> finalChunks = new ArrayList<>();
        int globalIndex = 0;

        for (ParagraphChunk paragraph : chunks) {
            Document doc = new Document(paragraph.getContent());
            doc.getMetadata().put("knowledge", ragTag);
            doc.getMetadata().put("sourceDoc", paragraph.getSourceDoc());
            doc.getMetadata().put("paragraphIndex", paragraph.getParagraphIndex());
            doc.getMetadata().put("pageNumber", paragraph.getPageNumber());
            doc.getMetadata().put("type", "knowledge");

            TokenTextSplitter splitter = createTokenTextSplitter();
            List<Document> tokenChunks = splitter.apply(List.of(doc));

            for (Document chunk : tokenChunks) {
                chunk.getMetadata().put("knowledge", ragTag);
                chunk.getMetadata().put("sourceDoc", paragraph.getSourceDoc());
                chunk.getMetadata().put("parentParagraphIndex", paragraph.getParagraphIndex());
                chunk.getMetadata().put("pageNumber", paragraph.getPageNumber());
                chunk.getMetadata().put("type", "knowledge");
                chunk.getMetadata().put("chunkIndex", globalIndex++);
                finalChunks.add(chunk);
            }
        }

        log.info("Token切分完成, 最终块数: {}", finalChunks.size());
        vectorStoreRepository.addDocuments(finalChunks);
        log.info("段落已存入向量库, sourceDoc: {}", sourceDoc);
    }

    private TokenTextSplitter createTokenTextSplitter() {
        cn.bugstack.rag.service.SplitterConfigService.SplitterConfig config = splitterConfigService.getConfig();
        return new TokenTextSplitter(
                config.getMaxTokens(),
                config.getMinChunkLengthToEmbed(),
                0,
                config.getMinTokens(),
                config.isKeepSeparator()
        );
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ParagraphChunk {
        private String content;
        private String sourceDoc;
        private int paragraphIndex;
        private int pageNumber;
        private int charCount;
    }
}