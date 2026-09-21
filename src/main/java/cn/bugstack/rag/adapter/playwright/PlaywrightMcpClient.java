package cn.bugstack.rag.adapter.playwright;

import cn.bugstack.rag.core.port.PlaywrightMcpPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PlaywrightMcpClient - HTTP JSON-RPC 2.0 client for Playwright MCP
 *
 * 协议:
 * POST {endpoint} (e.g. http://127.0.0.1:8931/mcp)
 * Content-Type: application/json
 *
 * Body:
 * {
 *   "jsonrpc": "2.0",
 *   "id": 1,
 *   "method": "tools/call",
 *   "params": {"name": "browser_navigate", "arguments": {...}}
 * }
 *
 * Response:
 * {
 *   "jsonrpc": "2.0", "id": 1,
 *   "result": {"content": [{"type": "text", "text": "..."}]}
 * }
 */
@Slf4j
@Component
public class PlaywrightMcpClient implements PlaywrightMcpPort {

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String endpoint;
    private final Map<String, Object> serverInfo = new HashMap<>();
    private volatile Map<String, Object> lastSnapshot = new HashMap<>();
    private final AtomicInteger requestId = new AtomicInteger(1);

    public PlaywrightMcpClient(
            ObjectMapper objectMapper,
            @Value("${onecase.playwright-mcp.endpoint:http://127.0.0.1:8931/mcp}") String endpoint) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.restClient = RestClient.builder()
                .baseUrl(endpoint)
                .build();
    }

    @Override
    public Map<String, Object> initialize() {
        log.info("PlaywrightMcpClient.initialize: endpoint={}", endpoint);
        try {
            Map<String, Object> body = Map.of(
                    "jsonrpc", "2.0",
                    "id", requestId.getAndIncrement(),
                    "method", "initialize",
                    "params", Map.of(
                            "protocolVersion", "2024-11-05",
                            "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "onecase", "version", "2.0.0")
                    )
            );
            String response = sendRaw(body);
            JsonNode node = objectMapper.readTree(response);
            JsonNode result = node.get("result");
            if (result != null) {
                serverInfo.putAll(objectMapper.convertValue(result, Map.class));
            }
            log.info("Playwright MCP server info: {}", serverInfo);
            connected = true;
            return serverInfo;
        } catch (Exception e) {
            log.error("initialize failed: {}", e.getMessage());
            connected = false;
            return Map.of("error", e.getMessage());
        }
    }

    private volatile boolean connected = false;

    @Override
    public List<String> listTools() {
        try {
            Map<String, Object> body = Map.of(
                    "jsonrpc", "2.0",
                    "id", requestId.getAndIncrement(),
                    "method", "tools/list",
                    "params", Map.of()
            );
            String response = sendRaw(body);
            JsonNode node = objectMapper.readTree(response);
            JsonNode tools = node.path("result").path("tools");
            List<String> names = new ArrayList<>();
            if (tools.isArray()) {
                for (JsonNode t : tools) {
                    names.add(t.path("name").asText());
                }
            }
            log.info("Playwright MCP tools ({}): {}", names.size(), names);
            return names;
        } catch (Exception e) {
            log.error("listTools failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        try {
            Map<String, Object> body = Map.of(
                    "jsonrpc", "2.0",
                    "id", requestId.getAndIncrement(),
                    "method", "tools/call",
                    "params", Map.of(
                            "name", toolName,
                            "arguments", arguments == null ? Map.of() : arguments
                    )
            );
            log.debug("MCP call: tool={}, args={}", toolName, arguments);
            String response = sendRaw(body);

            // 缓存 snapshot
            if ("browser_snapshot".equals(toolName)) {
                try {
                    JsonNode node = objectMapper.readTree(response);
                    String text = node.path("result").path("content").path(0).path("text").asText("{}");
                    JsonNode snapNode = objectMapper.readTree(text);
                    lastSnapshot = objectMapper.convertValue(snapNode, Map.class);
                } catch (Exception ignore) {}
            }

            // 提取返回文本
            JsonNode node = objectMapper.readTree(response);
            if (node.has("error")) {
                log.warn("MCP error response: {}", node.path("error"));
                return "[ERROR] " + node.path("error");
            }
            JsonNode result = node.path("result");
            if (result.has("content") && result.path("content").isArray() && result.path("content").size() > 0) {
                return result.path("content").path(0).path("text").asText("");
            }
            return result.toString();
        } catch (Exception e) {
            log.error("callTool {} failed: {}", toolName, e.getMessage());
            return "[ERROR] " + e.getMessage();
        }
    }

    @Override
    public Map<String, Object> getLastSnapshot() {
        return lastSnapshot;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    private String sendRaw(Map<String, Object> body) throws Exception {
        return restClient.post()
                .header("Content-Type", "application/json")
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String.class);
    }
}