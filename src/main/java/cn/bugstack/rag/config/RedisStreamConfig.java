package cn.bugstack.rag.config;

import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.client.RedisBusyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * Redis Stream配置
 */
@Slf4j
@Configuration
public class RedisStreamConfig {

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RedisStreamConfigProperties properties;

    @PostConstruct
    public void init() {
        try {
            var stream = redissonClient.getStream(properties.getStreamKey());
            stream.createGroup(StreamCreateGroupArgs.name(properties.getGroup()).makeStream());
            log.info("Redis Stream group创建成功: {}", properties.getGroup());
        } catch (RedisBusyException e) {
            log.info("Redis Stream group已存在: {}", properties.getGroup());
        } catch (Exception e) {
            log.warn("Redis Stream初始化失败: {}", e.getMessage());
        }
    }

}
