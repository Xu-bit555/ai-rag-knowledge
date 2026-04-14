package cn.bugstack.rag.service;

import cn.bugstack.rag.adapter.VectorStoreAdapter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 段落级文档摄入服务
 * 实现将长文档切分为带元数据的段落，保留来源文档、页码等信息
 */
@Slf4j
@Service
public class ParagraphIngestService {

    @Autowired
    private DocumentParserService documentParserService;

    @Autowired
    private SplitterConfigService splitterConfigService;

    @Autowired(required = false)
    private VectorStoreAdapter vectorStoreAdapter;

    /**
     * 将文档切分为段落并存储
     * @param content 文档内容
     * @param sourceDoc 来源文档名
     * @param ragTag 知识库标签
     */
    public void ingestDocument(String content, String sourceDoc, String ragTag) {
        log.info("开始摄入文档, sourceDoc: {}, ragTag: {}, 内容长度: {}", sourceDoc, ragTag, content.length());

        // 1. 先进行语义段落切分（兼容模式：如果没有预分段的段落，则使用语义切分）
        List<ParagraphChunk> paragraphs = splitIntoSemanticParagraphs(content, sourceDoc);

        log.info("语义切分完成, 段落数: {}", paragraphs.size());

        // 2. 对每个段落进行token切分（处理超长段落）
        List<Document> finalChunks = new ArrayList<>();
        int globalIndex = 0;

        for (ParagraphChunk paragraph : paragraphs) {
            // 创建带完整元数据的Document
            Document doc = new Document(paragraph.getContent());
            doc.getMetadata().put("knowledge", ragTag);
            doc.getMetadata().put("sourceDoc", paragraph.getSourceDoc());
            doc.getMetadata().put("paragraphIndex", paragraph.getParagraphIndex());
            doc.getMetadata().put("pageNumber", paragraph.getPageNumber());
            doc.getMetadata().put("type", "knowledge"); // 标记为知识库文档

            // 使用TokenTextSplitter处理可能超长的段落（动态配置）
            TokenTextSplitter splitter = createTokenTextSplitter();
            List<Document> tokenChunks = splitter.apply(List.of(doc));

            for (Document chunk : tokenChunks) {
                // 继承父段落的元数据
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

        // 3. 存储到向量库
        if (vectorStoreAdapter != null) {
            vectorStoreAdapter.addDocumentsWithMetadata(finalChunks);
            log.info("段落已存入向量库, sourceDoc: {}", sourceDoc);
        } else {
            log.warn("VectorStoreAdapter未配置，跳过向量存储");
        }
    }

    /**
     * 使用预分段的段落进行摄入（优先使用DocumentParserService的智能分段结果）
     * @param paragraphs 预分段的段落列表
     * @param sourceDoc 来源文档名
     * @param ragTag 知识库标签
     */
    public void ingestDocumentFromParagraphs(List<DocumentParserService.Paragraph> paragraphs, String sourceDoc, String ragTag) {
        log.info("开始摄入文档(预分段模式), sourceDoc: {}, ragTag: {}, 段落数: {}", sourceDoc, ragTag, paragraphs.size());

        // 1. 转换为内部段落格式
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

        // 2. 对每个段落进行token切分（处理超长段落）
        List<Document> finalChunks = new ArrayList<>();
        int globalIndex = 0;

        for (ParagraphChunk paragraph : chunks) {
            // 创建带完整元数据的Document
            Document doc = new Document(paragraph.getContent());
            doc.getMetadata().put("knowledge", ragTag);
            doc.getMetadata().put("sourceDoc", paragraph.getSourceDoc());
            doc.getMetadata().put("paragraphIndex", paragraph.getParagraphIndex());
            doc.getMetadata().put("pageNumber", paragraph.getPageNumber());
            doc.getMetadata().put("type", "knowledge"); // 标记为知识库文档

            // 使用TokenTextSplitter处理可能超长的段落（动态配置）
            TokenTextSplitter splitter = createTokenTextSplitter();
            List<Document> tokenChunks = splitter.apply(List.of(doc));

            for (Document chunk : tokenChunks) {
                // 继承父段落的元数据
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

        // 3. 存储到向量库
        if (vectorStoreAdapter != null) {
            vectorStoreAdapter.addDocumentsWithMetadata(finalChunks);
            log.info("段落已存入向量库, sourceDoc: {}", sourceDoc);
        } else {
            log.warn("VectorStoreAdapter未配置，跳过向量存储");
        }
    }



    /**
     * 语义段落切分
     * 优先按页码切分，其次按双换行符，最后按单换行
     */
    private List<ParagraphChunk> splitIntoSemanticParagraphs(String content, String sourceDoc) {
        List<ParagraphChunk> paragraphs = new ArrayList<>();

        if (content == null || content.isBlank()) {
            return paragraphs;
        }

        // 尝试按页码分割（PDF等文档可能有页码标记）
        List<String> pageContents = splitByPageMarkers(content);

        int paragraphIndex = 0;
        int pageNumber = 1;

        for (String pageContent : pageContents) {
            // 按段落分割（双换行 > 单换行）
            String[] lines = pageContent.split("\n\n|\n");

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                // 过滤过短的行（可能是页眉页脚）
                if (line.length() < 15 && !line.contains("。") && !line.contains(".")) {
                    continue;
                }

                paragraphs.add(ParagraphChunk.builder()
                        .content(line)
                        .sourceDoc(sourceDoc)
                        .paragraphIndex(paragraphIndex++)
                        .pageNumber(pageNumber)
                        .charCount(line.length())
                        .build());
            }

            pageNumber++;
        }

        return paragraphs;
    }

    /**
     * 按页码标记分割内容
     * 支持常见的页码格式：Page 1, 第1页, 1/10 等
     */
    private List<String> splitByPageMarkers(String content) {
        List<String> pages = new ArrayList<>();

        // 页码正则表达式
        Pattern[] pagePatterns = {
            Pattern.compile("(?m)(?=^Page \\d+)", Pattern.MULTILINE),           // Page 1
            Pattern.compile("(?m)(?=^第\\d+页)", Pattern.MULTILINE),            // 第1页
            Pattern.compile("(?m)(?=^\\d+/\\d+$)", Pattern.MULTILINE),          // 1/10
            Pattern.compile("(?m)(?=^-{3,}$)", Pattern.MULTILINE),              // ----
        };

        // 尝试使用第一个匹配的模式分割
        for (Pattern pattern : pagePatterns) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                // 找到匹配，按页分割
                int lastEnd = 0;
                int pageNum = 1;
                StringBuilder currentPage = new StringBuilder();

                matcher.reset();
                while (matcher.find()) {
                    if (currentPage.length() > 0) {
                        pages.add(currentPage.toString().trim());
                        currentPage = new StringBuilder();
                    }
                    currentPage.append(content, lastEnd, matcher.end());
                    lastEnd = matcher.end();
                    pageNum++;
                }

                // 添加最后一页
                if (lastEnd < content.length()) {
                    currentPage.append(content.substring(lastEnd));
                }
                if (currentPage.length() > 0) {
                    pages.add(currentPage.toString().trim());
                }

                if (!pages.isEmpty()) {
                    return pages;
                }
            }
        }

        // 如果没有找到页码标记，返回整个内容作为单页
        pages.add(content);
        return pages;
    }

    /**
     * 创建动态配置的 TokenTextSplitter
     * 每次调用时从 SplitterConfigService 获取最新配置
     */
    private TokenTextSplitter createTokenTextSplitter() {
        SplitterConfigService.SplitterConfig config = splitterConfigService.getConfig();
        return new TokenTextSplitter(
                config.getMaxTokens(),        // maxTokens
                config.getMinChunkLengthToEmbed(),  // minChunkSizeToEmbed
                0,                            // minNullOverlap
                config.getMinTokens(),        // minChunkLength
                Integer.MAX_VALUE             // numberOfChunks
        );
    }

    /**
     * 段落块结构
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ParagraphChunk {
        /**
         * 段落内容
         */
        private String content;

        /**
         * 来源文档名
         */
        private String sourceDoc;

        /**
         * 段落序号
         */
        private int paragraphIndex;

        /**
         * 页码
         */
        private int pageNumber;

        /**
         * 字符数
         */
        private int charCount;
    }
}
