package com.olo.plugin.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Ollama /api/chat response (non-streaming). Ignores extra fields (model, created_at, done, etc.). */
@JsonIgnoreProperties(ignoreUnknown = true)
final class OllamaChatResponse {

    private Message message;

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Message {
        private String role;
        private String content;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public Message getMessage() { return message; }
    public void setMessage(Message message) { this.message = message; }
}
