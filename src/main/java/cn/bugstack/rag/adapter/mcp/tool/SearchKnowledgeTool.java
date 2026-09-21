package cn.bugstack.rag.adapter.mcp.tool;

import cn.bugstack.rag.adapter.mcp.McpToolConfig;
import cn.bugstack.rag.core.usecase.SearchKnowledgeUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchKnowledgeTool {

    private final SearchKnowledgeUseCase searchKnowledgeUseCase;

    @Tool(name = "onecase_search_knowledge",
            description = "Search OneCase historical knowledge base (PRD / spec / domain KB) via RAG. "
                    + "Returns array of {documentId, content, score, metadata}. Use this BEFORE "
                    + "onecase_generate_cases to inject historical context into the prompt.")
    public Map<String, Object> search(
            @ToolParam(description = "Knowledge base tag", required = true) String ragTag,
            @ToolParam(description = "Search query string", required = true) String query,
            @ToolParam(description = "Top K results, default 5", required = false) Integer topK) {

        log.info("MCP onecase_search_knowledge: ragTag={}, query.length={}", ragTag,
                query == null ? 0 : query.length());
        List<Map<String, Object>> results = searchKnowledgeUseCase.execute(ragTag, query, topK);
        return Map.of(
                "results", results,
                "count", results.size(),
                "ragTag", ragTag,
                "query", query
        );
    }
}