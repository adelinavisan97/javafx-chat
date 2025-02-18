package com.example.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.*;
import java.net.Socket;
import java.util.Optional;

public class ChatClientApp extends Application {

    private TextArea chatArea;
    private TextField inputField;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String username;

    @Override
    public void start(Stage primaryStage) {
        // Prompt for a username before building the main UI
        username = askForUsername();
        if (username == null || username.isBlank()) {
            System.out.println("No username provided. Exiting...");
            Platform.exit();
            return;
        }

        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);

        inputField = new TextField();
        inputField.setPromptText("Type your message...");

        Button sendButton = new Button("Send");
        sendButton.setOnAction(e -> sendMessage());
        // Also send on Enter key
        inputField.setOnAction(e -> sendMessage());

        HBox inputBox = new HBox(10, inputField, sendButton);
        VBox root = new VBox(10, chatArea, inputBox);
        root.setPrefSize(400, 300);

        Scene scene = new Scene(root);
        primaryStage.setScene(scene);
        primaryStage.setTitle("JavaFX Chat Client - " + username);
        primaryStage.show();

        // Connect to server
        connectToServer("localhost", 12345);
    }

    /**
     * Simple method to prompt user for a username using a JavaFX TextInputDialog.
     */
    private String askForUsername() {
        TextInputDialog dialog = new TextInputDialog("John");
        dialog.setTitle("Set Username");
        dialog.setHeaderText("Choose a username");
        dialog.setContentText("Username:");
        // showAndWait() returns an Optional<String>
        Optional<String> result = dialog.showAndWait();
        return result.orElse(null);
    }

    private void handleIncomingMessage(String rawMessage) {
        // Check if it starts with the local user's name, e.g. "John: "
        String prefix = username + ": ";
        if (rawMessage.startsWith(prefix)) {
            // Rewrite "<username>: Hello" as "You: Hello"
            String withoutName = rawMessage.substring(prefix.length()); // "Hello"
            chatArea.appendText("You: " + withoutName + "\n");
        } else {
            // It's from someone else; show it unchanged
            chatArea.appendText(rawMessage + "\n");
        }
    }

    private void connectToServer(String host, int port) {
        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            // Continuously read messages from server
            Thread readerThread = new Thread(() -> {
                try {
                    String message;
                    while ((message = in.readLine()) != null) {
                        final String msg = message;
                        // Append as is
                        Platform.runLater(() -> handleIncomingMessage(msg));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    showError("Connection lost: " + e.getMessage());
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

        } catch (IOException e) {
            e.printStackTrace();
            showError("Could not connect to server: " + e.getMessage());
        }
    }

    /**
     * Send the contents of inputField to the server, prefixed with the username.
     */
    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        // Send "Username: Message" to the server
        out.println(username + ": " + text);

        inputField.clear();
    }

    private void showError(String message) {
        chatArea.appendText("[Error] " + message + "\n");
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        if (out != null) out.close();
        if (in != null) in.close();
        if (socket != null && !socket.isClosed()) socket.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
