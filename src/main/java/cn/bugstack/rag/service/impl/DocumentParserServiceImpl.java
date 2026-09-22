package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.service.DocumentParserService;
import cn.bugstack.rag.service.TableParserService;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
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

    /**
     * Phase 2 R5: jtokkit BPE tokenizer. CL100K_BASE 是 GPT-3.5/4 同款编码,
     *   对英文 BPE 准确, 中文需拆字符 (jtokkit 对 CJK 处理不算完美).
     */
    private static final EncodingRegistry ENCODING_REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding ENCODING = ENCODING_REGISTRY.getEncoding(EncodingType.CL100K_BASE);

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
        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }

        // 清理内容：统一换行符，去除乱码和纯数字行
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

        // 第一阶段：按段落拆分，同时识别和保护 Markdown 表格
        List<Block> blocks = splitIntoBlocksWithTableProtection(cleaned);

        // 第二阶段：对每个块进行 token 评估，超过限制的进行切分
        List<Paragraph> result = new ArrayList<>();
        int paragraphIndex = 0;
        for (Block block : blocks) {
            if (block.isTable()) {
                // Markdown 表格块：保持表格结构完整性，按 token 限制切分
                List<String> tableChunks = splitTableByToken(block.getContent(), 300, 100);
                for (String chunk : tableChunks) {
                    result.add(Paragraph.builder()
                            .content(chunk)
                            .sourceDoc(sourceDoc)
                            .paragraphIndex(paragraphIndex++)
                            .charCount(chunk.length())
                            .build());
                }
            } else {
                // 普通文本块：处理逻辑不变
                String singleLinePara = block.getContent().replace("\n", " ").replaceAll("\\s+", " ");
                int tokenCount = estimateTokenCount(singleLinePara);

                if (tokenCount <= 500) {
                    result.add(Paragraph.builder()
                            .content(singleLinePara)
                            .sourceDoc(sourceDoc)
                            .paragraphIndex(paragraphIndex++)
                            .charCount(singleLinePara.length())
                            .build());
                } else {
                    List<String> sentences = splitIntoSentences(singleLinePara);
                    for (String sentence : sentences) {
                        int sentenceTokenCount = estimateTokenCount(sentence);
                        if (sentenceTokenCount <= 300) {
                            result.add(Paragraph.builder()
                                    .content(sentence)
                                    .sourceDoc(sourceDoc)
                                    .paragraphIndex(paragraphIndex++)
                                    .charCount(sentence.length())
                                    .build());
                        } else {
                            List<String> fixedChunks = fixedTokenSplit(sentence, 300, 100);
                            for (String chunk : fixedChunks) {
                                result.add(Paragraph.builder()
                                        .content(chunk)
                                        .sourceDoc(sourceDoc)
                                        .paragraphIndex(paragraphIndex++)
                                        .charCount(chunk.length())
                                        .build());
                            }
                        }
                    }
                }
            }
        }

        log.info("文档切分为 {} 个段落", result.size());
        return result;
    }

    /**
     * 块类型：普通文本 或 Markdown 表格
     */
    private static class Block {
        private final String content;
        private final boolean table;

        public Block(String content, boolean table) {
            this.content = content;
            this.table = table;
        }

        public String getContent() { return content; }
        public boolean isTable() { return table; }
    }

    /**
     * 第一阶段切分：识别 Markdown 表格并保护，表格块整体保留
     */
    private List<Block> splitIntoBlocksWithTableProtection(String cleaned) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = cleaned.split("\n");
        StringBuilder currentText = new StringBuilder();
        StringBuilder currentTable = new StringBuilder();
        boolean inTable = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (isMarkdownTableRow(line)) {
                // 进入表格模式
                if (!inTable) {
                    // 先保存之前的文本块
                    if (currentText.length() > 0) {
                        String text = currentText.toString().trim();
                        if (!text.isEmpty()) {
                            blocks.add(new Block(text, false));
                        }
                        currentText.setLength(0);
                    }
                    inTable = true;
                    currentTable.setLength(0);
                }
                currentTable.append(line).append("\n");
            } else {
                // 非表格行
                if (inTable) {
                    // 表格结束，保存表格块
                    String tableContent = currentTable.toString().trim();
                    if (!tableContent.isEmpty() && isMarkdownTableBlock(tableContent)) {
                        blocks.add(new Block(tableContent, true));
                    }
                    currentTable.setLength(0);
                    inTable = false;
                }
                // 如果是非表格内容，加入文本块
                if (!line.isEmpty()) {
                    currentText.append(line).append("\n");
                } else {
                    // 遇到空行，保存当前文本（如果是连续非空行组成的段落）
                    String text = currentText.toString().trim();
                    if (!text.isEmpty()) {
                        blocks.add(new Block(text, false));
                        currentText.setLength(0);
                    }
                }
            }
        }

        // 处理最后残留的内容
        if (inTable) {
            String tableContent = currentTable.toString().trim();
            if (!tableContent.isEmpty() && isMarkdownTableBlock(tableContent)) {
                blocks.add(new Block(tableContent, true));
            }
        }
        if (currentText.length() > 0) {
            String text = currentText.toString().trim();
            if (!text.isEmpty()) {
                blocks.add(new Block(text, false));
            }
        }

        return blocks;
    }

    /**
     * 判断一行是否是 Markdown 表格行（以 | 开头或结尾）
     */
    private boolean isMarkdownTableRow(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        String trimmed = line.trim();
        // 表格行必须以 | 开头或结尾，且包含内容
        return (trimmed.startsWith("|") || trimmed.endsWith("|"))
                && trimmed.contains("|")
                && !trimmed.matches("^\\|+$"); // 排除全是分隔符的行
    }

    /**
     * 判断一组行是否构成有效的 Markdown 表格
     * 有效表格：至少2行，第一行是表头行，第二行是分隔行（|---|）
     */
    private boolean isMarkdownTableBlock(String tableContent) {
        if (tableContent == null || tableContent.isEmpty()) {
            return false;
        }
        String[] lines = tableContent.split("\n");
        if (lines.length < 2) {
            return false;
        }

        // 检查是否包含有效的分隔行（| --- | --- | 或类似格式）
        for (String line : lines) {
            String trimmed = line.trim();
            // 分隔行格式：| --- | --- | ... 或 |:---|:---:|... 等
            if (trimmed.matches("^\\|\\s*[-:]+\\s*(\\|\\s*[-:]+\\s*)+$")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 token 限制切分表格，保持表格结构完整性
     */
    private List<String> splitTableByToken(String tableContent, int maxTokens, int overlapTokens) {
        List<String> chunks = new ArrayList<>();
        if (tableContent == null || tableContent.isEmpty()) {
            return chunks;
        }

        String[] lines = tableContent.split("\n");
        // 找到分隔行位置（表头后面的分隔行）
        int separatorIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().matches("^\\|\\s*[-:]+\\s*(\\|\\s*[-:]+\\s*)+$")) {
                separatorIndex = i;
                break;
            }
        }

        if (separatorIndex < 0) {
            // 没有标准分隔行，按普通文本处理
            return splitTextByToken(tableContent, maxTokens, overlapTokens);
        }

        // 表头行
        String header = lines[0];
        // 分隔行
        String separator = lines[separatorIndex];
        // 数据行
        List<String> dataRows = new ArrayList<>();
        for (int i = separatorIndex + 1; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) {
                dataRows.add(lines[i]);
            }
        }

        // 先放入表头和分隔行
        String headerWithSeparator = header + "\n" + separator;
        int headerTokenCount = estimateTokenCount(headerWithSeparator);

        StringBuilder currentChunk = new StringBuilder();
        boolean firstDataRowInChunk = true;

        for (String dataRow : dataRows) {
            String rowWithHeader = headerWithSeparator + "\n" + (firstDataRowInChunk ? "" : currentChunk.toString() + "\n") + dataRow;
            int rowTokenCount = estimateTokenCount(dataRow);

            if (currentChunk.length() == 0) {
                // 新块，检查表头+此行是否超限
                if (headerTokenCount + rowTokenCount > maxTokens) {
                    // 表头+此行就超限，放入表头+分隔行，然后单独放此行
                    if (headerTokenCount <= maxTokens) {
                        chunks.add(headerWithSeparator);
                    }
                    if (rowTokenCount <= maxTokens) {
                        chunks.add(headerWithSeparator + "\n" + dataRow);
                    } else {
                        // 数据行本身超限，按固定token切分
                        List<String> rowChunks = splitTextByToken(dataRow, maxTokens, overlapTokens);
                        for (int i = 0; i < rowChunks.size(); i++) {
                            if (i == 0) {
                                chunks.add(headerWithSeparator + "\n" + rowChunks.get(i));
                            } else {
                                // 后续块需要补充表头
                                chunks.add(headerWithSeparator + "\n" + rowChunks.get(i));
                            }
                        }
                    }
                    firstDataRowInChunk = false;
                    currentChunk.setLength(0);
                    continue;
                } else {
                    currentChunk.append(dataRow);
                    firstDataRowInChunk = false;
                }
            } else {
                // 已有数据的块，检查加上此行是否超限
                int currentChunkToken = estimateTokenCount(currentChunk.toString());
                if (currentChunkToken + rowTokenCount <= maxTokens) {
                    currentChunk.append("\n").append(dataRow);
                } else {
                    // 当前块已满，保存并开始新块
                    chunks.add(headerWithSeparator + "\n" + currentChunk.toString());
                    // overlap: 保留前几行数据
                    List<String> overlapRows = getOverlapRows(currentChunk.toString(), overlapTokens, header);
                    currentChunk.setLength(0);
                    for (String overlapRow : overlapRows) {
                        currentChunk.append(overlapRow).append("\n");
                    }
                    currentChunk.append(dataRow);
                }
            }
        }

        // 保存最后一块
        if (currentChunk.length() > 0) {
            chunks.add(headerWithSeparator + "\n" + currentChunk.toString());
        }

        return chunks;
    }

    /**
     * 获取用于 overlap 的行（从末尾往前取，保证 token 数不超过 overlapTokens）
     */
    private List<String> getOverlapRows(String chunkContent, int overlapTokens, String header) {
        List<String> rows = new ArrayList<>();
        String[] lines = chunkContent.split("\n");
        // 从最后一行往前取
        StringBuilder overlap = new StringBuilder();
        for (int i = lines.length - 1; i >= 0; i--) {
            String row = lines[i];
            int testOverlap = estimateTokenCount(overlap.toString() + row);
            if (testOverlap > overlapTokens) {
                break;
            }
            overlap.insert(0, row + "\n");
            rows.add(0, row); // 保持原始顺序
        }
        return rows;
    }

    /**
     * 纯文本按 token 限制切分（非表格）
     */
    private List<String> splitTextByToken(String text, int maxTokens, int overlapTokens) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        // 按段落/句子拆分
        List<String> sentences = splitIntoSentences(text);
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            int sentenceTokenCount = estimateTokenCount(sentence);
            int currentChunkTokenCount = estimateTokenCount(currentChunk.toString());

            if (currentChunk.length() == 0) {
                if (sentenceTokenCount <= maxTokens) {
                    currentChunk.append(sentence);
                } else {
                    // 句子本身超限，固定切分
                    List<String> fixed = fixedTokenSplit(sentence, maxTokens, overlapTokens);
                    for (int i = 0; i < fixed.size(); i++) {
                        if (i < fixed.size() - 1) {
                            chunks.add(fixed.get(i));
                        } else {
                            currentChunk.append(fixed.get(i));
                        }
                    }
                }
            } else if (currentChunkTokenCount + sentenceTokenCount <= maxTokens) {
                currentChunk.append(" ").append(sentence);
            } else {
                chunks.add(currentChunk.toString());
                // overlap
                String lastPart = getOverlapText(currentChunk.toString(), overlapTokens);
                currentChunk.setLength(0);
                if (!lastPart.isEmpty()) {
                    currentChunk.append(lastPart).append(" ").append(sentence);
                } else {
                    currentChunk.append(sentence);
                }
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    /**
     * 获取用于 overlap 的文本（从末尾往前取 token）
     */
    private String getOverlapText(String text, int overlapTokens) {
        List<String> sentences = splitIntoSentences(text);
        StringBuilder overlap = new StringBuilder();
        for (int i = sentences.size() - 1; i >= 0; i--) {
            String sentence = sentences.get(i);
            int testOverlap = estimateTokenCount(overlap.toString() + sentence);
            if (testOverlap > overlapTokens) {
                break;
            }
            overlap.insert(0, sentence + " ");
        }
        return overlap.toString().trim();
    }

    /**
     * Phase 2 R5: 用 jtokkit BPE tokenizer 估算 token 数 (替代中文 0.5 / 英文 1.25 启发式).
     *
     * 中文按 char 拆分 (jtokkit CL100K_BASE 对 CJK 会合并, 拆字符避免被 BPE 合并),
     * 其余文本走 BPE 计数.
     */
    private int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 1) 中文字符用空格隔开, 防止 BPE 合并相邻中文字
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION) {
                sb.append(' ').append(c).append(' ');
            } else {
                sb.append(c);
            }
        }
        return ENCODING.countTokens(sb.toString());
    }

    /**
     * 按句子分割（中文句号、英文句号、问号、感叹号）
     */
    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        // 句子结束符：。！？.?! 以及后续紧跟空格或换行
        String[] parts = text.split("(?<=[。！？.?!])\\s*");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }

    /**
     * 固定 token 数切分，overlap 作为相邻 chunk 的重叠 token 数
     * @param text 文本
     * @param maxTokens 每个 chunk 的最大 token 数
     * @param overlapTokens 重叠的 token 数
     */
    private List<String> fixedTokenSplit(String text, int maxTokens, int overlapTokens) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        int start = 0;
        while (start < text.length()) {
            // 估算当前 start 位置开始的 token 数，找到不超过 maxTokens 的结束位置
            int end = findTokenBoundary(text, start, maxTokens);
            if (end <= start) {
                // 防止死循环，至少往前推进一个字符
                end = start + 1;
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            // 下一次开始位置：往前回退 overlapTokens 对应的字符数
            int backtrackChars = estimateCharactersForTokens(text, start, overlapTokens);
            start = Math.max(0, end - backtrackChars);
        }

        return chunks;
    }

    /**
     * 从 start 位置往前/往后估算约 tokens 个 token 对应的字符数
     */
    private int estimateCharactersForTokens(String text, int start, int tokens) {
        // 简化估算：中文 2 字符 ≈ 1 token，英文 1 词 ≈ 1.25 token
        // 往前估算 tokens 个 token 约需要 2 * tokens 个中文字符
        return (int) Math.ceil(tokens * 2);
    }

    /**
     * 从 start 位置往后找到不超过 maxTokens 的字符边界
     */
    private int findTokenBoundary(String text, int start, int maxTokens) {
        // 先估算 text 从 start 开始的 token 数
        String remaining = text.substring(start);
        int estimatedTokens = estimateTokenCount(remaining);
        if (estimatedTokens <= maxTokens) {
            return text.length();
        }

        // 二分查找合适的结束位置
        int left = start;
        int right = text.length();
        while (left + 1 < right) {
            int mid = (left + right) / 2;
            String substr = text.substring(start, mid);
            if (estimateTokenCount(substr) <= maxTokens) {
                left = mid;
            } else {
                right = mid;
            }
        }
        return left;
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