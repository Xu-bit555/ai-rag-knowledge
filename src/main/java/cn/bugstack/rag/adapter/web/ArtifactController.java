package cn.bugstack.rag.adapter.web;

import cn.bugstack.rag.core.domain.execution.Artifact;
import cn.bugstack.rag.core.port.ArtifactRepositoryPort;
import cn.bugstack.rag.core.usecase.ArtifactService;
import cn.bugstack.rag.model.response.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Artifact REST API (Multipart)
 *
 * 不暴露为 MCP Tool - MCP 负责测试领域语义调用,multipart 负责二进制 Artifact 传输
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/artifact")
@RequiredArgsConstructor
public class ArtifactController {

    private final ArtifactService artifactService;
    private final ArtifactRepositoryPort artifactRepository;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<Map<String, Object>> upload(
            @RequestParam("runId") String runId,
            @RequestParam("caseId") String caseId,
            @RequestParam(value = "attemptId", required = false) String attemptId,
            @RequestParam(value = "attemptNumber", required = false) Integer attemptNumber,
            @RequestParam(value = "stepOrder", required = false) Integer stepOrder,
            @RequestParam(value = "artifactType", defaultValue = "SCREENSHOT") String artifactType,
            @RequestParam("file") MultipartFile file) throws IOException {

        Integer attemptNum = attemptNumber;
        if (attemptNum == null && attemptId != null) {
            // 可选: 根据 attemptId 查 attemptNumber
            // 当前 MVP 简化 - 跳过
        }

        Artifact artifact = artifactService.upload(runId, caseId, attemptNum, stepOrder,
                artifactType, file);

        Map<String, Object> data = new HashMap<>();
        data.put("artifactId", artifact.getArtifactId());
        data.put("runId", artifact.getRunId());
        data.put("caseId", artifact.getCaseId());
        data.put("attemptNumber", artifact.getAttemptNumber());
        data.put("stepOrder", artifact.getStepOrder());
        data.put("artifactType", artifact.getArtifactType() != null
                ? artifact.getArtifactType().name() : null);
        data.put("fileName", artifact.getFileName());
        data.put("contentType", artifact.getContentType());
        data.put("fileSize", artifact.getFileSize());
        data.put("storagePath", artifact.getStoragePath());
        data.put("createdAt", artifact.getCreatedAt() != null ? artifact.getCreatedAt().toString() : null);

        log.info("Artifact uploaded: id={}, size={}B, type={}",
                artifact.getArtifactId(), artifact.getFileSize(), artifact.getArtifactType());
        return Response.ok(data);
    }

    @GetMapping("/{artifactId}")
    public ResponseEntity<byte[]> download(@PathVariable String artifactId) throws IOException {
        Artifact artifact = artifactRepository.findByArtifactId(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));

        byte[] bytes = artifactService.readBytes(artifact);

        HttpHeaders headers = new HttpHeaders();
        if (artifact.getContentType() != null) {
            headers.setContentType(MediaType.parseMediaType(artifact.getContentType()));
        }
        headers.setContentLength(bytes.length);
        if (artifact.getFileName() != null) {
            headers.setContentDisposition(
                    org.springframework.http.ContentDisposition.attachment()
                            .filename(artifact.getFileName()).build());
        }
        return new ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
    }

    @GetMapping("/{artifactId}/meta")
    public Response<Map<String, Object>> meta(@PathVariable String artifactId) {
        Artifact artifact = artifactRepository.findByArtifactId(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        Map<String, Object> data = new HashMap<>();
        data.put("artifactId", artifact.getArtifactId());
        data.put("runId", artifact.getRunId());
        data.put("caseId", artifact.getCaseId());
        data.put("attemptNumber", artifact.getAttemptNumber());
        data.put("stepOrder", artifact.getStepOrder());
        data.put("artifactType", artifact.getArtifactType() != null
                ? artifact.getArtifactType().name() : null);
        data.put("fileName", artifact.getFileName());
        data.put("contentType", artifact.getContentType());
        data.put("fileSize", artifact.getFileSize());
        data.put("storagePath", artifact.getStoragePath());
        return Response.ok(data);
    }
}