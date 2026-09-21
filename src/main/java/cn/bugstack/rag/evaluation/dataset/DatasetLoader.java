package cn.bugstack.rag.evaluation.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dataset loader：读 evaluation/datasets/v1.jsonl
 *
 * 提供：
 * - loadJsonl(path) → List<EvalQuery>
 * - groundTruthToMap(query) → Map<candidateId, relevanceGrade>（供 metrics 用）
 */
public final class DatasetLoader {

    private final ObjectMapper mapper;

    public DatasetLoader() {
        this.mapper = new ObjectMapper();
    }

    public List<EvalQuery> loadJsonl(Path path) throws IOException {
        List<EvalQuery> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(path)) {
            String line;
            int lineNum = 0;
            while ((line = r.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    EvalQuery q = mapper.readValue(line, EvalQuery.class);
                    out.add(q);
                } catch (Exception e) {
                    throw new IOException("Failed to parse line " + lineNum + ": " + e.getMessage(), e);
                }
            }
        }
        return out;
    }

    /**
     * groundTruth → Map<candidateId, relevance>，用于 metrics 计算。
     */
    public static Map<String, Integer> groundTruthToMap(EvalQuery q) {
        Map<String, Integer> map = new HashMap<>();
        for (GroundTruthLabel l : q.groundTruth()) {
            map.put(l.candidateId(), l.relevance());
        }
        return map;
    }

    public static Map<String, EvalQuery> indexByQueryId(List<EvalQuery> queries) {
        return queries.stream().collect(Collectors.toMap(EvalQuery::queryId, q -> q, (a, b) -> a, java.util.LinkedHashMap::new));
    }
}