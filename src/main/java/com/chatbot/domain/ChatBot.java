package com.chatbot.domain;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.chatbot.event.WebSocketEventSourceListener;
import com.unfbx.chatgpt.OpenAiStreamClient;
import com.unfbx.chatgpt.entity.chat.ChatCompletion;
import com.unfbx.chatgpt.entity.chat.Message;
import com.unfbx.chatgpt.entity.completions.Completion;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.smallrye.mutiny.Uni;
import okhttp3.sse.EventSourceListener;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;

import static com.chatbot.common.constants.RedisKey.CHAT_HISTORY_PREFIX;

@ApplicationScoped
public class ChatBot {

    public static final String HTTPS_API_OPENAI_COM = "https://api.openai.com/";

    @ConfigProperty(name = "proxy.enable", defaultValue = "false")
    Boolean enableProxy;

    @ConfigProperty(name = "proxy.host", defaultValue = "localhost")
    String proxyHost;
    @ConfigProperty(name = "proxy.port", defaultValue = "7654")
    Integer proxyPort;

    @Inject
    ReactiveRedisDataSource redisReactive;

    private static void initMessage(List<Message> messages, String history) {
        if (StrUtil.isBlank(history)) {
            messages.add(Message.builder().role(Message.Role.SYSTEM).content("你是一个可以问答任何问题的全能机器人").build());
        } else {
            messages.addAll(JSONUtil.toList(history, Message.class));
        }
    }

    /**
     * 收到响应之后回调, 保存响应到redis
     *
     * @param eventSourceListener
     * @param messages
     * @param redisKey
     */
    private void saveResponse(WebSocketEventSourceListener eventSourceListener,
                              List<Message> messages, String redisKey) {
        eventSourceListener.saveResponse(response -> {
            messages.add(Message.builder().role(Message.Role.ASSISTANT).content(response).build());
            while (messages.size() > 10) {
                ((LinkedList<Message>) messages).removeFirst();
            }
            redisReactive.value(String.class).set(redisKey, JSONUtil.toJsonStr(messages))
                    .call(() -> redisReactive.key().expire(redisKey, Duration.ofHours(12)))
                    .subscribe().asCompletionStage();
        });
    }

    public Uni<Void> chat(String apiKey, String openId, String prompt, EventSourceListener eventSourceListener) {
        List<Message> messages = ListUtil.list(true);
        String redisKey = CHAT_HISTORY_PREFIX + openId;
        return redisReactive.value(String.class).get(redisKey)
                .invoke(history -> {
                    //初始化 消息列表
                    initMessage(messages, history);
                    //添加当前消息
                    messages.add(Message.builder().role(Message.Role.USER).content(prompt).build());
                    //设置响应回调
                    saveResponse((WebSocketEventSourceListener) eventSourceListener, messages, redisKey);
                    //发送请求
                    getClient(apiKey).streamChatCompletion(ChatCompletion.builder().messages(messages).build(),
                            eventSourceListener);
                }).replaceWithVoid();
    }

    public void completions(String apiKey, String prompt, EventSourceListener eventSourceListener) {
        getClient(apiKey).streamCompletions(Completion.builder()
                .prompt(prompt)
                .stream(true)
                .build(), eventSourceListener);
    }


    private OpenAiStreamClient getClient(String apikey) {
        OpenAiStreamClient.Builder builder = OpenAiStreamClient.builder()
                .connectTimeout(10)
                .readTimeout(10)
                .writeTimeout(10)
                .apiKey(apikey)
                .apiHost(HTTPS_API_OPENAI_COM);
        if (enableProxy) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            builder.proxy(proxy);
        }
        return builder.build();
    }
}
