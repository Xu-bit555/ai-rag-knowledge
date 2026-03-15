package cn.bugstack.xfg.dev.tech.trigger.http;

import cn.bugstack.xfg.dev.tech.api.IRAGService;
import cn.bugstack.xfg.dev.tech.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/rag/")
public class RAGController {

    @Resource
    private IRAGService ragService;

    @RequestMapping(value = "query_rag_tag_list", method = RequestMethod.GET)
    public Response<List<String>> queryRagTagList() {
        return ragService.queryRagTagList();
    }

    @RequestMapping(value = "file/upload", method = RequestMethod.POST, headers = "content-type=multipart/form-data")
    public Response<String> uploadFile(@RequestParam("ragTag") String ragTag, @RequestParam("file") List<MultipartFile> files) {
        return ragService.uploadFile(ragTag, files);
    }

    @RequestMapping(value = "analyze_git_repository", method = RequestMethod.POST)
    public Response<String> analyzeGitRepository(@RequestParam("repoUrl") String repoUrl, @RequestParam("userName") String userName, @RequestParam("token") String token) throws Exception {
        return ragService.analyzeGitRepository(repoUrl, userName, token);
    }

    @RequestMapping(value = "rerank", method = RequestMethod.POST)
    public Response<String> rerank(@RequestParam("content") String content, @RequestParam("ragTag") String ragTag) {
        return ragService.rerank(content, ragTag);
    }

    @RequestMapping(value = "generate_cases", method = RequestMethod.POST)
    public Response<String> generateCases(@RequestParam("content") String content, @RequestParam("ragTag") String ragTag) {
        return ragService.generateCases(content, ragTag);
    }

    @RequestMapping(value = "generate_cases_stream", method = RequestMethod.GET, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> generateCasesStream(@RequestParam("content") String content, @RequestParam("ragTag") String ragTag) {
        return ragService.generateCasesStream(content, ragTag);
    }

}
