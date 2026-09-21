package cn.bugstack.rag.controller;

import cn.bugstack.rag.model.response.Response;
import cn.bugstack.rag.service.ThinkStreamFilter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

/**
 * AI 对话控制器 - MiniMax 接口
 *
 * Spring AI 1.1.x: ChatModel (不是 ChatClient)
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/")
public class AiController {

    @Resource
    private ChatModel chatModel;

    @GetMapping("chat/generate")
    public Response<String> generate(@RequestParam("prompt") String prompt) {
        log.info("对话请求, prompt长度: {}", prompt.length());
        ChatResponse response = chatModel.call(new Prompt(prompt));
        AssistantMessage msg = response.getResult().getOutput();
        return Response.ok(msg != null ? msg.getText() : "");
    }

    @GetMapping(value = "chat/generate_stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseBodyEmitter generateStream(@RequestParam("prompt") String prompt) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(3 * 60 * 1000L);

        try {
            ThinkStreamFilter filter = new cn.bugstack.rag.service.impl.ThinkStreamFilterImpl();

            Flux<String> flux = chatModel.stream(new Prompt(prompt))
                    .map(ChatResponse::getResult)
                    .map(result -> {
                        AssistantMessage msg = result.getOutput();
                        String content = msg != null ? msg.getText() : "";
                        return content;
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