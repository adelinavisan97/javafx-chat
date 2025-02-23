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
    private String username; // store after successful login

    public ClientHandler(Socket socket, ChatServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // 1. Authentication flow
            if (!handleAuth()) {
                // If auth fails, close connection
                closeConnections();
                return;
            }

            // 2. If we get here, user is authenticated
            // We enter the main chat loop
            String message;
            while ((message = in.readLine()) != null) {
                // broadcast, store in DB, etc.
                server.broadcast(username + ": " + message);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            server.removeClient(this);
            closeConnections();
        }
    }

    /**
     * Reads the first line from the client, expecting:
     *   "REGISTER <username> <password>"
     * or "LOGIN <username> <password>"
     * If successful, we set this.username and return true.
     */
    private boolean handleAuth() throws IOException {
        String line = in.readLine();
        if (line == null) {
            return false;
        }
        String[] parts = line.split("\\s+", 3);
        if (parts.length < 3) {
            out.println("AUTH_FAIL Missing parts");
            return false;
        }
        String command = parts[0].toUpperCase();
        String user = parts[1];
        String pass = parts[2];

        if (command.equals("REGISTER")) {
            if (server.getMongoService().registerUser(user, pass)) {
                out.println("AUTH_OK");
                this.username = user;
                return true;
            } else {
                out.println("AUTH_FAIL Username exists");
                return false;
            }
        } else if (command.equals("LOGIN")) {
            if (server.getMongoService().loginUser(user, pass)) {
                out.println("AUTH_OK");
                this.username = user;
                return true;
            } else {
                out.println("AUTH_FAIL Invalid credentials");
                return false;
            }
        } else {
            out.println("AUTH_FAIL Unknown command");
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
