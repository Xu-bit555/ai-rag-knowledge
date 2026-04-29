package cn.bugstack.rag.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://49.232.146.225:16379")
                .setConnectionPoolSize(10)
                .setConnectionMinimumIdleSize(5);
        return Redisson.create(config);
    }

}
