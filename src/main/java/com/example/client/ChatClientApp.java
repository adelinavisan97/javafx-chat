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
        // Prompt user: choose Login or Register
        String authMode = askLoginOrRegister();
        if (authMode == null) {
            System.out.println("User canceled login/register. Exiting...");
            Platform.exit();
            return;
        }

        // Prompt for username & password
        username = askForCredential("Username:", "John");
        if (username == null || username.isBlank()) {
            System.out.println("No username provided. Exiting...");
            Platform.exit();
            return;
        }
        String password = askForCredential("Password:", "");
        if (password == null || password.isBlank()) {
            System.out.println("No password provided. Exiting...");
            Platform.exit();
            return;
        }

        // Connect to server & send LOGIN or REGISTER command
        if (!connectAndAuthenticate("localhost", 12345, authMode, username, password)) {
            // If auth fails, exit or do something else
            Platform.exit();
            return;
        }

        // Build the Chat UI
        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);

        inputField = new TextField();
        inputField.setPromptText("Type your message...");

        Button sendButton = new Button("Send");
        sendButton.setOnAction(e -> sendMessage());
        inputField.setOnAction(e -> sendMessage());

        HBox inputBox = new HBox(10, inputField, sendButton);
        VBox root = new VBox(10, chatArea, inputBox);
        root.setPrefSize(400, 300);

        Scene scene = new Scene(root);
        primaryStage.setScene(scene);
        primaryStage.setTitle("JavaFX Chat Client - " + username);
        primaryStage.show();

        // Start reading messages in background thread
        startReaderThread();
    }

    /**
     *  Ask user: do you want to LOGIN or REGISTER?
     */
    private String askLoginOrRegister() {
        ChoiceDialog<String> dialog = new ChoiceDialog<>("LOGIN", "LOGIN", "REGISTER");
        dialog.setTitle("Login or Register");
        dialog.setHeaderText("Choose an action");
        dialog.setContentText("Select one:");
        Optional<String> result = dialog.showAndWait();
        return result.orElse(null);
    }

    /**
     *  Prompt user for either username or password using a TextInputDialog.
     */
    private String askForCredential(String label, String defaultVal) {
        TextInputDialog dialog = new TextInputDialog(defaultVal);
        dialog.setTitle("Enter " + label);
        dialog.setHeaderText(label);
        dialog.setContentText(label + ":");
        Optional<String> result = dialog.showAndWait();
        return result.orElse(null);
    }

    /**
     * Attempt to connect to the server and perform either LOGIN or REGISTER.
     * Returns true if AUTH_OK, or false if fail.
     */
    private boolean connectAndAuthenticate(String host, int port,
                                           String authMode, String user, String pass) {
        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            // Send "LOGIN <user> <pass>" or "REGISTER <user> <pass>" as first line
            out.println(authMode + " " + user + " " + pass);

            // Wait for response from server
            String response = in.readLine();
            if (response == null) {
                System.err.println("Server closed connection unexpectedly.");
                return false;
            }
            if (response.startsWith("AUTH_OK")) {
                System.out.println("Authentication successful!");
                return true;
            } else {
                System.err.println("Authentication failed: " + response);
                return false;
            }

        } catch (IOException e) {
            e.printStackTrace();
            showError("Could not connect to server: " + e.getMessage());
            return false;
        }
    }

    /**
     *  Once authenticated, start a background thread to read messages.
     */
    private void startReaderThread() {
        Thread readerThread = new Thread(() -> {
            try {
                String message;
                while ((message = in.readLine()) != null) {
                    final String msg = message;
                    Platform.runLater(() -> handleIncomingMessage(msg));
                }
            } catch (IOException e) {
                e.printStackTrace();
                showError("Connection lost: " + e.getMessage());
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     *  If the incoming message starts with "<username>:", rewrite to "You:".
     */
    private void handleIncomingMessage(String rawMessage) {
        String prefix = username + ": ";
        if (rawMessage.startsWith(prefix)) {
            String withoutName = rawMessage.substring(prefix.length());
            chatArea.appendText("You: " + withoutName + "\n");
        } else {
            chatArea.appendText(rawMessage + "\n");
        }
    }

    /**
     *  Send a normal chat message with "<username>: <text>" format.
     */
    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        out.println(text);
        inputField.clear();
    }

    private void showError(String message) {
        // We can also show a dialog, but for simplicity just append to chatArea
        Platform.runLater(() -> chatArea.appendText("[Error] " + message + "\n"));
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
