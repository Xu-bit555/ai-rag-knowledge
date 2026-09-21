package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.core.domain.execution.Artifact;
import cn.bugstack.rag.core.domain.execution.ArtifactType;
import cn.bugstack.rag.core.port.ArtifactRepositoryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ArtifactService 单元测试
 *
 * 覆盖:
 * - 上传 PNG/JPG 到 Local FS
 * - 服务端生成 UUID 文件名(无路径穿越)
 * - Content-Type / Size 校验
 * - readBytes 回读一致性
 */
class ArtifactServiceTest {

    private ArtifactService service;
    private InMemoryArtifactRepo repo;
    private Path storageRoot;

    @BeforeEach
    void setUp() throws IOException {
        repo = new InMemoryArtifactRepo();
        storageRoot = Files.createTempDirectory("artifacts-test-");
        service = new ArtifactService(repo);
        ReflectionTestUtils.setField(service, "storageRoot", storageRoot.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(storageRoot)) {
            Files.walk(storageRoot)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    @Test
    @DisplayName("上传 PNG 文件 - 服务端生成 UUID 文件名 + 写入 Local FS")
    void uploadPng() throws IOException {
        byte[] pngContent = makePngBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "screenshot.png", "image/png", pngContent);

        Artifact a = service.upload("run-1", "TC_001", 1, 1, "SCREENSHOT", file);

        assertNotNull(a.getArtifactId());
        assertTrue(a.getArtifactId().startsWith("art-"));
        assertEquals("run-1", a.getRunId());
        assertEquals("TC_001", a.getCaseId());
        assertEquals(1, a.getAttemptNumber());
        assertEquals(ArtifactType.SCREENSHOT, a.getArtifactType());
        assertEquals("image/png", a.getContentType());
        assertEquals(pngContent.length, a.getFileSize());
        assertTrue(Files.exists(Paths.get(a.getStoragePath())));

        // 服务端生成路径: storageRoot/run-1/TC_001/1/screenshot/art-xxxx.png
        assertTrue(a.getStoragePath().contains("/run-1/TC_001/1/screenshot/"));
        // 原始文件名被保留为 metadata
        assertEquals("screenshot.png", a.getFileName());
    }

    @Test
    @DisplayName("readBytes 回读 - 内容一致")
    void readBytesRoundTrip() throws IOException {
        byte[] original = makePngBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.png", "image/png", original);
        Artifact a = service.upload("run-1", "TC_001", 1, 1, "SCREENSHOT", file);

        byte[] readBack = service.readBytes(a);
        assertArrayEquals(original, readBack);
    }

    @Test
    @DisplayName("原始文件名包含 ../ - 不参与 storagePath(防路径穿越)")
    void pathTraversalBlocked() throws IOException {
        byte[] pngContent = makePngBytes();
        // 模拟恶意客户端
        MockMultipartFile evilFile = new MockMultipartFile(
                "file", "../../../etc/passwd.png", "image/png", pngContent);

        Artifact a = service.upload("run-1", "TC_001", 1, 1, "SCREENSHOT", evilFile);

        // storagePath 必须落在 storageRoot 下
        Path resolved = Paths.get(a.getStoragePath()).toAbsolutePath().normalize();
        assertTrue(resolved.startsWith(storageRoot.toAbsolutePath().normalize()),
                () -> "Storage path escapes storageRoot: " + resolved);

        // 原始恶意 filename 仅作为 metadata,不参与路径
        assertEquals("../../../etc/passwd.png", a.getFileName());
    }

    @Test
    @DisplayName("拒绝超过 50MB 文件")
    void rejectTooLarge() {
        byte[] huge = new byte[51 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.png", "image/png", huge);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload("run-1", "TC_001", 1, 1, "SCREENSHOT", file));
        assertTrue(ex.getMessage().contains("File too large"));
    }

    @Test
    @DisplayName("拒绝不允许的 Content-Type")
    void rejectDisallowedContentType() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.exe", "application/x-msdownload", new byte[]{1, 2, 3});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload("run-1", "TC_001", 1, 1, "SCREENSHOT", file));
        assertTrue(ex.getMessage().contains("Content-Type not allowed"));
    }

    @Test
    @DisplayName("artifactType 大小写不敏感(默认 OTHER)")
    void artifactTypeCaseInsensitive() throws IOException {
        byte[] png = makePngBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.png", "image/png", png);

        Artifact a = service.upload("run-1", "TC_001", 1, 1, "screenshot", file);
        assertEquals(ArtifactType.SCREENSHOT, a.getArtifactType());

        Artifact a2 = service.upload("run-1", "TC_001", 2, 1, "TRACE", file);
        assertEquals(ArtifactType.TRACE, a2.getArtifactType());

        Artifact a3 = service.upload("run-1", "TC_001", 3, 1, "unknown_type", file);
        assertEquals(ArtifactType.OTHER, a3.getArtifactType());
    }

    private byte[] makePngBytes() {
        // 最小有效 PNG: 1x1 透明像素
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0D, (byte) 0x49, (byte) 0x48, (byte) 0x44, (byte) 0x52,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,
                (byte) 0x08, (byte) 0x06, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x1F, (byte) 0x15, (byte) 0xC4,
                (byte) 0x89, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0D, (byte) 0x49, (byte) 0x44, (byte) 0x41,
                (byte) 0x54, (byte) 0x78, (byte) 0x9C, (byte) 0x62, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00,
                (byte) 0x05, (byte) 0x00, (byte) 0x01, (byte) 0x0D, (byte) 0x0A, (byte) 0x2D, (byte) 0xB4, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x49, (byte) 0x45, (byte) 0x4E, (byte) 0x44, (byte) 0xAE,
                (byte) 0x42, (byte) 0x60, (byte) 0x82
        };
    }

    /** In-memory artifact repo */
    static class InMemoryArtifactRepo implements ArtifactRepositoryPort {

        private final java.util.Map<String, Artifact> store = new java.util.HashMap<>();

        @Override
        public Artifact save(Artifact artifact) {
            store.put(artifact.getArtifactId(), artifact);
            return artifact;
        }

        @Override
        public Optional<Artifact> findByArtifactId(String artifactId) {
            return Optional.ofNullable(store.get(artifactId));
        }

        @Override
        public List<Artifact> findByRunId(String runId) {
            return store.values().stream()
                    .filter(a -> runId.equals(a.getRunId()))
                    .toList();
        }

        @Override
        public List<Artifact> findByAttempt(String runId, String caseId, int attemptNumber) {
            return store.values().stream()
                    .filter(a -> runId.equals(a.getRunId()))
                    .filter(a -> caseId.equals(a.getCaseId()))
                    .filter(a -> a.getAttemptNumber() != null && a.getAttemptNumber() == attemptNumber)
                    .toList();
        }
    }
}