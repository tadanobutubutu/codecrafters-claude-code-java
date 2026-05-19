import static com.openai.core.ObjectMappers.jsonMapper;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonObject;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;

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

        ChatCompletionTool readTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Read")
                        .description("Read and return the contents of a file")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "file_path", Map.of("type", "string", "description", "The path to the file to read")
                                )))
                                .putAdditionalProperty("required", JsonValue.from(List.of("file_path")))
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                                .build())
                        .build())
                .build();

        ChatCompletionTool writeTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Write")
                        .description("Write content to a file")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "file_path", Map.of("type", "string", "description", "The path of the file to write to"),
                                        "content", Map.of("type", "string", "description", "The content to write to the file")
                                )))
                                .putAdditionalProperty("required", JsonValue.from(List.of("file_path", "content")))
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                                .build())
                        .build())
                .build();

        ChatCompletionTool bashTool = ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("Bash")
                        .description("Execute a shell command")
                        .parameters(FunctionParameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                        "command", Map.of("type", "string", "description", "The command to execute")
                                )))
                                .putAdditionalProperty("required", JsonValue.from(List.of("command")))
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                                .build())
                        .build())
                .build();

        ChatCompletionCreateParams.Builder createParamsBuilder = ChatCompletionCreateParams.builder()
                .model("anthropic/claude-haiku-4.5")
                .addTool(readTool)
                .addTool(writeTool)
                .addTool(bashTool)
                .addUserMessage(prompt);

        while (true) {
            ChatCompletion response = client.chat().completions().create(createParamsBuilder.build());

            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            ChatCompletionMessage assistantMessage = response.choices().get(0).message();
            createParamsBuilder.addMessage(assistantMessage);

            if (assistantMessage.toolCalls().isPresent() && !assistantMessage.toolCalls().get().isEmpty()) {
                for (ChatCompletionMessageToolCall toolCall : assistantMessage.toolCalls().get()) {
                    String toolCallId = toolCall.id();
                    String functionName = toolCall.function().name();
                    String argumentsStr = toolCall.function().arguments();

                    String result = "";
                    try {
                        JsonValue arguments = JsonValue.from(jsonMapper().readTree(argumentsStr));
                        JsonObject obj = (JsonObject) arguments;

                        if ("Read".equals(functionName)) {
                            String filePath = obj.values().get("file_path").asStringOrThrow();
                            try {
                                byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath));
                                result = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                            } catch (Exception e) {
                                result = "Error: " + e.getMessage();
                            }
                        } else if ("Write".equals(functionName)) {
                            String filePath = obj.values().get("file_path").asStringOrThrow();
                            String content = obj.values().get("content").asStringOrThrow();
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
                            String command = obj.values().get("command").asStringOrThrow();
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

                    createParamsBuilder.addMessage(ChatCompletionToolMessageParam.builder()
                            .toolCallId(toolCallId)
                            .content(result)
                            .build());
                }
                continue;
            }

            System.out.print(assistantMessage.content().orElse(""));
            break;
        }
    }
}
