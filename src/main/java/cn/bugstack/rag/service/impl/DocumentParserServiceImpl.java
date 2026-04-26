package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.service.DocumentParserService;
import cn.bugstack.rag.service.TableParserService;
import jakarta.annotation.Resource;
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
 * 多格式文档解析服务实现
 * 支持 PDF、Word、Excel、PPT、TXT、Markdown、HTML、JSON 等多种格式
 * 增强：支持PDF表格提取
 */
@Slf4j
@Service
public class DocumentParserServiceImpl implements DocumentParserService {

    private final Tika tika = new Tika();
    private final AutoDetectParser parser = new AutoDetectParser();

    @Resource
    private TableParserService tableParserService;

    @Override
    public String parseDocument(Path filePath) {
        log.info("开始解析文档: {}", filePath);

        try (InputStream stream = Files.newInputStream(filePath)) {
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

    @Override
    public ParseResult parseWithMetadata(Path filePath) {
        String content = parseDocument(filePath);
        DocumentMetadata docMeta = extractMetadata(filePath);

        List<Paragraph> paragraphs = splitIntoParagraphs(content, filePath.getFileName().toString());

        return ParseResult.builder()
                .content(content)
                .metadata(docMeta)
                .paragraphs(paragraphs)
                .build();
    }

    @Override
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

            // 如果是PDF文件，额外提取表格
            List<TableParserService.Table> tables = new ArrayList<>();
            String contentType = metadata.get(org.apache.tika.metadata.Metadata.CONTENT_TYPE);
            if (contentType != null && contentType.contains("pdf")) {
                try {
                    tables = tableParserService.extractTables(bytes);
                    log.info("从PDF提取到 {} 个表格", tables.size());

                    // 将表格内容追加到段落中（转为Markdown格式）
                    for (TableParserService.Table table : tables) {
                        String markdownTable = tableParserService.tableToMarkdown(table);
                        paragraphs.add(Paragraph.builder()
                                .content("[表格-第" + table.getPageNumber() + "页-表" + (table.getTableIndex() + 1) + "]\n" + markdownTable)
                                .sourceDoc(fileName)
                                .paragraphIndex(paragraphs.size())
                                .charCount(markdownTable.length())
                                .build());
                    }
                } catch (Exception e) {
                    log.warn("PDF表格提取失败, 文件: {}, 错误: {}", fileName, e.getMessage());
                }
            }

            return ParseResult.builder()
                    .content(content)
                    .metadata(docMeta)
                    .paragraphs(paragraphs)
                    .tables(tables)
                    .build();
        } catch (IOException | SAXException | TikaException e) {
            log.error("文档解析失败: {}", fileName, e);
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public DocumentMetadata extractMetadata(Path filePath) {
        try (InputStream stream = Files.newInputStream(filePath)) {
            org.apache.tika.metadata.Metadata tikaMetadata = new org.apache.tika.metadata.Metadata();
            tikaMetadata.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, filePath.getFileName().toString());

            parser.parse(stream, new BodyContentHandler(), tikaMetadata, new ParseContext());

            DocumentMetadata docMeta = new DocumentMetadata();
            docMeta.setFileName(filePath.getFileName().toString());
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

    @Override
    public List<Paragraph> splitIntoParagraphs(String content, String sourceDoc) {
        List<String> rawChunks = new ArrayList<>();

        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }

        content = content.replace("\r\n", "\n").replace("\r", "\n");

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

        String[] majorSectionPatterns = {
                "(?=\\s*第[一二三四五六七八九十\\d]+章\\s)",
                "(?=\\s*第[一二三四五六七八九十\\d]+节\\s)",
                "(?=\\s*第[一二三四五六七八九十\\d]+[.．][一二三四五六七八九十\\d]+\\s)"
        };

        String processed = cleaned;
        for (String pattern : majorSectionPatterns) {
            String[] parts = processed.split(pattern);
            if (parts.length > 1) {
                processed = String.join("\n=====SECTION=====\n", parts);
            }
        }

        processed = processed.replaceAll(
                "(?m)^(\\d+[.．]\\d+(?:[.．]\\d+)?)\\s+(?=[^\\n])",
                "\n=====SECTION=====\n$1 "
        );

        processed = processed.replaceAll("\\n\\n+", "\n=====SECTION=====\n");

        String[] chunks = processed.split("=====SECTION=====");
        for (String chunk : chunks) {
            String trimmed = chunk.trim();
            if (!trimmed.isEmpty()) {
                rawChunks.add(trimmed);
            }
        }

        List<String> mergedChunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String chunk : rawChunks) {
            if (current.length() == 0) {
                current.append(chunk);
            } else if (chunk.length() < 30 && !current.toString().contains("\n\n")) {
                current.append("\n").append(chunk);
            } else if (chunk.length() < 30) {
                current.append("\n").append(chunk);
            } else {
                mergedChunks.add(current.toString());
                current = new StringBuilder(chunk);
            }
        }
        if (current.length() > 0) {
            mergedChunks.add(current.toString());
        }

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

    @Override
    public String detectContentType(Path filePath) {
        try {
            return tika.detect(filePath);
        } catch (IOException e) {
            log.warn("检测文件类型失败: {}", filePath);
            return "application/octet-stream";
        }
    }

    @Override
    public boolean isSupported(Path filePath) {
        String contentType = detectContentType(filePath);
        return contentType != null && (
                contentType.equals("application/pdf") ||
                contentType.equals("application/msword") ||
                contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        );
    }
}