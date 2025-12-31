import java.io.*;
import java.net.*;
import java.net.http.*;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Lock;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public class AIClient
{
    private final String serverIp;
    private final int port;
    private final String ollamaUrl;
    private final String aiModel;
    private String currentRoom;

    private long lastResponseTime = 0;
    private final long RESPONSE_COOLDOWN_MS = 3000;
    private final int MAX_CONTEXT_LINES = 5;

    private final Lock responseLock = new ReentrantLock();
    private final Lock contextLock = new ReentrantLock();

    private volatile boolean isRunning = true;
    private volatile boolean isConnected = false;

    private SSLSocket socket;
    private BufferedReader in;
    private PrintWriter out;

    private final long HEARTBEAT_INTERVAL_MS = 30000;
    private final long CONNECTION_TIMEOUT_MS = 60000;
    private volatile long lastHeartbeatReceived = System.currentTimeMillis();

    public AIClient(String serverIp, int port, String ollamaUrl, String aiModel, String currentRoom)
    {
        this.serverIp = serverIp;
        this.port = port;
        this.ollamaUrl = ollamaUrl;
        this.aiModel = aiModel;
        this.currentRoom = currentRoom;
    }

    public void start()
    {
        Thread.startVirtualThread(this::heartbeatMonitor);

        while (isRunning)
        {
            try
            {
                System.out.println("Attempting to connect to server...");
                connectToServer();

                if (isConnected)
                {
                    Thread.startVirtualThread(this::handleMessages);

                    Thread.startVirtualThread(this::sendHeartbeats);

                    while (isConnected && isRunning)
                    {
                        Thread.sleep(1000);
                    }
                }
            }
            catch (IOException | InterruptedException | GeneralSecurityException e)
            {
                handleConnectionFailure(e);
            }
        }
    }

    private void connectToServer() throws IOException, GeneralSecurityException
    {
        KeyStore trustStore = KeyStore.getInstance("JKS");

        try (FileInputStream tsFile = new FileInputStream("truststore.jks"))
        {
            trustStore.load(tsFile, "123456".toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
        socket = (SSLSocket) sslSocketFactory.createSocket(serverIp, port);

        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(socket.getOutputStream(), true);

        if (authenticateBot())
        {
            isConnected = true;
            lastHeartbeatReceived = System.currentTimeMillis();
            System.out.println("Connected to chat server. Waiting for messages...");
        }
        else
        {
            closeConnection();
        }
    }

    private void closeConnection()
    {
        isConnected = false;

        try
        {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
        }
        catch (IOException e)
        {
            System.err.println("Error closing connection: " + e.getMessage());
        }
    }

    private boolean waitForAuthRequest() throws IOException
    {
        int maxTries = 5;

        while (maxTries-- > 0)
        {
            String serverMessage = in.readLine();

            if (serverMessage == null) return false;
            if ("AUTH_REQUEST".equals(serverMessage)) return true;

            System.out.println("Waiting for AUTH_REQUEST, got instead: " + serverMessage);
        }
        return false;
    }

    private boolean authenticateBot() throws IOException
    {
        if (!waitForAuthRequest())
        {
            System.out.println("Did not receive AUTH_REQUEST from server in time.");
            return false;
        }

        String deviceId = "BOT_DEVICE_" + currentRoom;
        System.out.println("Sending device identifier: " + deviceId);
        out.println(deviceId);

        System.out.println("Sending AI_BOT identifier");
        out.println("AI_BOT");

        System.out.println("Sending bot password");
        out.println("bot_password");

        String serverMessage = in.readLine();

        if (serverMessage == null)
        {
            System.out.println("Server closed connection during bot authentication.");
            return false;
        }

        System.out.println("Received from server: " + serverMessage);

        if (serverMessage.startsWith("Enter room to join:"))
        {
            System.out.println("Sending room to join: " + currentRoom);
            out.println(currentRoom);

            serverMessage = in.readLine();

            if (serverMessage != null && serverMessage.startsWith("AUTH_SUCCESS"))
            {
                System.out.println("Bot successfully authenticated and joined room: " + currentRoom);
                return true;
            }
            else
            {
                System.out.println("Bot authentication failed for room: " + currentRoom + ": " + serverMessage);
                return false;
            }
        }
        else if (serverMessage.startsWith("AUTH_SUCCESS"))
        {
            System.out.println("Bot authenticated successfully!");
            return true;
        }
        else
        {
            System.out.println("Bot authentication failed: " + serverMessage);
            return false;
        }
    }

    private void handleMessages()
    {
        try
        {
            String message;

            while (isConnected && (message = in.readLine()) != null)
            {
                updateHeartbeatTimestamp();

                if (message.equals("HEARTBEAT"))
                {
                    out.println("HEARTBEAT_ACK");
                    continue;
                }

                System.out.println("Received message: " + message);

                if (message.contains("@bot"))
                {
                    String response = generateAIResponse(message);
                    sendBotResponse(response);
                }

                contextLock.lock();

                try
                {
                    saveMessageToContext(message);
                }
                finally
                {
                    contextLock.unlock();
                }
            }
        }
        catch (IOException e)
        {
            if (isConnected)
            {
                System.err.println("Error reading from server: " + e.getMessage());
                handleDisconnect();
            }
        }
    }

    private void saveMessageToContext(String message)
    {
        try
        {
            File logFile = new File(currentRoom + "_log.txt");
            boolean fileExists = logFile.exists();

            try (FileWriter fw = new FileWriter(logFile, true);
                 BufferedWriter bw = new BufferedWriter(fw))
            {
                if (!fileExists)
                {
                    logFile.getParentFile().mkdirs();
                }

                bw.write(message);
                bw.newLine();
            }
        }
        catch (IOException e)
        {
            System.err.println("Error saving message to context: " + e.getMessage());
        }
    }

    private void updateHeartbeatTimestamp()
    {
        lastHeartbeatReceived = System.currentTimeMillis();
    }

    private void sendHeartbeats()
    {
        while (isConnected && isRunning)
        {
            try
            {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);

                if (isConnected)
                {
                    out.println("HEARTBEAT");
                    System.out.println("Sent heartbeat to server");
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void heartbeatMonitor()
    {
        while (isRunning)
        {
            try
            {
                Thread.sleep(5000);

                if (isConnected)
                {
                    long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatReceived;

                    if (timeSinceLastHeartbeat > CONNECTION_TIMEOUT_MS)
                    {
                        System.out.println("No heartbeat received for " + (timeSinceLastHeartbeat / 1000) + " seconds. Reconnecting...");handleDisconnect();
                    }
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void handleDisconnect()
    {
        closeConnection();
    }

    private void handleConnectionFailure(Exception e)
    {
        closeConnection();
        System.err.println("Connection failed, retrying in 5 seconds...");
        e.printStackTrace();
        try
        {
            TimeUnit.SECONDS.sleep(5);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            isRunning = false;
        }
    }

    private void sendBotResponse(String response)
    {
        if (isConnected && out != null)
        {
            System.out.println("[Bot] Sending response to server and room " + currentRoom + " : " + response);
            out.println("[Bot]: " + response);
        }
    }

    private String generateAIResponse(String prompt)
    {
        responseLock.lock();

        try
        {
            long now = System.currentTimeMillis();

            if (now - lastResponseTime < RESPONSE_COOLDOWN_MS)
            {
                System.out.println("[Bot] Cooldown active. Sending wait message.");
                return "Please wait a moment before asking again...";
            }
            lastResponseTime = now;
        }
        finally
        {
            responseLock.unlock();
        }

        try
        {
            System.out.println("[Bot] Generating response for prompt: " + prompt);

            List<String> context;
            contextLock.lock();

            try
            {
                context = getRoomContext();
            }
            finally
            {
                contextLock.unlock();
            }

            String contextPrompt = buildContextPrompt(prompt, context);
            String jsonRequest = String.format(
                    "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false}",
                    aiModel,
                    escapeJson(contextPrompt)
            );

            System.out.println("[Bot] JSON request to Ollama:\n" + jsonRequest);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200)
            {
                System.err.println("[Bot] Ollama API error: " + response.body());
                return "Sorry, I'm having technical difficulties. (API Error)";
            }

            String responseBody = response.body();
            System.out.println("[Bot] Raw API response: " + responseBody);

            int start = responseBody.indexOf("\"response\":\"") + 11;
            int end = responseBody.indexOf("\"", start + 1);

            if (start < 11 || end <= start)
            {
                System.err.println("[Bot] Failed to parse AI response properly.");
                return "I'm not sure how to respond...";
            }

            String aiResponse = responseBody.substring(start + 1, end);
            System.out.println("[Bot] Generated AI Response: " + aiResponse);

            return aiResponse;
        }
        catch (Exception e)
        {
            System.err.println("[Bot] AI Error: " + e.getMessage());
            return "I'm having trouble thinking right now...";
        }
    }

    private List<String> getRoomContext()
    {
        List<String> context = new ArrayList<>();

        try
        {
            File logFile = new File(currentRoom + "_log.txt");

            if (!logFile.exists())
            {
                System.out.println("[Bot] No previous room log found.");
                return context;
            }

            System.out.println("[Bot] Reading recent messages from: " + logFile.getName());

            try (BufferedReader reader = new BufferedReader(new FileReader(logFile)))
            {
                Deque<String> lastMessages = new ArrayDeque<>();
                String line;

                while ((line = reader.readLine()) != null)
                {
                    if (!line.contains("[Bot]") && !line.trim().isEmpty())
                    {
                        lastMessages.addLast(line);

                        if (lastMessages.size() > MAX_CONTEXT_LINES)
                        {
                            lastMessages.removeFirst();
                        }
                    }
                }

                context.addAll(lastMessages);
                System.out.println("[Bot] Room context for prompt:\n" + context);
            }
        }
        catch (IOException e)
        {
            System.err.println("[Bot] Couldn't read room context: " + e.getMessage());
        }

        return context;
    }

    private String buildContextPrompt(String prompt, List<String> context)
    {
        StringBuilder sb = new StringBuilder();

        if (!context.isEmpty())
        {
            sb.append("Recent conversation context:\n");

            for (String msg : context)
            {
                sb.append(msg).append("\n");
            }

            sb.append("\n");
        }

        sb.append("Please respond to this: ").append(prompt.replace("@bot", "").trim());

        String finalPrompt = sb.toString();
        System.out.println("[Bot] Final prompt with context:\n" + finalPrompt);

        return finalPrompt;
    }

    private String escapeJson(String input) {
        String escapedInput = input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");

        System.out.println("[Bot] Escaped JSON input: " + escapedInput);
        return escapedInput;
    }

    public void changeRoom(String newRoom)
    {
        contextLock.lock();
        try
        {
            this.currentRoom = newRoom;
        }
        finally
        {
            contextLock.unlock();
        }

        if (isConnected && out != null)
        {
            out.println("CHANGE_ROOM:" + newRoom);
        }
    }

    public void shutdown()
    {
        isRunning = false;
        closeConnection();
    }

    public static void main(String[] args)
    {
        AIClient client = new AIClient("localhost", 8080, "http://localhost:11434", "llama3", "general");
        client.start();
    }
}