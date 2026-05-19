import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

public class Main {
    @JsonClassDescription("Read and return the contents of a file")
    public static class Read {
        @JsonPropertyDescription("The path to the file to read")
        public String file_path;
    }

    @JsonClassDescription("Write content to a file")
    public static class Write {
        @JsonPropertyDescription("The path of the file to write to")
        public String file_path;
        @JsonPropertyDescription("The content to write to the file")
        public String content;
    }

    @JsonClassDescription("Execute a shell command")
    public static class Bash {
        @JsonPropertyDescription("The command to execute")
        public String command;
    }

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

        ChatCompletionCreateParams.Builder createParamsBuilder = ChatCompletionCreateParams.builder()
                .model("anthropic/claude-haiku-4.5")
                .addTool(Read.class)
                .addTool(Write.class)
                .addTool(Bash.class)
                .addUserMessage(prompt);

        while (true) {
            ChatCompletion response = client.chat().completions().create(createParamsBuilder.build());

            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            ChatCompletionMessage assistantMessage = response.choices().get(0).message();
            createParamsBuilder.addMessage(assistantMessage);

            if (assistantMessage.toolCalls().isPresent() && !assistantMessage.toolCalls().get().isEmpty()) {
                for (ChatCompletionMessage.ToolCall toolCall : assistantMessage.toolCalls().get()) {
                    String toolCallId = toolCall.asFunction().id();
                    String functionName = toolCall.asFunction().function().name();

                    String result = "";
                    try {
                        if ("Read".equals(functionName)) {
                            Read readArgs = toolCall.asFunction().function().arguments(Read.class);
                            try {
                                byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(readArgs.file_path));
                                result = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                            } catch (Exception e) {
                                result = "Error: " + e.getMessage();
                            }
                        } else if ("Write".equals(functionName)) {
                            Write writeArgs = toolCall.asFunction().function().arguments(Write.class);
                            try {
                                java.nio.file.Path path = java.nio.file.Paths.get(writeArgs.file_path);
                                java.nio.file.Path parent = path.getParent();
                                if (parent != null) {
                                    java.nio.file.Files.createDirectories(parent);
                                }
                                java.nio.file.Files.write(path, writeArgs.content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                result = "Successfully wrote to " + writeArgs.file_path;
                            } catch (Exception e) {
                                result = "Error: " + e.getMessage();
                            }
                        } else if ("Bash".equals(functionName)) {
                            Bash bashArgs = toolCall.asFunction().function().arguments(Bash.class);
                            try {
                                ProcessBuilder pb = new ProcessBuilder("bash", "-c", bashArgs.command);
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
