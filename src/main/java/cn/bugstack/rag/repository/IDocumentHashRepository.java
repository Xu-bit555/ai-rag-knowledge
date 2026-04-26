package cn.bugstack.rag.repository;

import cn.bugstack.rag.model.entity.DocumentHash;

import java.util.List;
import java.util.Optional;

/**
 * 文档Hash仓储接口
 * <p>
 * 负责存储文档hash信息，用于检测文档变更
 */
public interface IDocumentHashRepository {

    /**
     * 插入或更新文档Hash记录（Upsert）
     */
    int upsert(DocumentHash documentHash);

    /**
     * 根据docId和ragTag查询
     */
    Optional<DocumentHash> findByDocIdAndRagTag(String docId, String ragTag);

    /**
     * 根据docId和ragTag查询（仅返回hash值）
     */
    String findHashByDocIdAndRagTag(String docId, String ragTag);

    /**
     * 根据ragTag查询所有文档Hash
     */
    List<DocumentHash> findActiveByRagTag(String ragTag);

    /**
     * 更新chunkIds
     */
    int updateChunkIds(String docId, String ragTag, String chunkIds);

    /**
     * 删除文档Hash记录
     */
    int delete(String docId, String ragTag);

    /**
     * 批量插入或更新
     */
    int batchUpsert(List<DocumentHash> documentHashes);
}