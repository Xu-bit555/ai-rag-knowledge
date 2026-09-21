package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.core.domain.execution.Artifact;
import cn.bugstack.rag.core.domain.execution.ArtifactType;
import cn.bugstack.rag.core.port.ArtifactRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Artifact Service
 *
 * 职责:
 * 1. 校验上传文件 (type / size / extension)
 * 2. 服务端生成 UUID 文件名(避免路径穿越)
 * 3. 写入 Local FS
 * 4. 持久化 metadata 到 rag_artifact_meta
 *
 * 安全: 原始 filename 仅作为 metadata,不参与 storagePath
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "application/json", "text/plain"
    );

    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;  // 50 MB

    private final ArtifactRepositoryPort artifactRepository;

    @Value("${onecase.artifact.storage-dir:/onecase-artifacts}")
    private String storageRoot;

    /**
     * 上传 Artifact
     */
    public Artifact upload(String runId, String caseId, Integer attemptNumber,
                           Integer stepOrder, String artifactTypeStr,
                           MultipartFile file) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required and must not be empty");
        }
        if (runId == null || runId.isBlank() || caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("runId and caseId are required");
        }
        ArtifactType artifactType = parseType(artifactTypeStr);

        // 1. 校验
        validate(file);

        // 2. 服务端生成 artifactId + 路径
        String artifactId = "art-" + UUID.randomUUID().toString().substring(0, 12);
        String ext = extensionFromContentType(file.getContentType());
        String fileName = artifactId + ext;
        Path targetDir = Paths.get(storageRoot, runId, caseId,
                attemptNumber != null ? String.valueOf(attemptNumber) : "0",
                artifactType.name().toLowerCase());
        Files.createDirectories(targetDir);
        Path targetFile = targetDir.resolve(fileName);

        // 3. 写文件
        try (var is = file.getInputStream()) {
            Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }

        // 4. 持久化 metadata
        Artifact artifact = Artifact.builder()
                .artifactId(artifactId)
                .runId(runId)
                .caseId(caseId)
                .attemptNumber(attemptNumber)
                .stepOrder(stepOrder)
                .artifactType(artifactType)
                .fileName(file.getOriginalFilename())
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .storagePath(targetFile.toAbsolutePath().toString())
                .createdAt(Instant.now())
                .build();
        log.info("Saved artifact: id={}, run={}, case={}, attempt={}, size={}B",
                artifactId, runId, caseId, attemptNumber, file.getSize());
        return artifactRepository.save(artifact);
    }

    /**
     * 读取 Artifact 文件字节(供 GET API)
     */
    public byte[] readBytes(Artifact artifact) throws IOException {
        if (artifact == null) {
            throw new IllegalArgumentException("Artifact not found");
        }
        return Files.readAllBytes(Paths.get(artifact.getStoragePath()));
    }

    private void validate(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "File too large: " + file.getSize() + " bytes (max " + MAX_FILE_SIZE + ")");
        }
        String ct = file.getContentType();
        if (ct == null || !ALLOWED_CONTENT_TYPES.contains(ct)) {
            throw new IllegalArgumentException(
                    "Content-Type not allowed: " + ct + " (allowed: " + ALLOWED_CONTENT_TYPES + ")");
        }
    }

    private ArtifactType parseType(String s) {
        if (s == null || s.isBlank()) return ArtifactType.OTHER;
        try {
            return ArtifactType.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return ArtifactType.OTHER;
        }
    }

    private String extensionFromContentType(String ct) {
        if (ct == null) return "";
        return switch (ct) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "application/json" -> ".json";
            case "text/plain" -> ".txt";
            default -> "";
        };
    }
}