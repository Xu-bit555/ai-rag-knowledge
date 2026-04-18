package cn.bugstack.rag.service;

import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.List;

/**
 * 文档解析服务接口
 */
public interface DocumentParserService {

    /**
     * 解析文档内容
     * @param filePath 文件路径
     * @return 提取的文本内容
     */
    String parseDocument(Path filePath);

    /**
     * 解析文档并返回带元数据的内容
     * @param filePath 文件路径
     * @return 解析结果包含内容和建议的切分点
     */
    ParseResult parseWithMetadata(Path filePath);

    /**
     * 从字节数组解析文档（不落盘）
     * @param bytes 文件字节数组
     * @param fileName 文件名
     * @return 解析结果包含内容和建议的切分点
     */
    ParseResult parseFromBytes(byte[] bytes, String fileName);

    /**
     * 提取文档元数据
     * @param filePath 文件路径
     * @return 元数据对象
     */
    DocumentMetadata extractMetadata(Path filePath);

    /**
     * 将文档内容切分为段落
     * @param content 文档内容
     * @param sourceDoc 源文档名
     * @return 段落列表
     */
    List<Paragraph> splitIntoParagraphs(String content, String sourceDoc);

    /**
     * 检测文件类型
     * @param filePath 文件路径
     * @return MIME类型
     */
    String detectContentType(Path filePath);

    /**
     * 判断是否为支持的文档类型
     */
    boolean isSupported(Path filePath);

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class DocumentMetadata {
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
    class Paragraph {
        private String content;
        private String sourceDoc;
        private int paragraphIndex;
        private int charCount;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class ParseResult {
        private String content;
        private DocumentMetadata metadata;
        private List<Paragraph> paragraphs;
    }
}