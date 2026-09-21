package cn.bugstack.rag.core.domain.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Artifact {
    private String artifactId;
    private String runId;
    private String caseId;
    private Integer attemptNumber;
    private Integer stepOrder;
    private ArtifactType artifactType;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String storagePath;
    private Instant createdAt;
}