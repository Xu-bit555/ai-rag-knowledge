package cn.bugstack.rag.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingClient;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * SiliconFlow 兼容的 Embedding 模型
 * 直接使用 RestTemplate 调用 SiliconFlow API，处理响应格式
 */
@Slf4j
public class SiliconFlowEmbeddingModel extends AbstractEmbeddingClient {

    private static final String SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1";
    private static final String SILICONFLOW_API_KEY = "sk-cxckzibaewxmdvxnvkjfuvypfxwgifqdhlbncvgeqfikvesp";
    private static final String DEFAULT_MODEL = "BAAI/bge-m3";

    private final RestTemplate restTemplate;
    private final String model;
    private final int dimensions;

    public SiliconFlowEmbeddingModel() {
        this(SILICONFLOW_BASE_URL, SILICONFLOW_API_KEY, DEFAULT_MODEL);
    }

    public SiliconFlowEmbeddingModel(String baseUrl, String apiKey, String model) {
        this.restTemplate = new RestTemplate();
        this.model = model;
        // SiliconFlow BAAI/bge-m3 模型维度是 1024
        this.dimensions = 1024;
    }

    @Override
    public int dimensions() {
        return this.dimensions;
    }

    @Override
    public List<Double> embed(String text) {
        return embed(List.of(text)).get(0);
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        try {
            log.info("开始 embedding 请求, 模型: {}, 文本数量: {}", model, texts.size());

            String url = SILICONFLOW_BASE_URL + "/embeddings";
            log.info("请求 URL: {}", url);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + SILICONFLOW_API_KEY);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("input", texts);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> rawResponse = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            String rawBody = rawResponse.getBody();
            log.info("原始响应状态: {}, body: {}", rawResponse.getStatusCode(), rawBody);

            Map<String, Object> responseBody = JSON.parseObject(rawBody,
                    new TypeReference<Map<String, Object>>() {});

            if (responseBody == null || !responseBody.containsKey("data")) {
                log.warn("SiliconFlow embedding 响应格式异常: {}", responseBody);
                return createZeroEmbeddings(texts);
            }

            List<Map<String, Object>> dataList = (List<Map<String, Object>>) responseBody.get("data");

            return dataList.stream()
                    .map(item -> {
                        Object embeddingObj = item.get("embedding");
                        if (embeddingObj instanceof List) {
                            return ((List<?>) embeddingObj).stream()
                                    .map(v -> ((Number) v).doubleValue())
                                    .toList();
                        } else {
                            return createZeroVector();
                        }
                    })
                    .toList();

        } catch (Exception e) {
            log.error("Embedding 调用失败: {}, 模型: {}, 文本数量: {}", e.getMessage(), model, texts.size(), e);
            return createZeroEmbeddings(texts);
        }
    }

    private List<List<Double>> createZeroEmbeddings(List<String> texts) {
        return texts.stream().map(t -> createZeroVector()).toList();
    }

    private List<Double> createZeroVector() {
        double[] arr = new double[dimensions];
        return java.util.Arrays.stream(arr).boxed().toList();
    }

    @Override
    public List<Double> embed(Document document) {
        return embed(document.getContent());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<List<Double>> embeddings = embed(request.getInstructions());
        List<Embedding> results = embeddings.stream()
                .map(list -> new Embedding(list, null))
                .toList();
        return new EmbeddingResponse(results);
    }
}
