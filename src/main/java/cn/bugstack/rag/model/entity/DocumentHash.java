package cn.bugstack.rag.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档Hash实体
 * <p>
 * 用于检测文档是否发生变化：
 * - 存储文档内容的SHA-256哈希值
 * - 关联文档ID与对应的chunk ID列表
 * - 支持知识库增量更新
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentHash {

    /**
     * 文档ID（如文件名或业务ID）
     */
    private String docId;

    /**
     * 知识库标签
     */
    private String ragTag;

    /**
     * 文档内容的SHA-256哈希值
     */
    private String contentHash;

    /**
     * 该文档在PGVector中的chunk ID列表（JSON数组）
     */
    private String chunkIds;

    /**
     * 最后修改时间（用于快速粗筛）
     */
    private LocalDateTime lastModified;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 获取chunkId列表
     */
    public java.util.List<Long> getChunkIdList() {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            return com.alibaba.fastjson2.JSON.parseArray(chunkIds, Long.class);
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 设置chunkId列表
     */
    public void setChunkIdList(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            this.chunkIds = "[]";
        } else {
            this.chunkIds = com.alibaba.fastjson2.JSON.toJSONString(ids);
        }
    }
}
