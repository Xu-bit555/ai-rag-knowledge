package cn.bugstack.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置
 *
 * REDIS_URL 环境变量配置示例:
 *   redis://host:port
 *   redis://:password@host:port
 *   rediss://host:port (SSL)
 */
@Slf4j
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${REDIS_URL:redis://localhost:6379}") String redisUrl) {
        log.info("初始化 Redisson 客户端, URL: {}", sanitizeUrl(redisUrl));
        Config config = new Config();
        config.useSingleServer()
                .setAddress(redisUrl)
                .setConnectionPoolSize(10)
                .setConnectionMinimumIdleSize(5);
        return Redisson.create(config);
    }

    private String sanitizeUrl(String url) {
        if (url == null) return "null";
        int idx = url.indexOf('@');
        if (idx > 0) {
            return url.substring(0, url.indexOf("://") + 3) + "***" + url.substring(idx);
        }
        return url;
    }

}