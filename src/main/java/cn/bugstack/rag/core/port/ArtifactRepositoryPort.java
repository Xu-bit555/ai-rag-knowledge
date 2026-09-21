package cn.bugstack.rag.core.port;

import cn.bugstack.rag.core.domain.execution.Artifact;

import java.util.List;
import java.util.Optional;

/**
 * Artifact 仓储端口
 *
 * Artifact 二进制本身存 Local FS,Port 只负责 metadata 持久化
 */
public interface ArtifactRepositoryPort {

    Artifact save(Artifact artifact);

    Optional<Artifact> findByArtifactId(String artifactId);

    /**
     * 按 runId 列出所有 Artifact
     */
    List<Artifact> findByRunId(String runId);

    /**
     * 按 (runId, caseId, attemptNumber) 列出
     */
    List<Artifact> findByAttempt(String runId, String caseId, int attemptNumber);
}