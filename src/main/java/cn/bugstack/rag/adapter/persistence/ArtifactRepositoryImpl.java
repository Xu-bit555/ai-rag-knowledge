package cn.bugstack.rag.adapter.persistence;

import cn.bugstack.rag.core.domain.execution.Artifact;
import cn.bugstack.rag.core.domain.execution.ArtifactType;
import cn.bugstack.rag.core.port.ArtifactRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ArtifactRepositoryImpl implements ArtifactRepositoryPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Artifact save(Artifact artifact) {
        jdbcTemplate.update(
                "INSERT INTO rag_artifact_meta " +
                        "(artifact_id, run_id, case_id, attempt_number, step_order, " +
                        " artifact_type, file_name, content_type, file_size, storage_path, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                artifact.getArtifactId(),
                artifact.getRunId(),
                artifact.getCaseId(),
                artifact.getAttemptNumber(),
                artifact.getStepOrder(),
                artifact.getArtifactType() != null ? artifact.getArtifactType().name() : null,
                artifact.getFileName(),
                artifact.getContentType(),
                artifact.getFileSize(),
                artifact.getStoragePath(),
                Timestamp.from(artifact.getCreatedAt() != null ? artifact.getCreatedAt() : Instant.now())
        );
        return artifact;
    }

    @Override
    public Optional<Artifact> findByArtifactId(String artifactId) {
        List<Artifact> results = jdbcTemplate.query(
                "SELECT * FROM rag_artifact_meta WHERE artifact_id = ?",
                (rs, rowNum) -> mapArtifact(rs), artifactId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<Artifact> findByRunId(String runId) {
        return jdbcTemplate.query(
                "SELECT * FROM rag_artifact_meta WHERE run_id = ? ORDER BY created_at DESC",
                (rs, rowNum) -> mapArtifact(rs), runId);
    }

    @Override
    public List<Artifact> findByAttempt(String runId, String caseId, int attemptNumber) {
        return jdbcTemplate.query(
                "SELECT * FROM rag_artifact_meta WHERE run_id = ? AND case_id = ? AND attempt_number = ? " +
                        "ORDER BY created_at DESC",
                (rs, rowNum) -> mapArtifact(rs), runId, caseId, attemptNumber);
    }

    private Artifact mapArtifact(ResultSet rs) throws SQLException {
        return Artifact.builder()
                .artifactId(rs.getString("artifact_id"))
                .runId(rs.getString("run_id"))
                .caseId(rs.getString("case_id"))
                .attemptNumber(rs.getInt("attempt_number") == 0 ? null : rs.getInt("attempt_number"))
                .stepOrder(rs.getInt("step_order") == 0 ? null : rs.getInt("step_order"))
                .artifactType(rs.getString("artifact_type") != null
                        ? ArtifactType.valueOf(rs.getString("artifact_type")) : null)
                .fileName(rs.getString("file_name"))
                .contentType(rs.getString("content_type"))
                .fileSize(rs.getLong("file_size"))
                .storagePath(rs.getString("storage_path"))
                .build();
    }
}