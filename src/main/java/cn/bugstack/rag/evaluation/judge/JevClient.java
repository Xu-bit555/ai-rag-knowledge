package cn.bugstack.rag.evaluation.judge;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jev HTTP client（per plan §八）。
 *
 * 直接调用 Vercel AI Gateway `evaluation-model` endpoint，body / response shape
 * 完全镜像 /Users/xc/jev-test/index.mts：
 *
 * Request:
 * {
 *   "model": "typesafe-ai/jev",
 *   "state": { "question": "<query>", "passage": "<candidate text>" },
 *   "questions": { "relevance": {
 *       "type": "choice",
 *       "instructions": "<JevPrompt.INSTRUCTIONS>",
 *       "criteria": { "irrelevant": null, "tangential": null, "useful": null, "directly_answers": null }
 *   }}
 * }
 *
 * Response (extracted fields):
 * {
 *   "answers": { "relevance": { "choice": "...", "probabilities": {...} } },
 *   "providerMetadata": { "typesafe": { "confidence": { "relevance": <double> } } },
 *   "usage": { "inputTokens": <int>, "outputTokens": <int>, "totalTokens": <int> },
 *   "rounding": { "probabilityDecimals": ..., "scoreDecimals": ... }
 * }
 *
 * 安全约束：
 * - API Key 只能从环境变量 AI_GATEWAY_API_KEY 读取；构造函数不接收 key
 * - toString() / 日志路径不暴露 key
 * - 抛出的异常消息不含 key
 */
public final class JevClient {

    public static final String DEFAULT_BASE_URL = "https://ai-gateway.vercel.sh";
    public static final String DEFAULT_MODEL    = "typesafe-ai/jev";

    /**
     * 端点路径（per Vercel AI Gateway docs TypeSafe API）：
     *   POST {baseUrl}/typesafe/v1/systemone
     */
    public static final String SYSTEMONE_PATH  = "/typesafe/v1/systemone";

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public JevClient() {
        this(DEFAULT_BASE_URL, DEFAULT_MODEL, readApiKeyFromEnv());
    }

    public JevClient(String baseUrl, String model, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("AI_GATEWAY_API_KEY env var is required");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.mapper = new ObjectMapper();
    }

    private static String readApiKeyFromEnv() {
        String k = System.getenv("AI_GATEWAY_API_KEY");
        if (k == null) k = System.getenv().get("AI_GATEWAY_API_KEY");
        return k;
    }

    /**
     * 单 candidate 评分调用（per plan §三 B 组）。
     * 自动 retry 429/5xx with exponential backoff。
     *
     * @return JevCallResult，含 choice / probabilities / confidence / tokens
     */
    public JevCallResult evaluate(String query, String candidateText) {
        return parse(getRawResponseWithRetry(query, candidateText));
    }

    /**
     * 返回原始 response body（cache 用）。失败时返回空 map + 记录错误。
     */
    public Map<String, Object> evaluateRaw(String query, String candidateText) {
        return getRawResponseWithRetry(query, candidateText);
    }

    /**
     * 底层 HTTP 调用：含 retry 逻辑。
     */
    private Map<String, Object> getRawResponseWithRetry(String query, String candidateText) {
        Map<String, Object> body = buildRequestBody(query, candidateText);
        Exception lastError = null;
        long delay = 500L;  // 起始延迟 500ms
        int maxRetries = 4;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String json = mapper.writeValueAsString(body);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + SYSTEMONE_PATH))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = resp.statusCode();
                if (code / 100 == 2) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> responseBody = mapper.readValue(resp.body(), Map.class);
                    return responseBody;
                }
                if (code == 429 || code == 408 || code >= 500) {
                    // retry with backoff
                    lastError = new JevClientException("Jev HTTP " + code + ": " + truncate(resp.body(), 200));
                    sleep(delay);
                    delay *= 2;
                    continue;
                }
                throw new JevClientException("Jev HTTP " + code + ": " + truncate(resp.body(), 200));
            } catch (JevClientException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                sleep(delay);
                delay *= 2;
            }
        }
        throw new JevClientException(
                "Jev call failed after " + maxRetries + " retries: " +
                        (lastError == null ? "unknown" : lastError.getMessage()),
                lastError);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    /**
     * 构造 request body（public，方便测试断言 shape）。
     */
    public Map<String, Object> buildRequestBody(String query, String candidateText) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("question", query);
        state.put("passage",  candidateText);

        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "choice");
        question.put("instructions", JevPrompt.INSTRUCTIONS);
        question.put("criteria", JevPrompt.CRITERIA);

        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("relevance", question);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", model);
        root.put("state", state);
        root.put("questions", questions);
        return root;
    }

    @SuppressWarnings("unchecked")
    private JevCallResult parse(Map<String, Object> responseBody) {
        Map<String, Object> answers = (Map<String, Object>) responseBody.get("answers");
        if (answers == null) {
            throw new JevClientException("Response missing 'answers' field");
        }
        Map<String, Object> relevance = (Map<String, Object>) answers.get("relevance");
        if (relevance == null) {
            throw new JevClientException("Response missing 'answers.relevance' field");
        }
        String choice = (String) relevance.get("choice");
        Map<String, Double> probs = (Map<String, Double>) relevance.get("probabilities");
        if (choice == null) throw new JevClientException("Missing 'choice'");
        if (probs  == null) throw new JevClientException("Missing 'probabilities'");

        double confidence = 0.0;
        // TypeSafe direct HTTP API: provider_metadata (snake_case)
        // AI SDK v7 camelCase wrapper: providerMetadata
        Map<String, Object> providerMetadata = (Map<String, Object>) responseBody.get("providerMetadata");
        if (providerMetadata == null) {
            providerMetadata = (Map<String, Object>) responseBody.get("provider_metadata");
        }
        if (providerMetadata != null) {
            Object typesafe = providerMetadata.get("typesafe");
            if (typesafe instanceof Map) {
                Object conf = ((Map<String, Object>) typesafe).get("confidence");
                if (conf instanceof Map) {
                    Object v = ((Map<String, Object>) conf).get("relevance");
                    if (v instanceof Number) confidence = ((Number) v).doubleValue();
                }
            }
        }

        int inputTokens = 0;
        int outputTokens = 0;
        Object usage = responseBody.get("usage");
        if (usage instanceof Map) {
            // TypeSafe direct HTTP API 返回 snake_case: input_tokens / output_tokens
            // AI SDK v7 包了一层后返回 camelCase: inputTokens / outputTokens
            Object i = ((Map<String, Object>) usage).get("inputTokens");
            if (i == null) i = ((Map<String, Object>) usage).get("input_tokens");
            Object o = ((Map<String, Object>) usage).get("outputTokens");
            if (o == null) o = ((Map<String, Object>) usage).get("output_tokens");
            if (i instanceof Number) inputTokens  = ((Number) i).intValue();
            if (o instanceof Number) outputTokens = ((Number) o).intValue();
        }

        return new JevCallResult(choice, probs, confidence, inputTokens, outputTokens);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "<null>";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public String baseUrl() { return baseUrl; }
    public String model() { return model; }

    public record JevCallResult(
            String choice,
            Map<String, Double> probabilities,
            double confidence,
            int inputTokens,
            int outputTokens
    ) {}

    public static class JevClientException extends RuntimeException {
        public JevClientException(String message) { super(message); }
        public JevClientException(String message, Throwable cause) { super(message, cause); }
    }
}