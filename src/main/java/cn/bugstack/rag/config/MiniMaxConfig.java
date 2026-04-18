package cn.bugstack.rag.config;

import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * MiniMax API配置（兼容OpenAI接口）
 */
@Configuration
public class MiniMaxConfig {

    @Autowired
    private MiniMaxConfigProperties properties;

    @Bean
    @Primary
    public OpenAiApi miniMaxApi() {
        return new OpenAiApi(properties.getBaseUrl(), properties.getApiKey());
    }

}
