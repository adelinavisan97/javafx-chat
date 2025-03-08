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

    public ChatServer(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        clients = Collections.synchronizedList(new ArrayList<>());
        mongoService = new MongoService(); // connect to Mongo
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
                if (client.getUsername().equalsIgnoreCase(username)) {
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
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
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
     * Handles the authentication flow.
     * For registration: "REGISTER|<fullName>|<email>|<password>"
     * For login:        "LOGIN|<email>|<password>"
     * On success, sends "AUTH_OK|<fullName>"
     */
    private boolean handleAuth() throws IOException {
        String line = in.readLine();
        if (line == null) return false;
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
            String email       = parts[2].trim().toLowerCase();
            String pass        = parts[3].trim();
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
            String email = parts[1].trim().toLowerCase();
            String pass  = parts[2].trim();
            if (server.getMongoService().loginUser(email, pass)) {
                this.username = email;
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

    private void handleClientMessage(String message) {
        String[] parts = message.split("\\|", 4);
        String command = parts[0];
        if (command.equals("NEW_CHAT")) {
            handleNewChat(parts);
        } else if (command.equals("LIST_USER_CONVERSATIONS")) {
            handleListConversations();
        } else if (command.equals("SEND_MESSAGE")) {
            handleSendMessage(parts);
        } else if (command.equals("GET_MESSAGES")) {
            handleGetMessages(parts);
        } else if (command.equals("SEARCH_USERS")) {
            handleSearchUsers(parts);
        }
        // FILE-SHARING COMMANDS
        else if (command.equals("SEND_FILE")) {
            handleSendFile(parts);
        } else if (command.equals("GET_FILE")) {
            handleGetFile(parts);
        }
    }

    private void handleNewChat(String[] parts) {
        if (parts.length < 2) return;
        String recipientEmail = parts[1].toLowerCase();
        if (!server.getMongoService().userExists(recipientEmail)) {
            out.println("CHAT_FAIL|UserNotFound");
            return;
        }
        String conversationId = server.getMongoService().createOrGetConversation(username, recipientEmail);
        String myFullName    = server.getMongoService().getFullName(username);
        String theirFullName = server.getMongoService().getFullName(recipientEmail);
        String myDisplayName = "Conversation with " + (theirFullName != null ? theirFullName : recipientEmail);
        String theirDisplayName = "Conversation with " + (myFullName != null ? myFullName : username);
        server.getMongoService().addConversationToUser(username, conversationId, myDisplayName);
        server.getMongoService().addConversationToUser(recipientEmail, conversationId, theirDisplayName);
        out.println("CHAT_STARTED|" + conversationId);
        ClientHandler recipientHandler = server.getClientByUsername(recipientEmail);
        if (recipientHandler != null) {
            recipientHandler.sendMessage("CHAT_STARTED|" + conversationId);
        }
    }

    private void handleListConversations() {
        List<ConvRef> userConvos = server.getMongoService().getUserConversations(username);
        for (ConvRef c : userConvos) {
            out.println("MY_CONVO|" + c.getConversationId() + "|" + c.getDisplayName());
        }
    }

    private void handleSendMessage(String[] parts) {
        if (parts.length < 3) return;
        String conversationId = parts[1];
        String msgContent = parts[2];
        try {
            String encrypted = CryptoUtil.encrypt(msgContent);
            server.getMongoService().saveMessage(conversationId, username, encrypted);
            String recipientEmail = server.getMongoService().getRecipientFromConversation(conversationId, username);
            ClientHandler recipientHandler = server.getClientByUsername(recipientEmail);
            if (recipientHandler != null) {
                recipientHandler.sendMessage("NEW_MESSAGE|" + fullName + "|" + msgContent);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            out.println("ERROR|Encryption failed on server side");
        }
    }

    private void handleGetMessages(String[] parts) {
        if (parts.length < 2) return;
        String conversationId = parts[1];
        List<String> messages = server.getMongoService().getMessages(conversationId, username);
        for (String msg : messages) {
            out.println("MESSAGE_HISTORY|" + msg);
        }
        // Also send file names from this conversation
        List<String> fileNames = server.getMongoService().getFileNames(conversationId);
        for (String file : fileNames) {
            out.println("NEW_FILE|(existing file)|" + file);
        }
    }

    private void handleSearchUsers(String[] parts) {
        if (parts.length < 2) return;
        String prefix = parts[1].toLowerCase();
        List<UserRecord> results = server.getMongoService().searchUsersByPrefix(prefix);
        for (UserRecord r : results) {
            out.println("USER_RESULT|" + r.getEmail() + "|" + r.getFullName());
        }
    }

    // FILE-SHARING COMMANDS

    private void handleSendFile(String[] parts) {
        if (parts.length < 4) return;
        String conversationId = parts[1];
        String fileName = parts[2];
        String base64Data = parts[3];
        try {
            // Do NOT encrypt file data (for demonstration purposes)
            server.getMongoService().saveFileMessage(conversationId, username, fileName, base64Data);
            String recipientEmail = server.getMongoService().getRecipientFromConversation(conversationId, username);
            ClientHandler recipientHandler = server.getClientByUsername(recipientEmail);
            if (recipientHandler != null) {
                recipientHandler.sendMessage("NEW_FILE|" + fullName + "|" + fileName);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            out.println("ERROR|File processing failed on server side");
        }
    }

    private void handleGetFile(String[] parts) {
        if (parts.length < 3) return;
        String conversationId = parts[1];
        String requestedFile = parts[2];
        String fileData = server.getMongoService().fetchFileBase64(conversationId, requestedFile);
        if (fileData == null) {
            out.println("FILE_DATA|" + requestedFile + "|NOT_FOUND");
        } else {
            try {
                out.println("FILE_DATA|" + requestedFile + "|" + fileData);
            } catch (Exception e) {
                e.printStackTrace();
                out.println("FILE_DATA|" + requestedFile + "|ERROR");
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
