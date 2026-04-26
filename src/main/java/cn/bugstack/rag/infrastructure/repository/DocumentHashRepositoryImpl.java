package cn.bugstack.rag.infrastructure.repository;

import cn.bugstack.rag.model.entity.DocumentHash;
import cn.bugstack.rag.repository.IDocumentHashRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 文档Hash仓储实现
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DocumentHashRepositoryImpl implements IDocumentHashRepository {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        log.info("开始初始化 document_hash 表...");
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS document_hash (
                    doc_id VARCHAR(256) NOT NULL,
                    rag_tag VARCHAR(128) NOT NULL,
                    content_hash VARCHAR(64) NOT NULL,
                    chunk_ids TEXT NOT NULL DEFAULT '[]',
                    last_modified TIMESTAMP NOT NULL DEFAULT NOW(),
                    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (doc_id, rag_tag)
                )
                """);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_document_hash_rag_tag ON document_hash(rag_tag)");
            log.info("document_hash 表初始化完成");
        } catch (Exception e) {
            log.error("初始化 document_hash 表失败: {}", e.getMessage(), e);
        }
    }

    private static final RowMapper<DocumentHash> ROW_MAPPER = (rs, rowNum) -> DocumentHash.builder()
            .docId(rs.getString("doc_id"))
            .ragTag(rs.getString("rag_tag"))
            .contentHash(rs.getString("content_hash"))
            .chunkIds(rs.getString("chunk_ids"))
            .lastModified(rs.getTimestamp("last_modified").toLocalDateTime())
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .updatedAt(rs.getTimestamp("updated_at").toLocalDateTime())
            .build();

    @Override
    public int upsert(DocumentHash documentHash) {
        String sql = """
            INSERT INTO document_hash (doc_id, rag_tag, content_hash, chunk_ids, last_modified)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (doc_id, rag_tag) DO UPDATE SET
                content_hash = EXCLUDED.content_hash,
                chunk_ids = EXCLUDED.chunk_ids,
                last_modified = EXCLUDED.last_modified,
                updated_at = NOW()
            """;
        return jdbcTemplate.update(sql,
                documentHash.getDocId(),
                documentHash.getRagTag(),
                documentHash.getContentHash(),
                documentHash.getChunkIds() != null ? documentHash.getChunkIds() : "[]",
                documentHash.getLastModified() != null ? documentHash.getLastModified() : LocalDateTime.now());
    }

    @Override
    public Optional<DocumentHash> findByDocIdAndRagTag(String docId, String ragTag) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT doc_id, rag_tag, content_hash, chunk_ids, last_modified, created_at, updated_at FROM document_hash WHERE doc_id = ? AND rag_tag = ?",
                    ROW_MAPPER, docId, ragTag));
        } catch (Exception e) {
            log.debug("查询DocumentHash失败, docId: {}, ragTag: {}", docId, ragTag);
            return Optional.empty();
        }
    }

    @Override
    public String findHashByDocIdAndRagTag(String docId, String ragTag) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT content_hash FROM document_hash WHERE doc_id = ? AND rag_tag = ?",
                    String.class, docId, ragTag);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<DocumentHash> findActiveByRagTag(String ragTag) {
        return jdbcTemplate.query(
                "SELECT doc_id, rag_tag, content_hash, chunk_ids, last_modified, created_at, updated_at FROM document_hash WHERE rag_tag = ?",
                ROW_MAPPER, ragTag);
    }

    @Override
    public int updateChunkIds(String docId, String ragTag, String chunkIds) {
        return jdbcTemplate.update(
                "UPDATE document_hash SET chunk_ids = ?, updated_at = NOW() WHERE doc_id = ? AND rag_tag = ?",
                chunkIds, docId, ragTag);
    }

    @Override
    public int delete(String docId, String ragTag) {
        return jdbcTemplate.update(
                "DELETE FROM document_hash WHERE doc_id = ? AND rag_tag = ?",
                docId, ragTag);
    }

    @Override
    public int batchUpsert(List<DocumentHash> documentHashes) {
        int count = 0;
        for (DocumentHash dh : documentHashes) {
            count += upsert(dh);
        }
        return count;
    }
}