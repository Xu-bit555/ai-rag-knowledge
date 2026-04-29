package cn.bugstack.rag.splitter;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义边界切分器
 * <p>
 * 按文档的自然语义边界切分：段落 > 句子 > 标点
 * 优点：按段落切割每个chunk都是围绕同一话题的完整段落，语义独立性最好
 * <p>
 * 流程：维护分隔符优先级列表，先尝试按段落切，切出来太大再按句子切，以此类推
 * 同时使用overlap重叠切割作为兜底，确保跨边界语义完整
 */
@Slf4j
@Data
@Builder
public class SemanticSplitter {

    /**
     * 段落分隔符：空行\n\n
     */
    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("(?<=\\n\\n)");

    /**
     * 句子分隔符：句号、问号、感叹号
     */
    private static final Pattern SENTENCE_SEPARATOR = Pattern.compile("(?<=[。！？.!?])");

    /**
     * 标点分隔符：逗号、分号（用于最后手段）
     */
    private static final Pattern PUNCTUATION_SEPARATOR = Pattern.compile("(?<=[，,；;])");

    /**
     * 每个chunk的最大字符数
     */
    private int maxChunkLength;

    /**
     * 每个chunk的最小字符数，过小则合并到上一块
     */
    private int minChunkLength;

    /**
     * 小于此字符长度的chunk不进行embedding
     */
    private int minChunkLengthToEmbed;

    /**
     * 是否保留分隔符
     */
    private boolean keepSeparator;

    /**
     * 相邻chunk之间的重叠字符数（兜底策略，约为chunk的10%-20%）
     */
    private int overlapChars;

    /**
     * 语义切分级别：1-段落, 2-句子, 3-标点
     */
    private int splitLevel;

    /**
     * 切分文本为语义完整的chunks
     *
     * @param text 原始文本
     * @param metadata 文档元数据
     * @return 切分后的Document列表
     */
    public List<Document> split(String text, java.util.Map<String, Object> metadata) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        text = cleanText(text);
        if (text.isEmpty()) {
            return new ArrayList<>();
        }

        // 根据splitLevel选择切分策略
        List<String> semanticChunks;
        switch (splitLevel) {
            case 1:
                semanticChunks = splitByParagraph(text);
                break;
            case 2:
                semanticChunks = splitBySentence(text);
                break;
            case 3:
                semanticChunks = splitByPunctuation(text);
                break;
            default:
                semanticChunks = splitByParagraph(text);
        }

        // 合并过小的chunks
        semanticChunks = mergeSmallChunks(semanticChunks);

        // 构建Document列表（带overlap）
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < semanticChunks.size(); i++) {
            String chunk = semanticChunks.get(i);
            if (chunk.length() < minChunkLengthToEmbed) {
                continue;
            }

            // 添加overlap：当前chunk头部包含上一个chunk的尾部内容
            String chunkWithOverlap = addOverlap(chunk, i, semanticChunks);

            java.util.HashMap<String, Object> chunkMetadata = new java.util.HashMap<>(metadata);
            chunkMetadata.put("type", "knowledge");

            Document doc = new Document(chunkWithOverlap, chunkMetadata);
            documents.add(doc);
        }

        log.debug("语义切分完成, 原始段落数: {}, 生成chunks: {}, overlapChars: {}",
                semanticChunks.size(), documents.size(), overlapChars);
        return documents;
    }

    /**
     * 清理空白字符，保留换行结构
     */
    private String cleanText(String text) {
        text = text.replaceAll("[ \\t]+", " ");
        text = text.replaceAll("(?<=\\n) +(?=\\n)", "");
        return text.trim();
    }

    /**
     * 添加overlap重叠内容
     */
    private String addOverlap(String chunk, int index, List<String> allChunks) {
        if (overlapChars <= 0 || index == 0) {
            return chunk;
        }

        // 获取前一个chunk的尾部overlapChars字符
        String prevChunk = allChunks.get(index - 1);
        int overlapStart = Math.max(0, prevChunk.length() - overlapChars);
        String overlapContent = prevChunk.substring(overlapStart);

        // 拼接：overlap内容 + 分隔符 + 当前chunk
        return overlapContent + "\n\n" + chunk;
    }

    /**
     * 级别1：按段落切分（空行\n\n）
     */
    private List<String> splitByParagraph(String text) {
        String[] paragraphs = text.split(PARAGRAPH_SEPARATOR.pattern());
        List<String> result = new ArrayList<>();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }

        return result;
    }

    /**
     * 级别2：按句子切分（。！？.!?）
     */
    private List<String> splitBySentence(String text) {
        String[] sentences = text.split(SENTENCE_SEPARATOR.pattern());
        List<String> result = new ArrayList<>();

        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }

        return result;
    }

    /**
     * 级别3：按标点切分（，,；;）
     */
    private List<String> splitByPunctuation(String text) {
        String[] parts = text.split(PUNCTUATION_SEPARATOR.pattern());
        List<String> result = new ArrayList<>();

        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }

        return result;
    }

    /**
     * 合并过小的chunks到上一块
     */
    private List<String> mergeSmallChunks(List<String> chunks) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        List<String> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String chunk : chunks) {
            if (buffer.isEmpty()) {
                buffer.append(chunk);
            } else if (buffer.length() + chunk.length() <= maxChunkLength) {
                buffer.append("\n\n").append(chunk);
            } else {
                merged.add(buffer.toString());
                buffer = new StringBuilder(chunk);
            }
        }

        // 处理最后一块
        if (buffer.length() > 0) {
            if (!merged.isEmpty() && merged.get(merged.size() - 1).length() + buffer.length() <= maxChunkLength) {
                merged.set(merged.size() - 1, merged.get(merged.size() - 1) + "\n\n" + buffer);
            } else {
                merged.add(buffer.toString());
            }
        }

        return merged;
    }

    /**
     * 动态判断文本适合的切分级别
     * <p>
     * 从段落级别开始尝试，如果段落太大则降级到句子级别，
     * 如果句子太大则继续降级直到标点级别
     */
    public int determineSplitLevel(String text) {
        // 先按段落切
        List<String> paragraphs = splitByParagraph(text);
        for (String para : paragraphs) {
            if (para.length() > maxChunkLength) {
                // 段落太大，尝试按句子切
                List<String> sentences = splitBySentence(text);
                for (String sentence : sentences) {
                    if (sentence.length() > maxChunkLength) {
                        // 句子太大，使用标点切
                        return 3;
                    }
                }
                return 2;
            }
        }
        return 1;
    }
}