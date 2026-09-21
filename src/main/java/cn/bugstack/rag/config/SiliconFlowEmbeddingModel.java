package cn.bugstack.rag.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SiliconFlow 兼容的 Embedding 模型
 *
 * Spring AI 1.1.x:
 * - 实现 EmbeddingModel 接口
 * - embed(String) 返回 float[]（不是 List<Double>）
 * - embed(List<String>) 返回 List<float[]>
 * - 不再依赖 AbstractEmbeddingClient
 *
 * P0-12 修复: fail-fast，不再返回全 0 向量污染向量库:
 *   1. API Key 缺失 → 抛 IllegalStateException (原: warn + return createZeroEmbeddings)
 *   2. HTTP 错误 / 响应格式异常 → 抛 IllegalStateException (原: warn + return createZeroEmbeddings)
 *   3. 单条 embedding 全 0 → 抛 IllegalStateException (原: silent fallback)
 */
@Slf4j
@Component
public class SiliconFlowEmbeddingModel implements EmbeddingModel {

    private static final String SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1";
    private static final String DEFAULT_MODEL = "BAAI/bge-m3";
    private static final int DEFAULT_DIMENSIONS = 1024;

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    public SiliconFlowEmbeddingModel(
            @Value("${spring.ai.embedding.bge.base-url:" + SILICONFLOW_BASE_URL + "}") String baseUrl,
            @Value("${spring.ai.embedding.bge.api-key:}") String apiKey,
            @Value("${spring.ai.embedding.bge.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${spring.ai.embedding.dimensions:" + DEFAULT_DIMENSIONS + "}") int dimensions) {
        this.restTemplate = new RestTemplate();
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        log.info("SiliconFlowEmbeddingModel init: baseUrl={}, model={}, dimensions={}, apiKeyPresent={}",
                baseUrl, model, dimensions, apiKey != null && !apiKey.isBlank());
    }

    @Override
    public int dimensions() {
        return this.dimensions;
    }

    @Override
    public float[] embed(String text) {
        List<float[]> result = embed(List.of(text));
        return result.isEmpty() ? new float[dimensions] : result.get(0);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        // P0-12 fix 1: API Key 缺失 → fail-fast，不再返回零向量污染向量库
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "SiliconFlow API Key not configured (SILICONFLOW_API_KEY). " +
                    "Returning zero embeddings would pollute the vector store with garbage. " +
                    "Set the SILICONFLOW_API_KEY environment variable.");
        }

        try {
            log.info("开始 embedding 请求, 模型: {}, 文本数量: {}", model, texts.size());

            String url = SILICONFLOW_BASE_URL + "/embeddings";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("input", texts);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            String rawBody = rawResponse.getBody();
            log.info("原始响应状态: {}, body 长度: {}", rawResponse.getStatusCode(),
                    rawBody != null ? rawBody.length() : 0);

            // P0-12 fix 2: HTTP 错误 → 抛异常
            if (rawResponse.getStatusCode().isError() || rawBody == null) {
                log.error("SiliconFlow embedding HTTP 错误: status={}, body={}",
                        rawResponse.getStatusCode(),
                        rawBody != null ? rawBody.substring(0, Math.min(500, rawBody.length())) : "null");
                throw new IllegalStateException(
                        "SiliconFlow embedding API error: " + rawResponse.getStatusCode());
            }

            Map<String, Object> responseBody = JSON.parseObject(rawBody,
                    new TypeReference<Map<String, Object>>() {});

            // P0-12 fix 2: 响应格式异常 → 抛异常
            if (responseBody == null || !responseBody.containsKey("data")) {
                log.error("SiliconFlow embedding 响应格式异常: {}", responseBody);
                throw new IllegalStateException(
                        "SiliconFlow embedding response format invalid (no 'data' field)");
            }

            List<Map<String, Object>> dataList = (List<Map<String, Object>>) responseBody.get("data");

            List<float[]> result = new ArrayList<>(dataList.size());
            int idx = 0;
            for (Map<String, Object> item : dataList) {
                Object embeddingObj = item.get("embedding");
                if (!(embeddingObj instanceof List<?> list)) {
                    throw new IllegalStateException(
                            "SiliconFlow embedding item missing 'embedding' field, index=" + idx);
                }
                float[] arr = new float[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    arr[i] = ((Number) list.get(i)).floatValue();
                }

                // P0-12 fix 3: 全 0 向量 → 抛异常 (siliconflow 不应返回全 0, 一旦返回意味着数据污染)
                if (isAllZero(arr)) {
                    throw new IllegalStateException(
                            "SiliconFlow returned all-zero embedding at index=" + idx +
                                    " (would corrupt vector store)");
                }
                result.add(arr);
                idx++;
            }
            return result;

        } catch (IllegalStateException ise) {
            throw ise;   // 透传已知业务异常
        } catch (Exception e) {
            log.error("Embedding 调用失败: {}, 模型: {}, 文本数量: {}", e.getMessage(), model, texts.size(), e);
            throw new IllegalStateException(
                    "SiliconFlow embedding call failed: " + e.getMessage(), e);
        }
    }

    private boolean isAllZero(float[] arr) {
        for (float v : arr) {
            if (v != 0f) return false;
        }
        return true;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<float[]> embeddings = embed(instructions);
        List<org.springframework.ai.embedding.Embedding> result = new ArrayList<>(embeddings.size());
        for (int i = 0; i < embeddings.size(); i++) {
            result.add(new org.springframework.ai.embedding.Embedding(embeddings.get(i), i));
        }
        return new EmbeddingResponse(result);
    }
}