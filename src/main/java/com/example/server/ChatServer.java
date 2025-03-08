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

    // Broadcast message to all clients
    public void broadcast(String message) {
        synchronized (clients) {
            for (ClientHandler client : clients) {
                client.sendMessage(message);
            }
        }
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

            // Main chat loop: broadcast incoming messages with the full name as prefix.
            String message;
            while ((message = in.readLine()) != null) {
                server.broadcast(fullName + ": " + message);
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
        // Split using pipe as delimiter
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
                // Set both username and fullName
                this.username = email;
                this.fullName = regFullName;
                // Send the full name back to the client
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
                // Retrieve full name from the database
                String retrievedFullName = server.getMongoService().getFullName(email);
                if (retrievedFullName == null) {
                    retrievedFullName = email; // fallback
                }
                this.username = email;
                this.fullName = retrievedFullName;
                out.println("AUTH_OK|" + retrievedFullName);
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

    public void sendMessage(String msg) {
        out.println(msg);
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

