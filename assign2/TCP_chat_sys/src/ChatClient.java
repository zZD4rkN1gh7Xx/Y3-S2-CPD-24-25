import java.io.*;
import java.net.*;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.util.concurrent.locks.ReentrantLock;

public class ChatClient {

    private final String serverIP;
    private final int port;

    private static volatile String currentInput = "";
    private static final AtomicBoolean inRoomTransition = new AtomicBoolean(false);
    private static final String DEVICE_ID_FILE = "device_id.txt";
    private static final String AUTH_TOKEN_FILE = "auth_token.txt";
    private volatile String authToken;

    private final ReentrantLock authTokenLock = new ReentrantLock();

    public ChatClient(String serverAddress, int port) throws IllegalArgumentException
    {
        if (serverAddress == null || serverAddress.trim().isEmpty())
        {
            throw new IllegalArgumentException("Server IP/hostname cannot be null or empty.");
        }

        if (port < 1 || port > 65535)
        {
            throw new IllegalArgumentException("Server port must be between 1 and 65535.");
        }

        try
        {
            InetAddress.getByName(serverAddress);
        }
        catch (UnknownHostException e)
        {
            throw new IllegalArgumentException("Invalid server IP/hostname: " + serverAddress);
        }

        this.serverIP = serverAddress;
        this.port = port;
        this.authToken = loadAuthToken();
    }

    public void start_client()
    {
        int maxAttempts = 3;
        int attempt = 0;
        boolean connected = false;
        SSLSocket socket = null;

        while (attempt < maxAttempts && !connected)
        {
            attempt++;

            try
            {
                System.out.println("Attempting to connect to server (Attempt " + attempt + "/" + maxAttempts + ")...");

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
                socket = (SSLSocket) sslSocketFactory.createSocket(serverIP, port);
                connected = true;

                System.out.println("Connection established!");

                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                Scanner scanner = new Scanner(System.in);

                if (!handleAuthentication(in, out, scanner))
                {
                    System.out.println("Authentication failed. Disconnecting.");
                    return;
                }

                Thread.ofVirtual().start(() -> {
                    try {
                        String serverMsg;
                        while ((serverMsg = in.readLine()) != null)
                        {
                            if (serverMsg.contains("|TOKEN:"))
                            {
                                String[] parts = serverMsg.split("\\|TOKEN:");

                                if (parts.length > 1)
                                {
                                    this.authToken = parts[1].trim();
                                    saveAuthToken(this.authToken);
                                }
                                serverMsg = parts[0];
                            }

                            System.out.print("\r" + " ".repeat(currentInput.length() + 5) + "\r");
                            System.out.println(serverMsg);
                            System.out.print("You: " + currentInput);
                        }
                    }
                    catch (IOException e)
                    {
                        System.out.println("\nConnection closed.");
                    }
                });

                System.out.print("You: ");

                while (scanner.hasNextLine())
                {
                    String input = scanner.nextLine();
                    currentInput = "";

                    if (input.startsWith("/join "))
                    {
                        if (inRoomTransition.compareAndSet(false, true))
                        {
                            try
                            {
                                String roomName = input.substring(6).trim();
                                System.out.println("Attempting to join room: " + roomName);
                                out.println(input);
                                Thread.sleep(300);
                            }
                            finally
                            {
                                inRoomTransition.set(false);
                            }
                        }
                        else
                        {
                            System.out.println("Room transition in progress, please wait...");
                        }
                    }
                    else
                    {
                        out.println(input);
                    }

                    System.out.print("You: ");
                }

            }
            catch (IOException | GeneralSecurityException e)
            {
                System.out.println("Connection attempt " + attempt + " failed: " + e.getMessage());
            }
            catch (InterruptedException e)
            {
                System.out.println("Client interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
                return;
            }
            finally
            {
                if (!connected && socket != null)
                {
                    try
                    {
                        socket.close();
                    }
                    catch (IOException ignored) {}
                }
            }

            if (!connected && attempt < maxAttempts)
            {
                try
                {
                    Thread.sleep(2000);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (!connected)
        {
            System.out.println("Failed to connect after " + maxAttempts + " attempts.");
        }
    }

    private boolean handleAuthentication(BufferedReader in, PrintWriter out, Scanner scanner) throws IOException
    {
        String deviceFingerprint = generateDeviceFingerprint(getUserAgent());

        if (authToken != null && !authToken.isEmpty())
        {
            out.println(deviceFingerprint + "|TOKEN:" + authToken);
        }
        else
        {
            out.println(deviceFingerprint);
        }

        String serverResponse = in.readLine();

        if (serverResponse != null && serverResponse.startsWith("AUTH_SUCCESS"))
        {
            String token = null;
            String room = null;

            if (serverResponse.contains("|TOKEN:"))
            {
                String[] parts = serverResponse.split("\\|TOKEN:");

                if (parts.length > 1)
                {
                    String tokenPart = parts[1];

                    if (tokenPart.contains("|ROOM:"))
                    {
                        String[] roomParts = tokenPart.split("\\|ROOM:");
                        token = roomParts[0].trim();
                        room = roomParts[1].trim();
                    }
                    else
                    {
                        token = tokenPart.trim();
                    }

                    this.authToken = token;
                    saveAuthToken(this.authToken);

                    System.out.println(parts[0]);
                }
                else
                {
                    System.out.println(serverResponse);
                }
            }
            else
            {
                System.out.println(serverResponse);
            }

            String serverInfo;
            boolean commandInfoReceived = false;
            while ((serverInfo = in.readLine()) != null)
            {
                if (serverInfo.startsWith("AVAILABLE COMMANDS:") || serverInfo.startsWith("AVAILABLE BOT COMMAND:"))
                {
                    System.out.println(serverInfo);
                    commandInfoReceived = true;
                }
                else if (serverInfo.startsWith("You have joined room:"))
                {
                    System.out.println(serverInfo);
                }
                else if ((serverInfo.startsWith("[Server]") || serverInfo.contains(":")) && commandInfoReceived)
                {
                    System.out.println(serverInfo);
                    break;
                }
                else
                {
                    System.out.println(serverInfo);
                }
            }

            return true;
        }

        System.out.println("Do you want to [login] or [register]?");
        System.out.print("Choice: ");
        String mode = scanner.nextLine().trim().toLowerCase();

        while (!mode.equals("login") && !mode.equals("register"))
        {
            System.out.println("Invalid choice. Please enter 'login' or 'register'.");
            System.out.print("Choice: ");

            mode = scanner.nextLine().trim().toLowerCase();
        }

        out.println(mode);

        while (true)
        {
            String serverPrompt = in.readLine();

            if (serverPrompt == null)
            {
                System.out.println("\nServer closed connection.");
                return false;
            }

            if (serverPrompt.startsWith("Enter username:"))
            {
                System.out.print("Username: ");
                out.println(scanner.nextLine());
            }
            else if (serverPrompt.startsWith("Enter password:"))
            {
                System.out.print("Password: ");
                out.println(scanner.nextLine());
            }
            else if (serverPrompt.startsWith("AUTH_SUCCESS"))
            {
                String token = null;

                if (serverPrompt.contains("|TOKEN:"))
                {
                    String[] parts = serverPrompt.split("\\|TOKEN:");

                    if (parts.length > 1)
                    {
                        String tokenPart = parts[1];

                        if (tokenPart.contains("|ROOM:"))
                        {
                            String[] roomParts = tokenPart.split("\\|ROOM:");
                            token = roomParts[0].trim();
                        }
                        else
                        {
                            token = tokenPart.trim();
                        }

                        this.authToken = token;
                        saveAuthToken(this.authToken);
                        System.out.println(parts[0]);
                    }
                    else
                    {
                        System.out.println(serverPrompt);
                    }
                }
                else
                {
                    System.out.println(serverPrompt);
                }

                String serverInfo;
                boolean commandInfoReceived = false;
                while ((serverInfo = in.readLine()) != null)
                {
                    if (serverInfo.startsWith("AVAILABLE COMMANDS:") || serverInfo.startsWith("AVAILABLE BOT COMMAND:"))
                    {
                        System.out.println(serverInfo);
                        commandInfoReceived = true;
                    }
                    else if (serverInfo.startsWith("You have joined room:"))
                    {
                        System.out.println(serverInfo);
                    }
                    else if ((serverInfo.startsWith("[Server]") || serverInfo.contains(":")) && commandInfoReceived)
                    {
                        System.out.println(serverInfo);
                        break;
                    }
                    else
                    {
                        System.out.println(serverInfo);
                    }
                }

                return true;
            }
            else if (serverPrompt.startsWith("AUTH_FAIL"))
            {
                System.out.println(serverPrompt);

                if (serverPrompt.contains("Too many failed") || serverPrompt.contains("Login failed"))
                {
                    break;
                }
            }
            else
            {
                System.out.println("Server: " + serverPrompt);
            }
        }

        return false;
    }

    private String getOrCreateDeviceUUID()
    {
        File file = new File(DEVICE_ID_FILE);

        try
        {
            if (file.exists())
            {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                return reader.readLine();
            }
            else
            {
                String deviceUUID = UUID.randomUUID().toString();

                try (BufferedWriter writer = new BufferedWriter(new FileWriter(file)))
                {
                    writer.write(deviceUUID);
                }

                return deviceUUID;
            }
        }
        catch (IOException e)
        {
            System.out.println("Error reading/writing device UUID: " + e.getMessage());
            return null;
        }
    }

    public String generateDeviceFingerprint(String userAgent)
    {
        String deviceUUID = getOrCreateDeviceUUID();
        String deviceData = deviceUUID + "|" + userAgent;

        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(deviceData.getBytes());

            StringBuilder hexString = new StringBuilder();

            for (byte b : hash)
            {
                String hex = Integer.toHexString(0xff & b);

                if (hex.length() == 1)
                {
                    hexString.append('0');
                }

                hexString.append(hex);
            }

            return hexString.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new RuntimeException("Failed to generate device fingerprint", e);
        }
    }

    private String getUserAgent()
    {
        return System.getProperty("os.name") + " " + System.getProperty("os.version");
    }

    private void saveAuthToken(String token)
    {
        authTokenLock.lock();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(AUTH_TOKEN_FILE)))
        {
            this.authToken = token;
            writer.write(token);
        }
        catch (IOException e)
        {
            System.out.println("Error saving auth token: " + e.getMessage());
        }
        finally
        {
            authTokenLock.unlock();
        }
    }

    private String loadAuthToken()
    {
        File file = new File(AUTH_TOKEN_FILE);

        if (file.exists())
        {
            try (BufferedReader reader = new BufferedReader(new FileReader(file)))
            {
                return reader.readLine();
            }
            catch (IOException e)
            {
                System.out.println("Error loading auth token: " + e.getMessage());
            }
        }
        return null;
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.print("Enter server IP: ");
        String ip = scanner.nextLine();

        System.out.print("Enter server port: ");
        int port = Integer.parseInt(scanner.nextLine());

        ChatClient client = new ChatClient(ip, port);
        client.start_client();

        scanner.close();
    }
}