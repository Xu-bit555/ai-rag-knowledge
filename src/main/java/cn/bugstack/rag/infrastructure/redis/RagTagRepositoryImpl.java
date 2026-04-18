package cn.bugstack.rag.infrastructure.redis;

import cn.bugstack.rag.repository.IRagTagRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * RAG标签仓储实现（Redis）
 */
@Slf4j
@Repository
public class RagTagRepositoryImpl implements IRagTagRepository {

    private static final String RAG_TAG_SET_KEY = "ragTag";

    private final RedissonClient redissonClient;

    public RagTagRepositoryImpl(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public List<String> getAllRagTags() {
        try {
            RSet<String> elements = redissonClient.getSet(RAG_TAG_SET_KEY);
            return new java.util.ArrayList<>(elements.readAll());
        } catch (Exception e) {
            log.warn("获取RAG标签失败，返回空列表: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void addRagTag(String tag) {
        RSet<String> elements = redissonClient.getSet(RAG_TAG_SET_KEY);
        if (!elements.contains(tag)) {
            elements.add(tag);
            log.info("添加RAG标签: {}", tag);
        }
    }

    @Override
    public void removeRagTag(String tag) {
        RSet<String> elements = redissonClient.getSet(RAG_TAG_SET_KEY);
        elements.remove(tag);
        log.info("删除RAG标签: {}", tag);
    }

}
