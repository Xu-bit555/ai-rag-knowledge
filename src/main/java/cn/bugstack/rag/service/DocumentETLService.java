package cn.bugstack.rag.service;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * 文档 ETL 服务接口
 * Extract: 读取 PDF/Word 等多格式文档（支持 InputStream/Resource，云原生友好）
 * Transform: 基于标点符号递归切分 + Token 计数限制，确保语义完整
 * Load: 分批写入向量库，避免 OOM
 */
public interface DocumentETLService {

    /**
     * ETL 流水线：读取文件流 → 语义切分 → 分批存入向量库
     * <p>
     * 云原生设计：接收 Spring 标准 Resource，支持 FileSystemResource、UrlResource、ByteArrayResource 等
     * 统一接口，兼容本地文件、云存储（OSS/S3）、远程 URL 等任意输入源。
     *
     * @param resource 文件资源
     * @param fileName 文件名（用于日志追溯和来源追踪，从 Resource 或调用方传入）
     * @param ragTag   知识库标签
     */
    void etlPipeline(Resource resource, String fileName, String ragTag);

    /**
     * ETL 流水线（更新模式）：先删旧chunk再新增，返回新插入的Document列表
     * <p>
     * 用于文档更新场景：
     * 1. 根据docId精确删除旧chunk
     * 2. 执行ETL新增
     * 3. 返回新chunk列表（用于提取chunkIds更新document_hash表）
     *
     * @param resource 文件资源
     * @param fileName 文件名
     * @param ragTag   知识库标签
     * @param docId    文档ID（用于metadata标识和精准删除）
     * @return 新插入的Document列表
     */
    List<Document> etlPipelineForUpdate(Resource resource, String fileName, String ragTag, String docId);

}
