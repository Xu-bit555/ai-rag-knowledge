package cn.bugstack.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 多格式文档解析服务
 * 支持 PDF、Word、Excel、PPT、TXT、Markdown、HTML、JSON 等多种格式
 */
@Slf4j
@Service
public class DocumentParserService {

    private final Tika tika = new Tika();
    private final AutoDetectParser parser = new AutoDetectParser();

    /**
     * 解析文档内容
     * @param filePath 文件路径
     * @return 提取的文本内容
     */
    public String parseDocument(Path filePath) {
        log.info("开始解析文档: {}", filePath);

        try (InputStream stream = Files.newInputStream(filePath)) {
            // 使用 BodyContentHandler 限制最大内容大小 (-1表示无限制)
            BodyContentHandler handler = new BodyContentHandler(-1);
            org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();
            ParseContext context = new ParseContext();

            parser.parse(stream, handler, metadata, context);

            String content = handler.toString();
            log.info("文档解析完成, 长度: {}, 文件名: {}", content.length(), filePath.getFileName());

            return content;
        } catch (IOException | SAXException | TikaException e) {
            log.error("文档解析失败: {}", filePath, e);
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析文档并返回带元数据的内容
     * @param filePath 文件路径
     * @return 解析结果包含内容和建议的切分点
     */
    public ParseResult parseWithMetadata(Path filePath) {
        String content = parseDocument(filePath);
        DocumentMetadata docMeta = extractMetadata(filePath);

        // 提取内容并生成建议的切分段落
        List<Paragraph> paragraphs = splitIntoParagraphs(content, filePath.getFileName().toString());

        return ParseResult.builder()
                .content(content)
                .metadata(docMeta)
                .paragraphs(paragraphs)
                .build();
    }

    /**
     * 从字节数组解析文档（不落盘）
     * @param bytes 文件字节数组
     * @param fileName 文件名
     * @return 解析结果包含内容和建议的切分点
     */
    public ParseResult parseFromBytes(byte[] bytes, String fileName) {
        log.info("开始解析文档(字节流): {}", fileName);

        try (InputStream stream = new java.io.ByteArrayInputStream(bytes)) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();
            metadata.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            ParseContext context = new ParseContext();

            parser.parse(stream, handler, metadata, context);

            String content = handler.toString();
            log.info("文档解析完成, 长度: {}, 文件名: {}", content.length(), fileName);

            DocumentMetadata docMeta = new DocumentMetadata();
            docMeta.setFileName(fileName);
            docMeta.setContentType(metadata.get(org.apache.tika.metadata.Metadata.CONTENT_TYPE));
            docMeta.setTitle(metadata.get(org.apache.tika.metadata.TikaCoreProperties.TITLE));
            docMeta.setAuthor(metadata.get(org.apache.tika.metadata.TikaCoreProperties.CREATOR));

            List<Paragraph> paragraphs = splitIntoParagraphs(content, fileName);

            return ParseResult.builder()
                    .content(content)
                    .metadata(docMeta)
                    .paragraphs(paragraphs)
                    .build();
        } catch (IOException | SAXException | TikaException e) {
            log.error("文档解析失败: {}", fileName, e);
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提取文档元数据
     * @param filePath 文件路径
     * @return 元数据对象
     */
    public DocumentMetadata extractMetadata(Path filePath) {
        try (InputStream stream = Files.newInputStream(filePath)) {
            org.apache.tika.metadata.Metadata tikaMetadata = new org.apache.tika.metadata.Metadata();
            tikaMetadata.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, filePath.getFileName().toString());

            // 只解析元数据，不解析内容
            parser.parse(stream, new BodyContentHandler(), tikaMetadata, new ParseContext());

            DocumentMetadata docMeta = new DocumentMetadata();
            docMeta.setFileName(filePath.getFileName().toString());
            // 使用 metadata.get() 获取常见属性
            docMeta.setContentType(tikaMetadata.get(org.apache.tika.metadata.Metadata.CONTENT_TYPE));
            docMeta.setTitle(tikaMetadata.get(org.apache.tika.metadata.TikaCoreProperties.TITLE));
            docMeta.setAuthor(tikaMetadata.get(org.apache.tika.metadata.TikaCoreProperties.CREATOR));

            return docMeta;
        } catch (IOException | SAXException | TikaException e) {
            log.warn("提取文档元数据失败: {}", filePath, e);
            DocumentMetadata docMeta = new DocumentMetadata();
            docMeta.setFileName(filePath.getFileName().toString());
            return docMeta;
        }
    }

    /**
     * 将文档内容切分为段落
     * 章节级语义切分策略：
     * 1. 优先按大章节标题（第X章、第X.X节）切分
     * 2. 次级按编号结构（X.X.X）切分
     * 3. 最后按双换行符分割
     * 4. 过短碎片（< 30字符）向后合并，而非丢弃
     *
     * @param content 文档内容
     * @param sourceDoc 源文档名
     * @return 段落列表
     */
    public List<Paragraph> splitIntoParagraphs(String content, String sourceDoc) {
        List<String> rawChunks = new ArrayList<>();

        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }

        // 0. 规范化换行符
        content = content.replace("\r\n", "\n").replace("\r", "\n");

        // 1. 清理内容：过滤乱码字符和纯数字行
        StringBuilder cleanedContent = new StringBuilder();
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty() && !line.matches("^\\d+$") && !line.contains("�")) {
                cleanedContent.append(line).append("\n");
            }
        }
        String cleaned = cleanedContent.toString().trim();
        if (cleaned.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 按大章节标题分割（优先级最高）
        // 匹配模式：第X章、第X.X节、第X.X.X小节
        String[] majorSectionPatterns = {
                "(?=\\s*第[一二三四五六七八九十\\d]+章\\s)",           // 第1章、第12章
                "(?=\\s*第[一二三四五六七八九十\\d]+节\\s)",           // 第1节、第12节
                "(?=\\s*第[一二三四五六七八九十\\d]+[.．][一二三四五六七八九十\\d]+\\s)" // 第1.1节
        };

        String processed = cleaned;
        for (String pattern : majorSectionPatterns) {
            String[] parts = processed.split(pattern);
            if (parts.length > 1) {
                processed = String.join("\n=====SECTION=====\n", parts);
            }
        }

        // 3. 按 X.X.X 结构进一步切分（1.  1.1  1.1.1 等）
        // 在独立的编号处切分
        processed = processed.replaceAll(
                "(?m)^(\\d+[.．]\\d+(?:[.．]\\d+)?)\\s+(?=[^\\n])",
                "\n=====SECTION=====\n$1 "
        );

        // 4. 按双换行符分割（兜底）
        processed = processed.replaceAll("\\n\\n+", "\n=====SECTION=====\n");

        // 5. 分割成原始块
        String[] chunks = processed.split("=====SECTION=====");
        for (String chunk : chunks) {
            String trimmed = chunk.trim();
            if (!trimmed.isEmpty()) {
                rawChunks.add(trimmed);
            }
        }

        // 6. 短段落向后合并（< 30字符认为是碎片，向后合并到前一个大段落）
        List<String> mergedChunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String chunk : rawChunks) {
            if (current.length() == 0) {
                current.append(chunk);
            } else if (chunk.length() < 30 && !current.toString().contains("\n\n")) {
                // 当前块过短且前一个块没有双换行（说明前一个块不是大段落），则合并
                current.append("\n").append(chunk);
            } else if (chunk.length() < 30) {
                // 当前块过短但前一个是大段落，直接合并
                current.append("\n").append(chunk);
            } else {
                // 当前块足够长，先保存上一个，再开启新的
                mergedChunks.add(current.toString());
                current = new StringBuilder(chunk);
            }
        }
        if (current.length() > 0) {
            mergedChunks.add(current.toString());
        }

        // 7. 构建 Paragraph 列表
        List<Paragraph> paragraphs = new ArrayList<>();
        int index = 0;
        for (String chunk : mergedChunks) {
            paragraphs.add(Paragraph.builder()
                    .content(chunk)
                    .sourceDoc(sourceDoc)
                    .paragraphIndex(index++)
                    .charCount(chunk.length())
                    .build());
        }

        log.info("文档切分为 {} 个段落", paragraphs.size());
        return paragraphs;
    }

    /**
     * 检测文件类型
     * @param filePath 文件路径
     * @return MIME类型
     */
    public String detectContentType(Path filePath) {
        try {
            return tika.detect(filePath);
        } catch (IOException e) {
            log.warn("检测文件类型失败: {}", filePath);
            return "application/octet-stream";
        }
    }

    /**
     * 判断是否为支持的文档类型
     * 仅支持 PDF 和 Word 两种格式
     */
    public boolean isSupported(Path filePath) {
        String contentType = detectContentType(filePath);
        return contentType != null && (
                contentType.equals("application/pdf") ||
                contentType.equals("application/msword") ||
                contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        );
    }

    // ==================== 内部类 ====================

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DocumentMetadata {
        private String fileName;
        private String contentType;
        private String title;
        private String author;
        private String createdDate;
        private String lastModified;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Paragraph {
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
         * 字符数
         */
        private int charCount;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ParseResult {
        private String content;
        private DocumentMetadata metadata;
        private List<Paragraph> paragraphs;
    }
}
