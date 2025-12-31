import java.io.*;
import java.net.*;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.*;
import java.security.KeyStore;

public class ChatServer
{

    private final int port;
    private boolean running;
    private ServerSocket serverSocket;

    private final List<Socket> clientSockets = new ArrayList<>();
    private final List<PrintWriter> clientWriters = new ArrayList<>();
    private final Map<String, ServerRoom> serverRooms = new HashMap<>();

    private final ClientAuthSystem clientAuth = new ClientAuthSystem();
    private final ClientTokenManager tokenManager = new ClientTokenManager();
    private final Map<Socket, String> socketToFingerprintMap = new HashMap<>();

    private final Map<String, PrintWriter> botWriters = new HashMap<>();

    private final ReentrantLock clientSocketsLock = new ReentrantLock();
    private final ReentrantLock clientWritersLock = new ReentrantLock();
    private final ReentrantLock serverRoomsLock = new ReentrantLock();
    private final ReentrantLock socketFingerprintLock = new ReentrantLock();
    private final ReentrantLock botWritersLock = new ReentrantLock();


    public ChatServer(int port)
    {
        this.port = port;
    }

    private String getDeviceFingerprint(Socket socket)
    {
        return socketToFingerprintMap.get(socket);
    }

    public void start_server()
    {
        running = true;
        System.out.println("Chat server starting on port " + port + "...");

        tokenManager.loadTokensFromFile();

        tokenManager.purgeExpiredTokens();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Server shutting down. Saving tokens...");
            tokenManager.saveTokensToFile();
        }));

        try
        {
            KeyStore keyStore = KeyStore.getInstance("JKS");

            try (FileInputStream keyStoreFile = new FileInputStream("keystore.jks"))
            {
                keyStore.load(keyStoreFile, "123456".toCharArray());
            }

            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
            keyManagerFactory.init(keyStore, "123456".toCharArray());

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), null, null);

            SSLServerSocketFactory sslServerSocketFactory = sslContext.getServerSocketFactory();

            try (SSLServerSocket serverSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(port))
            {
                this.serverSocket = serverSocket;
                System.out.println("Secure Chat server listening on port " + port);

                getOrCreateRoom("general");

                while (running)
                {
                    SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                    System.out.println("New Client connected: " + clientSocket.getInetAddress().getHostAddress());

                    PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true);

                    clientSocketsLock.lock();
                    try
                    {
                        clientSockets.add(clientSocket);
                    }
                    finally
                    {
                        clientSocketsLock.unlock();
                    }

                    clientWritersLock.lock();
                    try
                    {
                        clientWriters.add(writer);
                    }
                    finally
                    {
                        clientWritersLock.unlock();
                    }


                    Thread.ofVirtual().start(() -> {
                        try
                        {
                            handleClient(clientSocket, writer);
                        }
                        catch (NoSuchAlgorithmException e)
                        {
                            writer.println("AUTH_FAIL Server error");
                            System.out.println("Hashing algorithm not available: " + e.getMessage());

                            try
                            {
                                clientSocket.close();
                            }
                            catch (IOException ex)
                            {
                                System.out.println("Error closing client socket: " + ex.getMessage());
                            }
                        }
                    });
                }
            }
        }
        catch (Exception e)
        {
            if (running)
            {
                System.out.println("Server error: " + e.getMessage());
            }
        }
    }

    public void stop_server()
    {
        running = false;

        try
        {
            if (serverSocket != null && !serverSocket.isClosed())
            {
                serverSocket.close();
            }
        }
        catch (IOException e)
        {
            System.out.println("Error closing server: " + e.getMessage());
        }

        clientSocketsLock.lock();
        try
        {
            for (Socket socket : clientSockets)
            {
                try
                {
                    if (socket != null && !socket.isClosed())
                    {
                        socket.close();
                    }
                }
                catch (IOException e)
                {
                    System.out.println("Error closing client socket: " + e.getMessage());
                }
            }

            clientSockets.clear();
        }
        finally
        {
            clientSocketsLock.unlock();
        }

        clientWritersLock.lock();
        try
        {
            clientWriters.clear();
        }
        finally
        {
            clientWritersLock.unlock();
        }
    }


    private void handleClient(Socket clientSocket, PrintWriter writer) throws NoSuchAlgorithmException
    {
        String username = null;
        boolean isTokenAuth = false;

        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream())))
        {
            writer.println("AUTH_REQUEST");

            while (username == null)
            {
                String[] authResult = performAuthentication(in, writer, clientSocket);
                username = authResult[0];
                isTokenAuth = "true".equals(authResult[1]);

                if (username == null)
                {
                    writer.println("AUTH_REQUEST");
                }
            }

            String botRoom = null;

            if (username.startsWith("AI_Bot#"))
            {
                botRoom = username.split("#")[1];
                username = "AI_Bot";
                botWriters.put(botRoom, writer);
            }

            if(!username.equals("AI_Bot") && !isTokenAuth)
            {
                broadcast("[Server] " + username + " has joined the chat.", null);
            }

            chatLoop(username, in, writer, clientSocket, botRoom, isTokenAuth);

        } catch (IOException e)
        {
            System.out.println("Client disconnected: " + clientSocket.getInetAddress());
        }
        finally
        {
            cleanupConnection(clientSocket, writer, username);
        }
    }

    private String[] performAuthentication(BufferedReader in, PrintWriter writer, Socket clientSocket) throws IOException, NoSuchAlgorithmException
    {
        String deviceFingerprint = in.readLine();
        if (deviceFingerprint == null) return new String[]{null, "false"};

        String token = null;

        if (deviceFingerprint.contains("|TOKEN:"))
        {
            String[] parts = deviceFingerprint.split("\\|TOKEN:");
            deviceFingerprint = parts[0];
            token = parts[1];
        }

        socketToFingerprintMap.put(clientSocket, deviceFingerprint);

        if (token != null && !token.isEmpty())
        {
            String fingerprintUsername = tokenManager.findUsernameByTokenAndFingerprint(token, deviceFingerprint);

            if (fingerprintUsername != null)
            {
                String defaultRoom = tokenManager.getDefaultRoomForFingerprint(deviceFingerprint);

                if (defaultRoom == null || defaultRoom.trim().isEmpty()) {

                    defaultRoom = "general";
                }

                String newToken = tokenManager.generateToken(fingerprintUsername, deviceFingerprint, defaultRoom);

                writer.println("AUTH_SUCCESS Welcome back, " + fingerprintUsername + "!|TOKEN:" + newToken + "|ROOM:" + defaultRoom);
                writer.println("AVAILABLE COMMANDS: /join <room_name> - Join/Create chat room :/leave - Leave room&return to default : /listrooms - List all rooms.");
                writer.println("AVAILABLE BOT COMMAND: @bot + message");
                writer.flush();

                System.out.println("User " + fingerprintUsername + " authenticated via token with default room: " + defaultRoom);
                return new String[]{fingerprintUsername, "true"};
            }
        }

        String fingerprintUsername = tokenManager.findUsernameByFingerprint(deviceFingerprint);

        if (fingerprintUsername != null)
        {
            String defaultRoom = tokenManager.getDefaultRoomForFingerprint(deviceFingerprint);

            if (defaultRoom == null || defaultRoom.trim().isEmpty())
            {
                defaultRoom = "general";
            }

            String newToken = tokenManager.generateToken(fingerprintUsername, deviceFingerprint, defaultRoom);

            writer.println("AUTH_SUCCESS Welcome back, " + fingerprintUsername + "!|TOKEN:" + newToken + "|ROOM:" + defaultRoom);
            writer.println("AVAILABLE COMMANDS: /join <room_name> - Join/Create chat room :/leave - Leave room&return to default : /listrooms - List all rooms.");
            writer.println("AVAILABLE BOT COMMAND: @bot + message");
            writer.flush();

            System.out.println("User " + fingerprintUsername + " authenticated via device fingerprint with default room: " + defaultRoom);
            return new String[]{fingerprintUsername, "true"};
        }

        String mode = in.readLine();

        if (mode == null) return new String[]{null, "false"};

        if ("AI_BOT".equals(mode))
        {
            String password = in.readLine();
            if ("bot_password".equals(password))
            {
                writer.println("Enter room to join:");

                String botRoom = in.readLine();

                if (botRoom == null || botRoom.trim().isEmpty())
                {
                    writer.println("AUTH_FAIL Room name cannot be empty");
                    return new String[]{null, "false"};
                }

                writer.println("AUTH_SUCCESS");
                return new String[]{"AI_Bot#" + botRoom.trim(), "false"};
            }

            writer.println("AUTH_FAIL Invalid bot credentials");
            return new String[]{null, "false"};
        }

        mode = mode.trim().toLowerCase();

        if (mode.equals("register"))
        {
            writer.println("Enter username:");
            String username = in.readLine();

            if (username == null || username.trim().isEmpty())
            {
                writer.println("AUTH_FAIL Username cannot be empty");
                return new String[]{null, "false"};
            }

            if (clientAuth.usernameExists(username))
            {
                writer.println("AUTH_FAIL Username already exists");
                return new String[]{null, "false"};
            }

            writer.println("Enter password:");
            String password = in.readLine();

            if (password == null || password.trim().isEmpty())
            {
                writer.println("AUTH_FAIL Password cannot be empty");
                return new String[]{null, "false"};
            }

            if (clientAuth.registerClient(username, password))
            {
                String newToken = tokenManager.generateToken(username, deviceFingerprint, "general");

                writer.println("AUTH_SUCCESS Welcome, " + username + "!|TOKEN:" + newToken + "|ROOM:general");
                writer.println("AVAILABLE COMMANDS: /join <room_name> - Join/Create chat room :/leave - Leave room&return to default : /listrooms - List all rooms.");
                writer.flush();

                return new String[]{username, "false"};
            }
            else
            {
                writer.println("AUTH_FAIL Registration failed");
                return new String[]{null, "false"};
            }
        }
        else if (mode.equals("login"))
        {
            for (int attempts = 0; attempts < 3; attempts++)
            {
                writer.println("Enter username:");
                String username = in.readLine();

                writer.println("Enter password:");
                String password = in.readLine();

                if (username == null || password == null)
                {
                    writer.println("AUTH_FAIL Input cannot be null");
                    continue;
                }

                if (!clientAuth.usernameExists(username))
                {
                    writer.println("AUTH_FAIL Username does not exist");
                }
                else if (!clientAuth.verifyClient(username, password))
                {
                    writer.println("AUTH_FAIL Invalid password");
                }
                else
                {
                    String defaultRoom = tokenManager.getDefaultRoomForFingerprint(deviceFingerprint);

                    if (defaultRoom == null || defaultRoom.trim().isEmpty())
                    {
                        defaultRoom = "general";
                    }

                    String newToken = tokenManager.generateToken(username, deviceFingerprint, defaultRoom);

                    writer.println("AUTH_SUCCESS Welcome, " + username + "!|TOKEN:" + newToken + "|ROOM:" + defaultRoom);
                    writer.println("AVAILABLE COMMANDS: /join <room_name> - Join/Create chat room :/leave - Leave room&return to default: /listrooms - List all rooms.");
                    writer.println("AVAILABLE BOT COMMAND: @bot + message");
                    writer.flush();

                    return new String[]{username, "false"};
                }
            }

            writer.println("AUTH_FAIL Too many failed login attempts.");
            return new String[]{null, "false"};
        }

        writer.println("AUTH_FAIL Invalid mode (must be 'login' or 'register')");
        return new String[]{null, "false"};
    }

    private void chatLoop(String username, BufferedReader in, PrintWriter writer, Socket clientSocket, String botRoom, boolean isTokenAuth) throws IOException
    {
        if ("AI_Bot".equals(username))
        {
            String line;
            while ((line = in.readLine()) != null)
            {
                if (line.startsWith("[Bot]:"))
                {
                    if (botRoom != null)
                    {
                        ServerRoom room = getOrCreateRoom(botRoom);
                        room.broadcast(line, writer);
                    }
                }
                else if (line.startsWith("/shutdown"))
                {
                    System.out.println("Bot for room " + botRoom + " is shutting down.");
                    break;
                }
            }
            return;
        }

        String deviceFingerprint = getDeviceFingerprint(clientSocket);
        String currentRoomName = tokenManager.getDefaultRoomForFingerprint(deviceFingerprint);

        if (currentRoomName == null || currentRoomName.trim().isEmpty()) {
            currentRoomName = "general";
        }

        ServerRoom currentRoom = getOrCreateRoom(currentRoomName);

        addClientToRoom(currentRoomName, clientSocket, writer);

        writer.println("You have joined room: " + currentRoomName);
        writer.flush();

        if (!isTokenAuth)
        {
            currentRoom.broadcast("[Server] " + username + " has joined the room.", writer);
        }

        List<String> lastMessages = currentRoom.getLastFiveMessages();

        if (!lastMessages.isEmpty())
        {
            for (String msg : lastMessages)
            {
                writer.println(msg);
                writer.flush();
            }
        }

        String line;

        while ((line = in.readLine()) != null)
        {
            if (line.contains("TOKEN:") || (line.length() >= 64 && line.matches("[a-f0-9]{64}")))
            {
                writer.println("[Server] Message blocked for security reasons.");
                writer.flush();

                continue;
            }


            if (line.equalsIgnoreCase("login") || line.equalsIgnoreCase("register"))
            {
                writer.println("[Server] Message blocked to prevent confusion with authentication commands.");
                writer.flush();

                continue;
            }

            if (line.startsWith("/join "))
            {
                String newRoomName = line.substring(6).trim();

                if (!newRoomName.isEmpty())
                {
                    if (!newRoomName.equals(currentRoomName))
                    {
                        synchronized (this)
                        {
                            currentRoom.broadcast("[Server] " + username + " has left the room.", writer);

                            removeClientFromRoom(currentRoomName, clientSocket, writer);

                            String oldRoomName = currentRoomName;
                            currentRoomName = newRoomName;
                            currentRoom = getOrCreateRoom(currentRoomName);

                            tokenManager.updateDefaultRoom(username, getDeviceFingerprint(clientSocket), currentRoomName);

                            addClientToRoom(currentRoomName, clientSocket, writer);

                            writer.println("You have joined room: " + currentRoomName);
                            writer.flush();

                            currentRoom.broadcast("[Server] " + username + " has joined the room.", writer);

                            lastMessages = currentRoom.getLastFiveMessages();

                            if (!lastMessages.isEmpty())
                            {
                                for (String msg : lastMessages)
                                {
                                    writer.println(msg);
                                    writer.flush();
                                }
                            }

                            System.out.println("User " + username + " moved from room " + oldRoomName + " to " + currentRoomName);
                        }
                    }
                    else
                    {
                        writer.println("You're already in that room.");
                        writer.flush();
                    }
                }
                else
                {
                    writer.println("Room name cannot be empty.");
                    writer.flush();
                }
            }
            else if (line.contains("@bot"))
            {
                currentRoom.broadcast(username + ": " + line, writer);

                PrintWriter botWriter = findBotWriter(currentRoomName);

                if (botWriter != null)
                {
                    botWriter.println(line);
                    botWriter.flush();
                }
                else
                {
                    writer.println("[Server] No bot is available in this room.");
                    writer.flush();
                }

                System.out.println("Sending to bot: " + line);
            }
            else if (line.startsWith("/leave"))
            {
                serverRoomsLock.lock();

                try
                {
                    currentRoom.broadcast("[Server] " + username + " has left the room.", writer);

                    removeClientFromRoom(currentRoomName, clientSocket, writer);

                    currentRoomName = "general";
                    currentRoom = getOrCreateRoom(currentRoomName);
                    addClientToRoom(currentRoomName, clientSocket, writer);

                    tokenManager.updateDefaultRoom(username, getDeviceFingerprint(clientSocket), currentRoomName);

                    writer.println("You have left the room and joined the \'general\' room.");

                    lastMessages = currentRoom.getLastFiveMessages();

                    if (!lastMessages.isEmpty())
                    {
                        for (String msg : lastMessages)
                        {
                            writer.println(msg);
                            writer.flush();
                        }
                    }

                    writer.flush();

                    currentRoom.broadcast("[Server] " + username + " has joined the room.", writer);
                }
                finally
                {
                    serverRoomsLock.unlock();
                }
            }
            else if (line.equals("/listrooms"))
            {
                StringBuilder roomList = new StringBuilder("Available rooms: ");
                List<String> sortedRooms = new ArrayList<>(serverRooms.keySet());

                sortedRooms.remove("general");
                Collections.sort(sortedRooms);
                roomList.append("general / ");

                for (String room : sortedRooms)
                {
                    roomList.append(room).append(" / ");
                }

                writer.println(roomList.toString().trim());
                writer.flush();
            }
            else
            {
                System.out.println(username + ": " + line);
                currentRoom.broadcast(username + ": " + line, writer);
            }
        }
    }

    private PrintWriter findBotWriter(String roomName)
    {
        botWritersLock.lock();

        try
        {
            return botWriters.get(roomName);
        }
        finally
        {
            botWritersLock.unlock();
        }
    }


    private void cleanupConnection(Socket clientSocket, PrintWriter writer, String username)
    {
        clientSocketsLock.lock();

        try
        {
            clientSockets.remove(clientSocket);
        }
        finally
        {
            clientSocketsLock.unlock();
        }

        clientWritersLock.lock();
        try
        {
            clientWriters.remove(writer);
        }
        finally
        {
            clientWritersLock.unlock();
        }

        socketFingerprintLock.lock();
        try
        {
            socketToFingerprintMap.remove(clientSocket);
        }
        finally
        {
            socketFingerprintLock.unlock();
        }

        try
        {
            clientSocket.close();
        }
        catch (IOException e)
        {
            System.out.println("Error closing client socket: " + e.getMessage());
        }

        if (username != null && !username.equals("AI_Bot"))
        {
            broadcast("[Server] " + username + " has left the chat.", null);
        }
    }

    private void broadcast(String message, PrintWriter sender)
    {
        clientWritersLock.lock();

        try
        {
            for (PrintWriter writer : clientWriters)
            {
                if (writer != sender)
                {
                    writer.println(message);
                }
            }
        }
        finally
        {
            clientWritersLock.unlock();
        }
    }

    private ServerRoom getOrCreateRoom(String roomName)
    {
        serverRoomsLock.lock();
        try
        {
            boolean roomExists = serverRooms.containsKey(roomName);

            ServerRoom room = serverRooms.computeIfAbsent(roomName, ServerRoom::new);

            if (!roomExists && !"general".equals(roomName))
            {
                System.out.println("New room created: " + roomName + ", spawning AI bot...");

                Thread.ofVirtual().start(() -> {
                    try
                    {
                        AIClient bot = new AIClient("localhost", 8080, "http://localhost:11434", "llama3", roomName);
                        bot.start();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                });

            }
            return room;
        }
        finally
        {
            serverRoomsLock.unlock();
        }
    }


    private void addClientToRoom(String roomName, Socket socket, PrintWriter writer)
    {
        serverRoomsLock.lock();

        try
        {
            ServerRoom room = serverRooms.get(roomName);

            if (room != null)
            {
                room.addClient(socket, writer);
                System.out.println("Client added to room: " + roomName);
            }
        }
        catch (Exception e)
        {
            System.out.println("Error adding client to room " + roomName + ": " + e.getMessage());
            e.printStackTrace();
        }
        finally
        {
            serverRoomsLock.unlock();
        }
    }

    private void removeClientFromRoom(String roomName, Socket socket, PrintWriter writer)
    {
        serverRoomsLock.lock();

        try
        {
            ServerRoom room = serverRooms.get(roomName);

            if (room != null)
            {
                room.removeClient(socket, writer);
                System.out.println("Client removed from room: " + roomName);

                if (room.isEmpty() && !"general".equals(roomName))
                {
                    serverRooms.remove(roomName);
                    botWriters.remove(roomName);
                    System.out.println("Room " + roomName + " is empty, shutting down its AI bot...");

                    final String roomToDisconnect = roomName;
                    new Thread(() -> disconnectBot(roomToDisconnect)).start();
                }
            }
        }
        catch (Exception e)
        {
            System.out.println("Error removing client from room " + roomName + ": " + e.getMessage());
            e.printStackTrace();
        }
        finally
        {
            serverRoomsLock.unlock();
        }
    }

    private void disconnectBot(String roomName)
    {
        try (Socket socket = new Socket("localhost", 8080);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream())))
        {
            out.println("AI_BOT");
            out.println("bot_password");
            out.println(roomName);

            String response = in.readLine();

            if ("AUTH_SUCCESS".equals(response))
            {
                out.println("/shutdown");
            }
        }
        catch (IOException e)
        {
            System.out.println("Failed to disconnect bot for room " + roomName + ": " + e.getMessage());
        }
    }


    public static void main(String[] args)
    {
        if (args.length < 1)
        {
            System.out.println("Usage: java ChatServer <port>");
            return;
        }

        int port = Integer.parseInt(args[0]);

        ChatServer server = new ChatServer(port);

        try
        {
            server.clientAuth.registerClient("charlie", "test123");
            System.out.println("Test users registered: alice, bob");
        }
        catch (Exception e)
        {
            System.out.println("Error registering test users: " + e.getMessage());
            return;
        }

        server.start_server();
    }
}