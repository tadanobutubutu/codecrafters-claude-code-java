import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.FunctionDefinition;
import com.openai.models.chat.completions.FunctionParameters;
import com.openai.core.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) {
        if (args.length < 2 || !"-p".equals(args[0])) {
            System.err.println("Usage: program -p <prompt>");
            System.exit(1);
        }

        String prompt = args[1];

        String apiKey = System.getenv("OPENROUTER_API_KEY");
        String baseUrl = System.getenv("OPENROUTER_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://openrouter.ai/api/v1";
        }

        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OPENROUTER_API_KEY is not set");
        }

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        // Define tools
        ChatCompletionTool readTool = ChatCompletionTool.ofFunction(
            ChatCompletionFunctionTool.builder()
                .function(FunctionDefinition.builder()
                    .name("Read")
                    .description("Read and return the contents of a file")
                    .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("required", JsonValue.from(List.of("file_path")))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of(
                            "file_path", Map.of(
                                "type", "string",
                                "description", "The path to the file to read"
                            )
                        )))
                        .build())
                    .build())
                .build()
        );

        ChatCompletionTool writeTool = ChatCompletionTool.ofFunction(
            ChatCompletionFunctionTool.builder()
                .function(FunctionDefinition.builder()
                    .name("Write")
                    .description("Write content to a file")
                    .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("required", JsonValue.from(List.of("file_path", "content")))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of(
                            "file_path", Map.of(
                                "type", "string",
                                "description", "The path of the file to write to"
                            ),
                            "content", Map.of(
                                "type", "string",
                                "description", "The content to write to the file"
                            )
                        )))
                        .build())
                    .build())
                .build()
        );

        ChatCompletionTool bashTool = ChatCompletionTool.ofFunction(
            ChatCompletionFunctionTool.builder()
                .function(FunctionDefinition.builder()
                    .name("Bash")
                    .description("Execute a shell command")
                    .parameters(FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("required", JsonValue.from(List.of("command")))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of(
                            "command", Map.of(
                                "type", "string",
                                "description", "The command to execute"
                            )
                        )))
                        .build())
                    .build())
                .build()
        );

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        messages.add(ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder()
                .role(ChatCompletionUserMessageParam.Role.USER)
                .content(ChatCompletionUserMessageParam.Content.ofTextContent(prompt))
                .build()
        ));

        ObjectMapper mapper = new ObjectMapper();

        while (true) {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model("anthropic/claude-haiku-4.5")
                    .messages(messages)
                    .addTool(readTool)
                    .addTool(writeTool)
                    .addTool(bashTool)
                    .build();

            ChatCompletion response = client.chat().completions().create(params);

            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            ChatCompletionAssistantMessageParam assistantMessage = response.choices().get(0).message();
            messages.add(ChatCompletionMessageParam.ofAssistant(assistantMessage));

            if (assistantMessage.toolCalls().isPresent() && !assistantMessage.toolCalls().get().isEmpty()) {
                List<ChatCompletionAssistantMessageParam.ToolCall> toolCalls = assistantMessage.toolCalls().get();
                for (ChatCompletionAssistantMessageParam.ToolCall toolCall : toolCalls) {
                    String toolCallId = toolCall.asFunction().id();
                    String functionName = toolCall.asFunction().function().name();
                    String arguments = toolCall.asFunction().function().arguments();

                    String result = "";
                    try {
                        JsonNode node = mapper.readTree(arguments);
                        if ("Read".equals(functionName)) {
                            String filePath = node.get("file_path").asText();
                            try {
                                byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath));
                                result = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                            } catch (Exception e) {
                                result = "Error: " + e.getMessage();
                            }
                        } else if ("Write".equals(functionName)) {
                            String filePath = node.get("file_path").asText();
                            String content = node.get("content").asText();
                            try {
                                java.nio.file.Path path = java.nio.file.Paths.get(filePath);
                                java.nio.file.Path parent = path.getParent();
                                if (parent != null) {
                                    java.nio.file.Files.createDirectories(parent);
                                }
                                java.nio.file.Files.write(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                result = "Successfully wrote to " + filePath;
                            } catch (Exception e) {
                                result = "Error: " + e.getMessage();
                            }
                        } else if ("Bash".equals(functionName)) {
                            String command = node.get("command").asText();
                            try {
                                ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                                pb.redirectErrorStream(true);
                                Process process = pb.start();
                                if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                                    process.destroyForcibly();
                                    result = "Command timed out";
                                } else {
                                    try (java.io.InputStream is = process.getInputStream();
                                         java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                                        byte[] buffer = new byte[1024];
                                        int length;
                                        while ((length = is.read(buffer)) != -1) {
                                            baos.write(buffer, 0, length);
                                        }
                                        byte[] outputBytes = baos.toByteArray();
                                        if (outputBytes.length == 0) {
                                            result = "Command executed successfully (no output)";
                                        } else {
                                            result = new String(outputBytes, java.nio.charset.StandardCharsets.UTF_8);
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                result = "Error: " + e.getMessage();
                            }
                        } else {
                            result = "Unknown tool: " + functionName;
                        }
                    } catch (Exception e) {
                        result = "Error parsing tool arguments: " + e.getMessage();
                    }

                    messages.add(ChatCompletionMessageParam.ofTool(
                        ChatCompletionToolMessageParam.builder()
                            .toolCallId(toolCallId)
                            .content(result)
                            .build()
                    ));
                }
                continue;
            }

            System.out.print(assistantMessage.content().orElse(""));
            break;
        }
    }
}
