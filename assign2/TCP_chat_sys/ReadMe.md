# DISTRIBUTED SYSTEMS ASSIGNMENT
<h2 align="center">This project was made by</h2>
<h3 align="center">GRUPO T11_G11</h3>

<div align="center">

| Nome            | Número de Estudante | Participation |
|-----------------|---------------------|---------------|
| Carlos Costa    | 202205908           | 25%           | 
| Luciano Ferreira| 202208158           | 50%           |
| Tomás Telmo     | 202206091           | 25%           | 

</div>

## How to run

### Prerequisites
- Java 21 or higher installed.

### Step 1: Make sure you have Docker installed
First, you need to make sure you have Docker installed. Run the following command:
```bash
docker --version
```

If you don´t have Docker, make sure to install it.

### Step 2: Download the Docker image for Ollama
```bash
docker run -d --name ollama -p 11434:11434 ollama/ollama
```
### Step 3: Start the Ollama container
```bash
docker exec ollama ollama pull llama3
```

### Step 4: Go to the TCP_chat_sys repository
Assuming you are at the base directory of the group's repository, run the following command:
```bash
cd assign2/TCP_chat_sys
```

### Step 5: Launch the Chat System
In the terminal where you want to launch the chat system, run the launcher:
```bash
java -cp out ChatSystemLauncher
```

### Step 6: Join as a Client

In another terminal, you can join the system by running:
```bash
java -cp out ChatClient
```

**Initially there must not have the files named**: 
- device_id.txt
- auth_token.txt
- user_tokens.dat


# How to:
## Join as a Client
When prompted, enter:
- Server IP (e.g. localhost or 192.168.1.100, basically the same as the server)
- Port 8080 (current port used by the server)

Next the client will:
- Connect to the specified server
- Start the login process (via username/token)
- Join the default room and listen for messages

## Share messages and use commands
Users can send messages freely like:
- Hello!
- How are you people

Users can also use the following commands in the chat:
- `/join <room_name>` - Join or create a chat room
- `/leave` - Leave current room and return to the general room
- `/listrooms` - Display all available chat rooms
- `@bot + message` - Interact with the AI assistant in the current room


# Project Overview
## Chat Server
### Overview
This project implements a secure multi room chat server using SSL for encrypted communication. The server supports user authentication, persistent tokens, room management, and AI bot integration.

### Features

- Uses SSL for encrypted client-server communication
- Supports both registration and login mechanisms
- Remembers client devices via unique fingerprints for easier login
- Users can create and join different chat rooms
- Provides last five messages when joining a room
- Each room can have an AI assistant powered by LLama3
- Leverages Java's virtual threads for efficient concurrent client handling

### Implementation Notes
- The server automatically spawns AI bots when new rooms are created
- Empty rooms (except the general room) are automatically cleaned up
- The server maintains connection with room-specific AI bots
- Token persistence is maintained between server restarts

## Chat Client
### Overview
This project implements a secure chat client that connects to a chat server over SSL. The client supports user authentication, room management, and persistent device identification.

### Features
- Uses SSL for encrypted communication with the server
- Supports both login and registration
- Maintains authentication tokens for persistent login
- Identifies client devices using a unique fingerprint
- Automatically attempts to reconnect on connection failure

### Implementation Notes
- Written in Java with support for virtual threads
- Thread synchronization via java.util.concurrent.locks
- Secure device identification using SHA-256 hashing
- The client implements a token-based authentication system that eliminates the need to provide credentials on each connection.
- The client creates two files for persistence:
    - device_id.txt - Stores your unique device identifier
    - auth_token.txt - Saves your authentication token for automatic login

## AI Chat Bot Client
### Overview
This project implements an AI chatbot client that connects to a chat server, monitors conversations, and responds to mentions using an Ollama-powered AI model.<br>
Currently using ollama3, it might take some time to give an answer around 10s.

### Features
- Secure SSL connection to chat server
- Bot authentication and room management
- AI-powered responses using Ollama API
- Conversation context awareness
- Heartbeat mechanism for reliable connections
- Thread-safe operations using Java locks

### Implementation Notes
- Uses Java virtual threads for efficient concurrency
- Thread synchronization via java.util.concurrent.locks
- Cooldown period of 3 seconds between responses
- 30-second heartbeat interval for connection monitoring