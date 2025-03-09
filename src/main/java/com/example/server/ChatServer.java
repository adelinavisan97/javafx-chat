package com.example.server;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ChatServer {
    private ServerSocket serverSocket;
    private final List<ClientHandler> clients;
    private final MongoService mongoService;

    public ChatServer(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        clients = Collections.synchronizedList(new ArrayList<>());
        mongoService = new MongoService();
        System.out.println("Server started on port " + port);
    }

    public MongoService getMongoService() {
        return mongoService;
    }

    public void start() {
        while (true) {
            try {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket, this);
                clients.add(handler);
                new Thread(handler).start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

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

    public void removeClient(ClientHandler clientHandler) {
        clients.remove(clientHandler);
        System.out.println("Client disconnected. Current client count: " + clients.size());
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
    private final Socket socket;
    private final ChatServer server;
    private PrintWriter out;
    private BufferedReader in;
    private String username;
    private String fullName;

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
            // Minimal logging
            e.printStackTrace();
        } finally {
            server.removeClient(this);
            closeConnections();
        }
    }

    private boolean handleAuth() throws IOException {
        String line = in.readLine();
        if (line == null) return false;
        String[] parts = line.split("\\|");
        if (parts.length < 3) {
            out.println("AUTH_FAIL|Missing parts");
            return false;
        }
        String command = parts[0].toUpperCase();
        if ("REGISTER".equals(command)) {
            if (parts.length != 4) {
                out.println("AUTH_FAIL|Incorrect registration format");
                return false;
            }
            return handleRegister(parts[1], parts[2], parts[3]);
        } else if ("LOGIN".equals(command)) {
            if (parts.length != 3) {
                out.println("AUTH_FAIL|Incorrect login format");
                return false;
            }
            return handleLogin(parts[1], parts[2]);
        } else {
            out.println("AUTH_FAIL|Unknown command");
            return false;
        }
    }

    private boolean handleRegister(String fullNameInput, String email, String password) {
        email = email.toLowerCase().trim();
        if (server.getMongoService().registerUser(email, password, fullNameInput)) {
            this.username = email;
            this.fullName = fullNameInput;
            out.println("AUTH_OK|" + this.fullName);
            return true;
        } else {
            out.println("AUTH_FAIL|Username exists");
            return false;
        }
    }

    private boolean handleLogin(String emailInput, String passwordInput) {
        String email = emailInput.toLowerCase().trim();
        if (server.getMongoService().loginUser(email, passwordInput)) {
            this.username = email;
            this.fullName = server.getMongoService().getFullName(email);
            out.println("AUTH_OK|" + this.fullName);
            return true;
        } else {
            out.println("AUTH_FAIL|Invalid credentials");
            return false;
        }
    }

    private void handleClientMessage(String message) {
        String[] parts = message.split("\\|", 4);
        String command = parts[0];
        switch (command) {
            case "NEW_CHAT":
                handleNewChat(parts);
                break;
            case "LIST_USER_CONVERSATIONS":
                handleListConversations();
                break;
            case "SEND_MESSAGE":
                handleSendMessage(parts);
                break;
            case "GET_MESSAGES":
                handleGetMessages(parts);
                break;
            case "SEARCH_USERS":
                handleSearchUsers(parts);
                break;
            case "SEND_FILE":
                handleSendFile(parts);
                break;
            case "GET_FILE":
                handleGetFile(parts);
                break;
            case "GET_FILES":
                handleGetFiles(parts);
                break;
            default:
                break;
        }
    }

    private void handleNewChat(String[] parts) {
        if (parts.length < 2) return;
        String recipientEmail = parts[1].toLowerCase().trim();
        if (!server.getMongoService().userExists(recipientEmail)) {
            out.println("CHAT_FAIL|UserNotFound");
            return;
        }
        String conversationId = server.getMongoService().createOrGetConversation(username, recipientEmail);
        String myFullName = server.getMongoService().getFullName(username);
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
        for (ConvRef convo : userConvos) {
            out.println("MY_CONVO|" + convo.getConversationId() + "|" + convo.getDisplayName());
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
            out.println("ERROR|Encryption failed on server side");
        }
    }

    private void handleGetMessages(String[] parts) {
        if (parts.length < 2) return;
        String conversationId = parts[1];
        // Send complete conversation history (file messages are included once)
        for (String msg : server.getMongoService().getMessages(conversationId, username)) {
            out.println("MESSAGE_HISTORY|" + msg);
        }
    }

    private void handleSearchUsers(String[] parts) {
        if (parts.length < 2) return;
        String prefix = parts[1].toLowerCase();
        for (UserRecord r : server.getMongoService().searchUsersByPrefix(prefix)) {
            out.println("USER_RESULT|" + r.getEmail() + "|" + r.getFullName());
        }
    }

    // ----- File Sharing Commands -----
    private void handleSendFile(String[] parts) {
        if (parts.length < 4) return;
        String conversationId = parts[1];
        String fileName = parts[2];
        String base64Data = parts[3];
        try {
            String encryptedFileData = CryptoUtil.encrypt(base64Data);
            // Save file message with summary text: "<SenderFullName> shared a file: <fileName>"
            server.getMongoService().saveFileMessage(conversationId, username, fileName, encryptedFileData, fullName);
            // Notify the recipient
            String recipientEmail = server.getMongoService().getRecipientFromConversation(conversationId, username);
            ClientHandler recipientHandler = server.getClientByUsername(recipientEmail);
            if (recipientHandler != null) {
                recipientHandler.sendMessage("NEW_FILE|" + fullName + "|" + fileName);
            }
            // No duplicate notification to sender – the sender will see the stored message when reloading.
        } catch (Exception ex) {
            out.println("ERROR|File encryption failed");
        }
    }

    private void handleGetFile(String[] parts) {
        if (parts.length < 3) return;
        String conversationId = parts[1];
        String requestedFile = parts[2];
        String encryptedFileData = server.getMongoService().fetchFileBase64(conversationId, requestedFile);
        if (encryptedFileData == null) {
            out.println("FILE_DATA|" + requestedFile + "|NOT_FOUND");
        } else {
            try {
                // Decrypt the stored file data before sending to the client.
                String plainBase64 = CryptoUtil.decrypt(encryptedFileData);
                out.println("FILE_DATA|" + requestedFile + "|" + plainBase64);
            } catch (Exception e) {
                e.printStackTrace();
                out.println("FILE_DATA|" + requestedFile + "|ERROR");
            }
        }
    }


    // New command to get the list of files for a conversation.
    private void handleGetFiles(String[] parts) {
        if (parts.length < 2) return;
        String conversationId = parts[1];
        for (String file : server.getMongoService().getFileNames(conversationId)) {
            out.println("FILE_LIST|" + file);
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
