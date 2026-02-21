package com.bloxbean.cardano.julc.analysis.ai;

import com.bloxbean.cardano.julc.analysis.Finding;
import com.bloxbean.cardano.julc.decompiler.input.ScriptAnalyzer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;

/**
 * AI analyzer backed by LangChain4j {@link ChatModel}.
 * <p>
 * Works with any LangChain4j provider (Ollama, OpenAI, Anthropic, etc.)
 * — the user supplies the configured ChatModel instance.
 */
public final class LangChainAiAnalyzer implements AiAnalyzer {

    private final ChatModel chatModel;

    public LangChainAiAnalyzer(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public List<Finding> analyze(String javaSource, ScriptAnalyzer.ScriptStats stats) {
        var systemMsg = SystemMessage.from(PromptBuilder.systemPrompt());
        var userMsg = UserMessage.from(PromptBuilder.userPrompt(javaSource, stats));
        var response = chatModel.chat(systemMsg, userMsg);
        return ResponseParser.parse(response.aiMessage().text());
    }
}
