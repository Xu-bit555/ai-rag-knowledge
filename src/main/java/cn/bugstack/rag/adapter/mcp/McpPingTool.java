package cn.bugstack.rag.adapter.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP Ping Tool - OneCase 2.0 MCP server 健康检查
 *
 * Spring AI 1.1.x 使用 @Tool 注解 + MethodToolCallbackProvider
 * （没有 @McpTool 注解，由 spring-ai-starter-mcp-server-webmvc 自动扫描注册）
 *
 * 验证: curl -X POST http://localhost:8090/mcp \
 *   -H "Content-Type: application/json" \
 *   -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
 */
@Slf4j
@Component
public class McpPingTool {

    @Tool(name = "onecase_ping",
          description = "OneCase MCP server health check. Returns 'pong' with current server timestamp.")
    public Map<String, Object> ping(
            @ToolParam(description = "Optional message to echo", required = false) String message) {
        log.info("MCP ping called with message: {}", message);
        return Map.of(
                "status", "pong",
                "timestamp", System.currentTimeMillis(),
                "echo", message != null ? message : "",
                "server", "onecase-mcp",
                "version", "2.0.0"
        );
    }

    @Tool(name = "onecase_server_info",
          description = "Return OneCase server version and capabilities. Use to discover server features.")
    public Map<String, Object> serverInfo() {
        return Map.of(
                "name", "onecase-mcp",
                "version", "2.0.0",
                "spring-ai-version", "1.1.8",
                "spring-boot-version", "3.5.16",
                "capabilities", Map.of(
                        "test_case_generation", true,
                        "rag_search", true,
                        "mcp_tools_count", 2,
                        "embedding_model", "BAAI/bge-m3",
                        "llm_model", "MiniMax-M3"
                )
        );
    }
}