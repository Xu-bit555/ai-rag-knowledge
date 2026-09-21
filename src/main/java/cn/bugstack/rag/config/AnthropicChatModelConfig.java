package cn.bugstack.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Anthropic (Claude) ChatModel 配置 — 1.1.x
 *
 * 用途: 让 OneCase 通过 Anthropic SDK 调 api.minimaxi.com/anthropic,
 *       复用 Claude Code 客户端的 ANTHROPIC_AUTH_TOKEN.
 *
 * 与 MiniMaxConfig 互斥: 这里不再 @Primary, 但 MiniMaxConfig 已被删除,
 * 所以这是唯一的 ChatModel bean.
 *
 * 注意: Anthropic 协议里 completionsPath = /v1/messages, 不是 /v1/chat/completions
 *
 * 必须环境变量 (由 .env 或 system env 提供):
 *   ANTHROPIC_BASE_URL  默认 https://api.minimaxi.com/anthropic
 *   ANTHROPIC_AUTH_TOKEN 必填 (skill 期望在 ~/.claude/settings.json 里)
 *   ANTHROPIC_MODEL     默认 MiniMax-M3[1m] (MiniMax 转发 Anthropic 协议的默认模型)
 */
@Slf4j
@Configuration
public class AnthropicChatModelConfig {

    @Value("${anthropic.base-url:https://api.minimaxi.com/anthropic}")
    private String baseUrl;

    // 兼容三种命名: anthropic.api-key (yml) / ANTHROPIC_API_KEY (env, Spring relaxed binding)
    // / ANTHROPIC_AUTH_TOKEN (Claude Code 客户端的标准变量名)
    @Value("${anthropic.auth-token:${ANTHROPIC_AUTH_TOKEN:${anthropic.api-key:${ANTHROPIC_API_KEY:}}}}")
    private String apiKey;

    @Value("${anthropic.model:MiniMax-M3}")
    private String model;

    /**
     * Anthropic API client (low-level) — baseUrl 是 SDK 内部的"根",
     * completionsPath 是协议规定的 endpoint (Anthropic = /v1/messages).
     * SDK 会拼接 baseUrl + completionsPath → 最终 URL.
     * 例: https://api.minimaxi.com/anthropic + /v1/messages
     *   = https://api.minimaxi.com/anthropic/v1/messages  (MiniMax 转发)
     */
    @Bean
    public AnthropicApi anthropicApi() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[ANTHROPIC] api-key 未配置, generate_cases / diagnose_failure 会失败. " +
                     "请设置 ANTHROPIC_AUTH_TOKEN 或 anthropic.api-key");
        } else {
            log.info("[ANTHROPIC] apiKey 长度: {}, 前缀: {}", apiKey.length(),
                    apiKey.length() >= 7 ? apiKey.substring(0, 7) + "..." : "(too short)");
        }
        log.info("[ANTHROPIC] baseUrl={}, completionsPath=/v1/messages", baseUrl);
        return AnthropicApi.builder()
                .baseUrl(baseUrl)
                .completionsPath("/v1/messages")
                .apiKey(apiKey)
                .build();
    }

    /**
     * ChatModel bean — 唯一 (MiniMaxConfig 已删除).
     */
    @Bean
    public ChatModel chatModel(AnthropicApi anthropicApi) {
        log.info("[ANTHROPIC] 初始化 ChatModel, model={}", model);
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(4096)
                .build();
        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(options)
                .build();
    }
}