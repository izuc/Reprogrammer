package software.crud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.yaml.snakeyaml.Yaml;

public class API {
    private final ObjectMapper objectMapper;
    private Map<String, Object> settings;

    public API() {
        this.objectMapper = new ObjectMapper();
        this.settings = loadSettings();
    }

    private Map<String, Object> loadSettings() {
        Yaml yaml = new Yaml();
        try (InputStream input = new FileInputStream("settings.yaml")) {
            return yaml.load(input);
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptyMap();
        }
    }

    public String getProperty(String key, String defaultValue) {
        return settings.containsKey(key) ? settings.get(key).toString() : defaultValue;
    }

    public String generateText(String text, List<Message> history, int maxTokens) {
        String service = getProperty("ai_service", "openai");
        AIGeneratorInterface generator;
        switch (service) {
            case "openai":
                generator = new OpenAIService();
                break;
            case "custom":
                generator = new CustomTextGenerationService();
                break;
            case "claude":
                generator = new ClaudeAIService();
                break;
            default:
                throw new IllegalArgumentException("Invalid AI service specified in settings: " + service);
        }
        return generator.generateText(text, history, maxTokens);
    }

    private interface AIGeneratorInterface {
        String generateText(String text, List<Message> history, int maxTokens);
    }

    static class Message {
        private String role;
        private String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }

    private class OpenAIService implements AIGeneratorInterface {
        private final OkHttpClient httpClient;
        private final String apiKey;
        private final String apiUrl;
        private final String model;

        public OpenAIService() {
            this.httpClient = new OkHttpClient.Builder()
                    .readTimeout(500, TimeUnit.SECONDS)
                    .writeTimeout(500, TimeUnit.SECONDS)
                    .connectTimeout(500, TimeUnit.SECONDS)
                    .build();
            this.apiKey = getProperty("openai_api_key", "");
            this.apiUrl = getProperty("openai_api_url", "https://api.openai.com/v1/chat/completions");
            this.model = getProperty("openai_model", "gpt-4-turbo");
        }

        @Override
        public String generateText(String text, List<Message> history, int maxTokens) {
            List<Map<String, String>> formattedHistory = new ArrayList<>();
            for (Message message : history) {
                formattedHistory.add(new HashMap<>() {
                    {
                        put("role", message.getRole());
                        put("content", message.getContent());
                    }
                });
            }
            formattedHistory.add(new HashMap<>() {
                {
                    put("role", "user");
                    put("content", text);
                }
            });

            Map<String, Object> requestBody = new HashMap<>() {
                {
                    put("model", model);
                    put("messages", formattedHistory);
                    put("max_tokens", maxTokens);
                    put("temperature", 0.7);
                }
            };

            try {
                String jsonBody = objectMapper.writeValueAsString(requestBody);
                RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .post(body)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String responseBody = response.body().string();
                    Map<String, Object> responseMap = objectMapper.readValue(responseBody,
                            new TypeReference<Map<String, Object>>() {
                            });
                    List<?> rawChoices = (List<?>) responseMap.get("choices");
                    List<Map<String, Object>> choices = safelyCastListOfMaps(rawChoices);

                    for (Map<String, Object> choice : choices) {
                        Map<String, String> message = objectMapper.convertValue(choice.get("message"),
                                new TypeReference<Map<String, String>>() {
                                });
                        if (message != null && "assistant".equals(message.get("role"))) {
                            return message.get("content");
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            return "Sorry, no response was generated.";
        }
    }

    private class CustomTextGenerationService implements AIGeneratorInterface {
        private final String apiUrl;
        private final String model;
        private final OkHttpClient httpClient;

        public CustomTextGenerationService() {
            this.apiUrl = getProperty("custom_text_generation_api_url", "http://127.0.0.1:5000/v1/chat/completions");
            this.model = getProperty("custom_text_generation_model", "");
            this.httpClient = new OkHttpClient.Builder()
                    .readTimeout(500, TimeUnit.SECONDS)
                    .writeTimeout(500, TimeUnit.SECONDS)
                    .connectTimeout(500, TimeUnit.SECONDS)
                    .build();
        }

        @Override
        public String generateText(String text, List<Message> history, int maxTokens) {
            List<Message> messages = new ArrayList<>(history);
            messages.add(new Message("user", text));

            Map<String, Object> requestBody = new HashMap<>() {
                {
                    put("messages", messages);
                    if (!model.isEmpty()) {
                        put("model", model);
                    }
                }
            };

            try {
                String jsonBody = objectMapper.writeValueAsString(requestBody);
                RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .post(body)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String responseBody = response.body().string();
                    Map<String, Object> responseMap = objectMapper.readValue(responseBody,
                            new TypeReference<Map<String, Object>>() {
                            });
                    List<?> rawChoices = (List<?>) responseMap.get("choices");
                    List<Map<String, Object>> choices = safelyCastListOfMaps(rawChoices);

                    if (!choices.isEmpty()) {
                        Map<String, Object> firstChoice = choices.get(0);
                        Map<String, String> message = objectMapper.convertValue(firstChoice.get("message"),
                                new TypeReference<Map<String, String>>() {
                                });
                        if (message != null) {
                            String responseContent = message.get("content");
                            messages.add(new Message("assistant", responseContent));
                            return responseContent;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            return "Sorry, I have no answer.";
        }
    }

    private class ClaudeAIService implements AIGeneratorInterface {
        private final String apiUrl;
        private final String apiKey;
        private final String apiVersion;
        private final String model;
        private final OkHttpClient httpClient;

        public ClaudeAIService() {
            this.apiUrl = getProperty("claude_api_url", "https://api.anthropic.com/v1/messages");
            this.apiKey = getProperty("claude_api_key", "");
            this.apiVersion = getProperty("claude_api_version", "2023-06-01");
            this.model = getProperty("claude_model", "claude-3-sonnet-20240229");
            this.httpClient = new OkHttpClient.Builder()
                    .readTimeout(500, TimeUnit.SECONDS)
                    .writeTimeout(500, TimeUnit.SECONDS)
                    .connectTimeout(500, TimeUnit.SECONDS)
                    .build();
        }

        @Override
        public String generateText(String text, List<Message> history, int maxTokens) {
            List<Message> messages = new ArrayList<>(history);
            messages.add(new Message("user", text));

            Map<String, Object> requestBody = new HashMap<>() {
                {
                    put("model", model);
                    put("messages", messages);
                    put("max_tokens", maxTokens);
                }
            };

            try {
                String jsonBody = objectMapper.writeValueAsString(requestBody);
                RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .addHeader("x-api-key", apiKey)
                        .addHeader("anthropic-version", apiVersion)
                        .post(body)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String responseBody = response.body().string();
                    Map<String, Object> responseMap = objectMapper.readValue(responseBody,
                            new TypeReference<Map<String, Object>>() {
                            });
                    List<?> rawContent = (List<?>) responseMap.get("content");
                    List<Map<String, Object>> content = safelyCastListOfMaps(rawContent);

                    if (!content.isEmpty()) {
                        Map<String, Object> firstElement = content.get(0);
                        if (firstElement.containsKey("text")) {
                            return (String) firstElement.get("text");
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            return null;
        }
    }

    private List<Map<String, Object>> safelyCastListOfMaps(List<?> list) {
        List<Map<String, Object>> castedList = new ArrayList<>();
        if (list != null) {
            for (Object item : list) {
                if (item instanceof Map) {
                    Map<?, ?> rawMap = (Map<?, ?>) item;
                    Map<String, Object> safeMap = new HashMap<>();
                    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                        if (entry.getKey() instanceof String) {
                            safeMap.put((String) entry.getKey(), entry.getValue());
                        }
                    }
                    castedList.add(safeMap);
                }
            }
        }
        return castedList;
    }
}