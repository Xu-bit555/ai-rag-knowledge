package cn.bugstack.xfg.dev.tech.trigger.http;


import cn.bugstack.xfg.dev.tech.api.IRAGService;
import cn.bugstack.xfg.dev.tech.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.ai.document.Document;


import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/rag/")
public class RAGController implements IRAGService {
    @Resource
    private TokenTextSplitter tokenTextSplitter; // 文本分割器
    @Resource
    private PgVectorStore pgVectorStore;         // 向量存储和检索
    @Resource
    private RedissonClient redissonClient;       // Redis客户端

    // 查询Redis中存储的RAG标签列表
    @RequestMapping(value = "query_rag_tag_list", method = RequestMethod.GET)
    @Override
    public Response<List<String>> queryRagTagList() {
        RList<String> elements = redissonClient.getList("ragTag");
        return Response.<List<String>>builder()
                .code("0000")
                .info("调用成功")
                .data(new ArrayList<>(elements))
                .build();
    }

    // 上传多个文件、解析、分块、添加标签、存储向量、添加标签
    @RequestMapping(value = "file/upload", method = RequestMethod.POST, headers = "content-type=multipart/form-data")
    @Override
    public Response<String> uploadFile(@RequestParam String ragTag, @RequestParam("file") List<MultipartFile> files) {
        log.info("上传知识库开始 {}", ragTag);
        for (MultipartFile file : files) {
            // 1. Tika读取文件解析成Document对象列表
            TikaDocumentReader documentReader = new TikaDocumentReader(file.getResource());
            // 2. 将完整文档分割成更小的文档块
            List<Document> documents = documentReader.get();
            List<Document> documentSplitterList = tokenTextSplitter.apply(documents);

            // 3. 为原始的Document对象添加RAG标签
            documents.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));
            // 分割后的Document对象添加RAG标签
            documentSplitterList.forEach(doc -> doc.getMetadata().put("knowledge", ragTag));

            // 4，将分割后的Document对象列表转换成向量并存储到PgVectorStore中
            pgVectorStore.accept(documentSplitterList);

            // 5. 更新 Redis 中的知识库标签记录。
            RList<String> elements = redissonClient.getList("ragTag");
            if (!elements.contains(ragTag)) {
                elements.add(ragTag);
            }
        }

        log.info("上传知识库完成 {}", ragTag);
        return Response.<String>builder().code("0000").info("调用成功").build();
    }

}
