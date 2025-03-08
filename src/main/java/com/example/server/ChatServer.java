package com.example.server;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ChatServer {
    private ServerSocket serverSocket;
    private final List<ClientHandler> clients;
    private MongoService mongoService;

    // Constructor
    public ChatServer(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        clients = Collections.synchronizedList(new ArrayList<>());
        mongoService = new MongoService(); // Connect to Mongo

        System.out.println("Server started on port " + port);
    }

    public MongoService getMongoService() {
        return mongoService;
    }

    public void start() {
        while (true) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected: " + clientSocket.getInetAddress());

                ClientHandler handler = new ClientHandler(clientSocket, this);
                clients.add(handler);

                new Thread(handler).start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // Find a specific client based on username (email)
    public ClientHandler getClientByUsername(String username) {
        synchronized (clients) {
            for (ClientHandler client : clients) {
                if (client.getUsername().equals(username)) {
                    return client;
                }
            }
        }
        return null;
    }

    // Remove client if disconnected
    public void removeClient(ClientHandler clientHandler) {
        clients.remove(clientHandler);
        System.out.println("A client disconnected. Current client count: " + clients.size());
    }

    public static void main(String[] args) {
        try {
            ChatServer server = new ChatServer(12345);
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class ClientHandler implements Runnable {
    private Socket socket;
    private ChatServer server;
    private PrintWriter out;
    private BufferedReader in;
    private String username; // email used for authentication
    private String fullName; // full name for display

    public ClientHandler(Socket socket, ChatServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Authentication flow
            if (!handleAuth()) {
                closeConnections();
                return;
            }

            String message;
            while ((message = in.readLine()) != null) {
                handleClientMessage(message);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            server.removeClient(this);
            closeConnections();
        }
    }

    /**
     * Reads the first line from the client.
     * For registration, expects:
     *   "REGISTER|<fullName>|<email>|<password>"
     * For login, expects:
     *   "LOGIN|<email>|<password>"
     * On success, sends back "AUTH_OK|<fullName>"
     */
    private boolean handleAuth() throws IOException {
        String line = in.readLine();
        if (line == null) {
            return false;
        }

        String[] parts = line.split("\\|");
        if (parts.length < 3) {
            out.println("AUTH_FAIL|Missing parts");
            return false;
        }

        String command = parts[0].toUpperCase();
        if (command.equals("REGISTER")) {
            if (parts.length != 4) {
                out.println("AUTH_FAIL|Incorrect registration format");
                return false;
            }
            String regFullName = parts[1].trim();
            String email = parts[2].trim();
            String pass = parts[3].trim();

            if (server.getMongoService().registerUser(email, pass, regFullName)) {
                this.username = email;
                this.fullName = regFullName;
                out.println("AUTH_OK|" + regFullName);
                return true;
            } else {
                out.println("AUTH_FAIL|Username exists");
                return false;
            }
        } else if (command.equals("LOGIN")) {
            if (parts.length != 3) {
                out.println("AUTH_FAIL|Incorrect login format");
                return false;
            }
            String email = parts[1].trim();
            String pass = parts[2].trim();

            if (server.getMongoService().loginUser(email, pass)) {
                this.username = email.toLowerCase();
                this.fullName = server.getMongoService().getFullName(email);
                out.println("AUTH_OK|" + fullName);
                return true;
            } else {
                out.println("AUTH_FAIL|Invalid credentials");
                return false;
            }
        } else {
            out.println("AUTH_FAIL|Unknown command");
            return false;
        }
    }

    /**
     * Handles messages received from the client.
     * - "NEW_CHAT|<recipientEmail>"
     * - "SEND_MESSAGE|<conversationId>|<message>"
     */
    private void handleClientMessage(String message) {
        String[] parts = message.split("\\|", 3);
        String command = parts[0];

        if (command.equals("NEW_CHAT")) {
            String recipientEmail = parts[1].trim();

            // 1) Check if this user actually exists
            if (!server.getMongoService().userExists(recipientEmail)) {
                // If not found, inform the sender client
                out.println("CHAT_FAIL|UserNotFound");
                return; // do not create a conversation
            }

            // 2) If user does exist, proceed
            String conversationId = server.getMongoService().createOrGetConversation(username, recipientEmail);
            out.println("CHAT_STARTED|" + conversationId);
        } else if (command.equals("SEND_MESSAGE")) {
            if (parts.length < 3) return;
            String conversationId = parts[1];
            String msgContent = parts[2];

            try {
                // Encrypt message (throws Exception)
                String encryptedMessage = CryptoUtil.encrypt(msgContent);

                // Store the encrypted message in MongoDB
                server.getMongoService().saveMessage(conversationId, username, encryptedMessage);

                // Send a real-time notification to the recipient if they are online
                String recipientEmail = server.getMongoService().getRecipientFromConversation(conversationId, username);
                ClientHandler recipientHandler = server.getClientByUsername(recipientEmail);
                if (recipientHandler != null) {
                    // We send the plain text back to recipient, or you can send the encrypted if you prefer
                    recipientHandler.sendMessage("NEW_MESSAGE|" + fullName + "|" + msgContent);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                // Optionally notify the sender of the error:
                out.println("ERROR|Encryption failed on server side.");
            }

        } else if (command.equals("GET_MESSAGES")) {
            String conversationId = parts[1];
            List<String> messages = server.getMongoService().getMessages(conversationId, username);
            for (String line : messages) {
                out.println("MESSAGE_HISTORY|" + line);
            }
        } else if (command.equals("LIST_CONVERSATIONS")) {
            System.out.println("[DEBUG] LIST_CONVERSATIONS for: " + username);
            List<String> userConversations = server.getMongoService().getAllConversationsForUser(username.toLowerCase());
            System.out.println("[DEBUG] Found these convos: " + userConversations);
            for (String conversationId : userConversations) {
                out.println("CONVERSATION|" + conversationId);
            }
        }
    }

    public void sendMessage(String msg) {
        out.println(msg);
    }

    public String getUsername() {
        return username;
    }

    private void closeConnections() {
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
