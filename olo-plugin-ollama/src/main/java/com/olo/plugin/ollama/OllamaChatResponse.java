package com.olo.plugin.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Ollama /api/chat response (non-streaming). Ignores extra fields. */
@JsonIgnoreProperties(ignoreUnknown = true)
final class OllamaChatResponse {

    private String model;
    private Message message;
    private Long prompt_eval_count;
    private Long eval_count;

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Message {
        private String role;
        private String content;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Message getMessage() { return message; }
    public void setMessage(Message message) { this.message = message; }
    public Long getPrompt_eval_count() { return prompt_eval_count; }
    public void setPrompt_eval_count(Long prompt_eval_count) { this.prompt_eval_count = prompt_eval_count; }
    public Long getEval_count() { return eval_count; }
    public void setEval_count(Long eval_count) { this.eval_count = eval_count; }
}
