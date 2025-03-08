package com.example.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.*;
import java.net.Socket;
import java.util.Optional;

public class ChatClientApp extends Application {

    private Stage primaryStage;

    // Scenes
    private Scene loginScene;
    private Scene conversationsScene; // listing user’s conversations
    private Scene chatScene;          // a single conversation’s chat

    // For conversation listing
    private ListView<String> conversationListView;

    // For chat UI
    private TextArea chatArea;
    private TextField inputField;

    // Networking
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    // User Info
    private String username;    // user's email
    private String displayName; // user's full name from server

    // Current conversation
    private String currentConversationId;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        // Build Scenes
        loginScene = buildLoginScene();
        conversationsScene = buildConversationsScene();

        // Start with the login scene
        primaryStage.setScene(loginScene);
        primaryStage.setTitle("Chat Login");
        primaryStage.show();
    }

    /**
     * 1) LOGIN SCENE
     * Allows user to pick: Login or Register
     */
    private Scene buildLoginScene() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);

        Label titleLabel = new Label("Welcome to ChatApp");
        Button loginButton = new Button("Login");
        Button registerButton = new Button("Register");

        loginButton.setOnAction(e -> handleLogin());
        registerButton.setOnAction(e -> handleRegistration());

        HBox buttonBox = new HBox(10, loginButton, registerButton);
        buttonBox.setAlignment(Pos.CENTER);

        root.getChildren().addAll(titleLabel, buttonBox);

        return new Scene(root, 400, 300);
    }

    /**
     * 2) CONVERSATIONS SCENE
     * Lists existing conversation IDs + allows new chat creation
     */
    private Scene buildConversationsScene() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        Label header = new Label("Your Conversations");
        header.setStyle("-fx-font-size: 16px;");

        conversationListView = new ListView<>();

        // Button: new chat
        Button newChatBtn = new Button("New Chat");
        newChatBtn.setOnAction(e -> showNewChatDialog());

        // Button: open chat
        Button openChatBtn = new Button("Open Chat");
        openChatBtn.setOnAction(e -> openSelectedConversation());

        // Logout
        Button logoutBtn = new Button("Logout");
        logoutBtn.setOnAction(e -> handleLogout());

        HBox topBar = new HBox(10, newChatBtn, openChatBtn, logoutBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);

        VBox topSection = new VBox(10, header, topBar);
        topSection.setPadding(new Insets(5));

        root.setTop(topSection);
        root.setCenter(conversationListView);

        return new Scene(root, 500, 400);
    }

    /**
     * 3) CHAT SCENE
     * For a single conversation
     */
    private Scene buildChatScene() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);

        inputField = new TextField();
        inputField.setPromptText("Type your message...");

        Button sendButton = new Button("Send");
        sendButton.setOnAction(e -> sendMessage());
        inputField.setOnAction(e -> sendMessage());

        Button backBtn = new Button("Back to Conversations");
        backBtn.setOnAction(e -> {
            // Return to the conversation list scene
            primaryStage.setScene(conversationsScene);
            primaryStage.setTitle("Conversations - " + displayName);
        });

        HBox inputBox = new HBox(10, inputField, sendButton);
        inputBox.setPadding(new Insets(5));

        VBox topBar = new VBox(5, backBtn);

        VBox mainBox = new VBox(10, chatArea, inputBox);
        mainBox.setPadding(new Insets(10));

        root.setTop(topBar);
        root.setCenter(mainBox);

        return new Scene(root, 500, 400);
    }

    /**
     * Show a dialog to create a new chat with another user
     */
    private void showNewChatDialog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New Chat");
        dialog.setHeaderText("Enter the email of the user you want to chat with:");
        dialog.setContentText("Email:");

        Optional<String> result = dialog.showAndWait();
        if (result.isPresent()) {
            String otherEmail = result.get().trim();
            if (!otherEmail.isEmpty()) {
                // Send "NEW_CHAT|otherEmail"
                out.println("NEW_CHAT|" + otherEmail);
                // The server's response "CHAT_STARTED|<conversationId>" is handled in handleServerLine()
            }
        }
    }

    /**
     * open the selected conversation in conversationListView
     */
    private void openSelectedConversation() {
        String selected = conversationListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert("Please select a conversation first.");
            return;
        }
        currentConversationId = selected;

        // Build and set the chat scene
        chatScene = buildChatScene();
        primaryStage.setScene(chatScene);
        primaryStage.setTitle("Conversation: " + currentConversationId);

        // Request messages from server
        out.println("GET_MESSAGES|" + currentConversationId);
    }

    /**
     * After successful login or register, we load the conversation list
     */
    private void loadConversationsList() {
        conversationListView.getItems().clear();
        out.println("LIST_CONVERSATIONS"); // server returns "CONVERSATION|<id>" lines
    }

    /**
     * handleLogin() flow
     */
    private void handleLogin() {
        String email = askForCredential("Email:", "john@example.com");
        if (email == null || email.isBlank()) {
            showAlert("No email provided.");
            return;
        }
        String password = askForPassword("Enter Password:");
        if (password == null || password.isBlank()) {
            showAlert("No password provided.");
            return;
        }

        // connect & authenticate
        if (connectAndAuthenticate("localhost", 12345, "LOGIN", "", email, password)) {
            username = email;
            primaryStage.setScene(conversationsScene);
            primaryStage.setTitle("Conversations - " + displayName);
            loadConversationsList();
        } else {
            showAlert("Authentication failed. Please try again.");
        }
    }

    /**
     * handleRegistration() flow
     */
    private void handleRegistration() {
        RegistrationData regData = askForRegistrationData();
        if (regData == null) {
            showAlert("Registration canceled.");
            return;
        }
        if (connectAndAuthenticate("localhost", 12345, "REGISTER",
                regData.getFullName(), regData.getEmail(), regData.getPassword())) {
            username = regData.getEmail();
            primaryStage.setScene(conversationsScene);
            primaryStage.setTitle("Conversations - " + displayName);
            loadConversationsList();
        } else {
            showAlert("Registration failed. Please try again.");
        }
    }

    /**
     * handleLogout
     */
    private void handleLogout() {
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        username = null;
        displayName = null;
        Platform.runLater(() -> {
            primaryStage.setScene(buildLoginScene());
            // Clear window title
            primaryStage.setTitle("Chat Login");
        });
    }

    /**
     * connectAndAuthenticate
     */
    private boolean connectAndAuthenticate(String host, int port, String authMode,
                                           String fullName, String userOrEmail, String pass) {
        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            if (authMode.equals("REGISTER")) {
                out.println("REGISTER|" + fullName + "|" + userOrEmail + "|" + pass);
                username = userOrEmail;
            } else { // LOGIN
                out.println("LOGIN|" + userOrEmail + "|" + pass);
                username = userOrEmail;
            }

            String response = in.readLine();
            if (response == null) {
                System.err.println("Server closed connection unexpectedly.");
                return false;
            }
            if (response.startsWith("AUTH_OK")) {
                // e.g. "AUTH_OK|<fullName>"
                String[] parts = response.split("\\|", 2);
                if (parts.length == 2) {
                    displayName = parts[1];
                    System.out.println("Authentication successful! Display Name: " + displayName);
                }
                // start reading in background
                startReaderThread();
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
     * read lines from server, parse them
     */
    private void startReaderThread() {
        Thread readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    final String serverLine = line;
                    Platform.runLater(() -> handleServerLine(serverLine));
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
     * handle lines from server:
     * - "CONVERSATION|<id>"
     * - "CHAT_STARTED|<id>"
     * - "MESSAGE_HISTORY|sender: text"
     * - "NEW_MESSAGE|<senderFullName>|<plainText>"
     */
    private void handleServerLine(String line) {
        System.out.println("Server => " + line);

        String[] parts = line.split("\\|", 3);
        String command = parts[0];

        switch (command) {
            case "CONVERSATION": {
                if (parts.length >= 2) {
                    String convId = parts[1];
                    System.out.println("Got conversation ID: " + convId);
                    conversationListView.getItems().add(convId);
                }
                break;
            }
            case "CHAT_STARTED": {
                // "CHAT_STARTED|<id>"
                if (parts.length >= 2) {
                    String convId = parts[1];
                    conversationListView.getItems().add(convId);
                    showAlert("New conversation started: " + convId);
                }
                break;
            }
            case "MESSAGE_HISTORY": {
                // "MESSAGE_HISTORY|sender: text"
                if (parts.length >= 2 && chatArea != null) {
                    String lineOfHistory = parts[1];  // "Alice: Hello"
                    chatArea.appendText(lineOfHistory + "\n");
                }
                break;
            }
            case "NEW_MESSAGE": {
                // "NEW_MESSAGE|senderFullName|plainText"
                if (parts.length == 3 && chatArea != null) {
                    String senderName = parts[1];
                    String plainText = parts[2];
                    chatArea.appendText(senderName + ": " + plainText + "\n");
                }
                break;
            }
            default:
                // Possibly handle other commands or ignore
                break;
        }
    }

    /**
     * send a message in the current conversation
     */
    private void sendMessage() {
        if (currentConversationId == null) {
            showAlert("No conversation is opened.");
            return;
        }
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        // Send to server
        out.println("SEND_MESSAGE|" + currentConversationId + "|" + text);

        // Local echo
        chatArea.appendText("You: " + text + "\n");
        inputField.clear();
    }

    // ------------------------------------------------------------------------
    // SUPPORTING METHODS: askForCredential, askForPassword, askForRegistrationData
    // ------------------------------------------------------------------------

    /**
     * Generic prompt for a text field.
     * @param label The label or prompt
     * @param defaultVal The default value to show in the dialog
     * @return The string the user entered, or null if they canceled
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
     * Prompt the user for a password (masked input).
     * @param prompt The dialog header text
     * @return The password typed or null if canceled
     */
    private String askForPassword(String prompt) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Password");
        dialog.setHeaderText(prompt);

        ButtonType okButtonType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okButtonType, ButtonType.CANCEL);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        grid.add(new Label("Password:"), 0, 0);
        grid.add(passwordField, 1, 0);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == okButtonType) {
                return passwordField.getText();
            }
            return null;
        });

        Optional<String> result = dialog.showAndWait();
        return result.orElse(null);
    }

    /**
     * Show a registration dialog for full name, email, password
     */
    private RegistrationData askForRegistrationData() {
        Dialog<RegistrationData> dialog = new Dialog<>();
        dialog.setTitle("Register");
        dialog.setHeaderText("Enter your details to register");

        ButtonType registerButtonType = new ButtonType("Register", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(registerButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        TextField nameField = new TextField();
        nameField.setPromptText("First and Last Name");
        TextField emailField = new TextField();
        emailField.setPromptText("Email");

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");

        // Optional: show/hide password with a checkbox
        TextField visiblePasswordField = new TextField();
        visiblePasswordField.setManaged(false);
        visiblePasswordField.setVisible(false);
        visiblePasswordField.textProperty().bindBidirectional(passwordField.textProperty());

        CheckBox showPasswordCheckBox = new CheckBox("Show Password");
        showPasswordCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                visiblePasswordField.setManaged(true);
                visiblePasswordField.setVisible(true);
                passwordField.setManaged(false);
                passwordField.setVisible(false);
            } else {
                visiblePasswordField.setManaged(false);
                visiblePasswordField.setVisible(false);
                passwordField.setManaged(true);
                passwordField.setVisible(true);
            }
        });

        grid.add(new Label("Full Name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Email:"), 0, 1);
        grid.add(emailField, 1, 1);
        grid.add(new Label("Password:"), 0, 2);
        grid.add(passwordField, 1, 2);
        grid.add(visiblePasswordField, 1, 2);
        grid.add(showPasswordCheckBox, 1, 3);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == registerButtonType) {
                return new RegistrationData(nameField.getText(), emailField.getText(), passwordField.getText());
            }
            return null;
        });

        Optional<RegistrationData> result = dialog.showAndWait();
        return result.orElse(null);
    }

    // ------------------------------------------------------------------------
    // UTILITY: Show an informational alert
    // ------------------------------------------------------------------------
    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Notification");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    /**
     * Show an error in the chatArea if it exists, or to stderr if not
     */
    private void showError(String message) {
        if (chatArea != null) {
            chatArea.appendText("[Error] " + message + "\n");
        } else {
            System.err.println("ERROR: " + message);
        }
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        if (out != null) out.close();
        if (in != null) in.close();
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
