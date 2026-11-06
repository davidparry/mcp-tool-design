package com.davidparry.tooldesign.benchmark;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** A model that plays back scripted replies, 100 input and 10 output tokens each, and keeps every prompt it got. */
public final class ScriptedChatModel implements ChatModel {

    private final Deque<AssistantMessage> replies = new ArrayDeque<>();

    private final List<Prompt> prompts = new ArrayList<>();

    public ScriptedChatModel reply(String text) {
        this.replies.add(AssistantMessage.builder().content(text).build());
        return this;
    }

    public ScriptedChatModel toolCall(String tool, String arguments) {
        return toolCalls(new String[] { tool, arguments });
    }

    /** One reply carrying several tool calls, each given as {name, arguments}. */
    public ScriptedChatModel toolCalls(String[]... calls) {
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (String[] call : calls) {
            toolCalls.add(new AssistantMessage.ToolCall("call-" + (this.replies.size() + 1) + "-" + toolCalls.size(),
                    "function", call[0], call[1]));
        }
        this.replies.add(AssistantMessage.builder().content("").toolCalls(toolCalls).build());
        return this;
    }

    public List<Prompt> prompts() {
        return this.prompts;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        this.prompts.add(prompt);
        AssistantMessage reply = this.replies.isEmpty() ? AssistantMessage.builder().content("out of script").build()
                : this.replies.poll();
        return new ChatResponse(List.of(new Generation(reply)),
                ChatResponseMetadata.builder().model("scripted").usage(new DefaultUsage(100, 10)).build());
    }
}
