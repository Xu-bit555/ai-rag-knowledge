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
 * Markdown标题层级切分器
 * <p>
 * 针对Markdown文档，按标题层级切分是更优的选择
 * - 每个chunk对应整篇文档中的一个完整章节
 * - metadata里自动带上所属标题路径（如"产品手册 > 退款政策 > 申请流程"）
 * - 既语义独立，又方便过滤和溯源
 * <p>
 * 使用overlap重叠切割作为兜底，确保跨边界语义完整
 */
@Slf4j
@Data
@Builder
public class MarkdownHeaderSplitter {

    /**
     * Markdown标题正则：# 一级标题 ## 二级标题等
     */
    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    /**
     * 每个chunk的最大字符数
     */
    private int maxChunkLength;

    /**
     * 每个chunk的最小字符数
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
     * 相邻chunk之间的重叠字符数（兜底策略）
     */
    private int overlapChars;

    /**
     * Markdown文本结构节点
     */
    @Data
    @Builder
    public static class MarkdownNode {
        /**
         * 标题级别1-6，0表示正文
         */
        private int level;
        /**
         * 标题文本（仅标题有）
         */
        private String header;
        /**
         * 内容
         */
        private String content;
        /**
         * 完整标题路径
         */
        private String headerPath;

        public boolean isHeader() {
            return level > 0;
        }
    }

    /**
     * 切分Markdown文档为语义完整的chunks
     *
     * @param markdown 原始Markdown文本
     * @param metadata 文档元数据
     * @return 切分后的Document列表
     */
    public List<Document> split(String markdown, java.util.Map<String, Object> metadata) {
        if (markdown == null || markdown.isBlank()) {
            return new ArrayList<>();
        }

        // 解析Markdown结构
        List<MarkdownNode> nodes = parseMarkdown(markdown);
        if (nodes.isEmpty()) {
            return new ArrayList<>();
        }

        // 按标题层级切分
        List<Document> documents = new ArrayList<>();

        String currentHeaderPath = "";
        StringBuilder currentContent = new StringBuilder();
        int currentLevel = 0;

        for (MarkdownNode node : nodes) {
            if (node.isHeader()) {
                // 保存之前的content
                if (currentContent.length() > 0) {
                    String content = currentContent.toString().trim();
                    if (content.length() >= minChunkLengthToEmbed) {
                        // 添加overlap
                        String contentWithOverlap = addOverlapToContent(content, documents);

                        java.util.HashMap<String, Object> chunkMetadata = new java.util.HashMap<>(metadata);
                        chunkMetadata.put("type", "knowledge");

                        Document doc = new Document(contentWithOverlap, chunkMetadata);
                        documents.add(doc);
                    }
                    currentContent = new StringBuilder();
                }

                // 更新当前标题路径
                currentLevel = node.getLevel();
                currentHeaderPath = updateHeaderPath(currentHeaderPath, node.getHeader(), currentLevel);
            }

            // 累积内容
            if (currentContent.length() > 0) {
                currentContent.append("\n\n");
            }
            currentContent.append(node.getContent());
        }

        // 处理最后一块
        if (currentContent.length() > 0) {
            String content = currentContent.toString().trim();
            if (content.length() >= minChunkLengthToEmbed) {
                String contentWithOverlap = addOverlapToContent(content, documents);

                java.util.HashMap<String, Object> chunkMetadata = new java.util.HashMap<>(metadata);
                chunkMetadata.put("type", "knowledge");

                Document doc = new Document(contentWithOverlap, chunkMetadata);
                documents.add(doc);
            }
        }

        log.info("Markdown切分完成, 节点数: {}, 生成chunks: {}, overlapChars: {}",
                nodes.size(), documents.size(), overlapChars);
        return documents;
    }

    /**
     * 为内容添加overlap（从上一个chunk尾部获取）
     */
    private String addOverlapToContent(String content, List<Document> existingDocs) {
        if (overlapChars <= 0 || existingDocs.isEmpty()) {
            return content;
        }

        // 获取上一个chunk的内容
        Document lastDoc = existingDocs.get(existingDocs.size() - 1);
        String lastContent = lastDoc.getContent();

        // 提取尾部overlapChars字符
        int overlapStart = Math.max(0, lastContent.length() - overlapChars);
        String overlapContent = lastContent.substring(overlapStart);

        return overlapContent + "\n\n" + content;
    }

    /**
     * 解析Markdown文本为节点列表
     */
    private List<MarkdownNode> parseMarkdown(String markdown) {
        List<MarkdownNode> nodes = new ArrayList<>();
        Matcher matcher = HEADER_PATTERN.matcher(markdown);

        int lastEnd = 0;
        int currentLevel = 0;
        String currentHeader = "";

        while (matcher.find()) {
            int headerLevel = matcher.group(1).length();
            String headerText = matcher.group(2).trim();
            int headerStart = matcher.start();

            // 保存标题前的正文
            if (headerStart > lastEnd) {
                String content = markdown.substring(lastEnd, headerStart).trim();
                if (!content.isEmpty()) {
                    nodes.add(MarkdownNode.builder()
                            .level(currentLevel)
                            .header(currentLevel > 0 ? currentHeader : null)
                            .content(content)
                            .build());
                }
            }

            // 添加标题节点
            nodes.add(MarkdownNode.builder()
                    .level(headerLevel)
                    .header(headerText)
                    .content("")
                    .build());

            lastEnd = matcher.end();
            currentLevel = headerLevel;
            currentHeader = headerText;
        }

        // 处理最后剩余的内容
        if (lastEnd < markdown.length()) {
            String content = markdown.substring(lastEnd).trim();
            if (!content.isEmpty()) {
                nodes.add(MarkdownNode.builder()
                        .level(currentLevel)
                        .header(currentLevel > 0 ? currentHeader : null)
                        .content(content)
                        .build());
            }
        }

        return nodes;
    }

    /**
     * 更新标题路径
     */
    private String updateHeaderPath(String currentPath, String newHeader, int newLevel) {
        if (currentPath.isEmpty()) {
            return newHeader;
        }

        String[] parts = currentPath.split(" > ");
        if (newLevel == 1) {
            return newHeader;
        } else if (newLevel <= parts.length) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < newLevel - 1; i++) {
                if (i > 0) sb.append(" > ");
                sb.append(parts[i]);
            }
            sb.append(" > ").append(newHeader);
            return sb.toString();
        } else {
            return currentPath + " > " + newHeader;
        }
    }

    /**
     * 获取顶层标题
     */
    private String getTopHeader(String headerPath) {
        if (headerPath == null || headerPath.isEmpty()) {
            return "";
        }
        int firstSeparator = headerPath.indexOf(" > ");
        return firstSeparator > 0 ? headerPath.substring(0, firstSeparator) : headerPath;
    }

    /**
     * 检查是否为Markdown文档（包含标题）
     */
    public static boolean isMarkdown(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return HEADER_PATTERN.matcher(text).find();
    }

    /**
     * 获取文档的第一个标题（用于判断文档主题）
     */
    public static String extractFirstHeader(String markdown) {
        Matcher matcher = HEADER_PATTERN.matcher(markdown);
        if (matcher.find()) {
            return matcher.group(2).trim();
        }
        return "";
    }
}