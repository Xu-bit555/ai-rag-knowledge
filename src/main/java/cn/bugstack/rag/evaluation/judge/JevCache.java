package cn.bugstack.rag.evaluation.judge;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Jev response FS cache（per plan §十）。
 *
 * Cache Key 组成：
 *   SHA256(query || "|" || candidateId || "|" || jevModel || "|" || jevPromptVersion)
 *
 * 任何一项变化（query、candidateId、model、prompt_version）都产生 cache miss。
 *
 * 安全约束：
 * - 缓存 value **绝不**包含 API Key
 * - 缓存 value 只包含 Jev response body（即 answer + probabilities）
 * - log 不打 cache hit/miss 时也不打 key 内容
 */
public final class JevCache {

    private final Path cacheDir;
    private final ObjectMapper mapper;
    private int hits = 0;
    private int misses = 0;

    public JevCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        this.mapper = new ObjectMapper();
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create cache dir: " + cacheDir, e);
        }
    }

    public Path cacheDir() { return cacheDir; }

    public synchronized Optional<Map<String, Object>> get(String query, String candidateId, String jevModel) {
        Path file = fileFor(query, candidateId, jevModel);
        if (!Files.exists(file)) {
            misses++;
            return Optional.empty();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(file.toFile(), Map.class);
            hits++;
            return Optional.of(body);
        } catch (IOException e) {
            // corrupted cache → treat as miss
            misses++;
            return Optional.empty();
        }
    }

    public synchronized void put(String query, String candidateId, String jevModel, Map<String, Object> body) {
        Path file = fileFor(query, candidateId, jevModel);
        try {
            Files.writeString(file, mapper.writeValueAsString(body), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write cache file: " + file, e);
        }
    }

    public int hits() { return hits; }
    public int misses() { return misses; }
    public double hitRate() {
        int total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    /**
     * 文件名 = SHA256(query|candidateId|model|prompt_version).json
     */
    Path fileFor(String query, String candidateId, String jevModel) {
        String key = sha256(query + "|" + candidateId + "|" + jevModel + "|" + JevPrompt.VERSION);
        return cacheDir.resolve(key + ".json");
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}