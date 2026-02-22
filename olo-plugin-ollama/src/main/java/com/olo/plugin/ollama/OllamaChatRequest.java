package com.olo.plugin.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Ollama /api/chat request body. */
final class OllamaChatRequest {

    private final String model;
    private final List<Message> messages;
    @JsonProperty("stream")
    private final boolean stream;

    OllamaChatRequest(String model, List<Message> messages, boolean stream) {
        this.model = model;
        this.messages = messages;
        this.stream = stream;
    }

    public String getModel() { return model; }
    public List<Message> getMessages() { return messages; }
    public boolean isStream() { return stream; }

    static final class Message {
        private final String role;
        private final String content;

        Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() { return role; }
        public String getContent() { return content; }
    }
}
