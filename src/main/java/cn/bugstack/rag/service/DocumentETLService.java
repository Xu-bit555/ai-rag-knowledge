package cn.bugstack.rag.service;

/**
 * 文档 ETL 服务接口
 * Extract: 读取 PDF/Word 等多格式文档
 * Transform: 进行文本切分
 * Load: 向量库存储
 */
public interface DocumentETLService {

    /**
     * ETL 流水线：读取文件 → 切分 → 存入向量库
     *
     * @param filePath 文件路径
     * @param ragTag   知识库标签
     */
    void etlPipeline(String filePath, String ragTag);
}