package cn.bugstack.rag.controller;

import cn.bugstack.rag.model.response.Response;
import cn.bugstack.rag.service.ThinkStreamFilter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

/**
 * AI对话控制器 - MiniMax接口
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/")
public class AiController {

    @Resource
    private OpenAiChatClient chatClient;

    /**
     * 对话接口
     */
    @GetMapping("chat/generate")
    public Response<String> generate(@RequestParam("prompt") String prompt) {
        log.info("对话请求, prompt长度: {}", prompt.length());
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel("MiniMax-M2.7")
                .build();
        String response = chatClient.call(new Prompt(prompt, options)).getResult().getOutput().getContent();
        return Response.ok(response);
    }

    /**
     * 流式对话接口
     */
    @GetMapping(value = "chat/generate_stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseBodyEmitter generateStream(@RequestParam("prompt") String prompt) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(3 * 60 * 1000L);

        try {
            ThinkStreamFilter filter = new ThinkStreamFilter();
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .withModel("MiniMax-M2.7")
                    .build();

            Flux<String> flux = chatClient.stream(new Prompt(prompt, options))
                    .map(chatResponse -> {
                        if (chatResponse == null || chatResponse.getResult() == null) {
                            return "";
                        }
                        String content = chatResponse.getResult().getOutput().getContent();
                        return content != null ? content : "";
                    })
                    .map(filter::apply)
                    .filter(s -> s != null && !s.isEmpty())
                    .map(s -> "data:" + s.replace("\n", "\ndata:") + "\n\n");

            flux.subscribe(
                    data -> {
                        try {
                            emitter.send(data);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    },
                    emitter::completeWithError,
                    emitter::complete
            );
        } catch (Exception e) {
            log.error("流式对话失败", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

}
