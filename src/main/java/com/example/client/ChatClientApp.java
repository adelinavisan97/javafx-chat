package com.example.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Optional;

public class ChatClientApp extends Application {

    private Stage primaryStage;

    // Scenes
    private Scene loginScene;
    private Scene conversationsScene; // listing user’s conversations
    private Scene chatScene;          // a single conversation’s chat

    // For conversation listing
    private ListView<ConversationListItem> conversationListView;

    // For chat UI
    private TextArea chatArea;
    private TextField inputField;

    // For files UI (in Files Tab)
    private ListView<String> filesListView;

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

        // Start with login
        primaryStage.setScene(loginScene);
        primaryStage.setTitle("Chat Login");
        primaryStage.show();
    }

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

    private Scene buildConversationTabsScene() {
        TabPane tabPane = new TabPane();

        // Chat Tab
        Tab chatTab = new Tab("Chat");
        chatTab.setContent(buildChatUI());
        chatTab.setClosable(false);

        // Files Tab
        Tab filesTab = new Tab("Files");
        filesTab.setContent(buildFilesUI());
        filesTab.setClosable(false);

        tabPane.getTabs().addAll(chatTab, filesTab);
        return new Scene(tabPane, 600, 400);
    }

    private VBox buildChatUI() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        inputField = new TextField();
        inputField.setPromptText("Type your message...");
        Button sendButton = new Button("Send");
        sendButton.setOnAction(e -> sendMessage());
        inputField.setOnAction(e -> sendMessage());
        Button sendFileButton = new Button("Send File");
        sendFileButton.setOnAction(e -> sendFile());
        HBox inputBox = new HBox(10, inputField, sendButton, sendFileButton);
        root.getChildren().addAll(chatArea, inputBox);
        return root;
    }

    private Pane buildFilesUI() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        filesListView = new ListView<>();
        filesListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                String selectedFile = filesListView.getSelectionModel().getSelectedItem();
                if (selectedFile != null) {
                    confirmAndDownload(selectedFile);
                }
            }
        });
        Label instructions = new Label("Double-click a file to download.");
        root.getChildren().addAll(instructions, filesListView);
        return root;
    }

    private Scene buildConversationsScene() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        Label header = new Label("Your Conversations");
        header.setStyle("-fx-font-size: 16px;");
        conversationListView = new ListView<>();
        Button newChatBtn = new Button("New Chat");
        newChatBtn.setOnAction(e -> showNewChatDialog());
        Button openChatBtn = new Button("Open Chat");
        openChatBtn.setOnAction(e -> openSelectedConversation());
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

    private void showNewChatDialog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New Chat");
        dialog.setHeaderText("Enter the email of the user you want to chat with:");
        dialog.setContentText("Email:");
        Optional<String> result = dialog.showAndWait();
        if (result.isPresent()) {
            String otherEmail = result.get().trim();
            if (!otherEmail.isEmpty()) {
                out.println("NEW_CHAT|" + otherEmail);
            }
        }
    }

    private void openSelectedConversation() {
        ConversationListItem selectedItem = conversationListView.getSelectionModel().getSelectedItem();
        if (selectedItem == null) {
            showAlert("Please select a conversation first.");
            return;
        }
        currentConversationId = selectedItem.getConversationId();
        Scene convScene = buildConversationTabsScene();
        primaryStage.setScene(convScene);
        primaryStage.setTitle(selectedItem.getDisplayName());
        out.println("GET_MESSAGES|" + currentConversationId);
    }

    private void loadConversationsList() {
        conversationListView.getItems().clear();
        out.println("LIST_USER_CONVERSATIONS");
    }

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
        if (connectAndAuthenticate("localhost", 12345, "LOGIN", "", email, password)) {
            username = email;
            primaryStage.setScene(conversationsScene);
            primaryStage.setTitle("Conversations - " + displayName);
            loadConversationsList();
        } else {
            showAlert("Authentication failed. Please try again.");
        }
    }

    private void handleRegistration() {
        RegistrationData regData = askForRegistrationData();
        if (regData == null) {
            showAlert("Registration canceled.");
            return;
        }
        if (connectAndAuthenticate("localhost", 12345, "REGISTER", regData.getFullName(), regData.getEmail(), regData.getPassword())) {
            username = regData.getEmail();
            primaryStage.setScene(conversationsScene);
            primaryStage.setTitle("Conversations - " + displayName);
            loadConversationsList();
        } else {
            showAlert("Registration failed. Please try again.");
        }
    }

    private void handleLogout() {
        try {
            if (out != null) out.close();
            if (in  != null) in.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        username = null;
        displayName = null;
        Platform.runLater(() -> {
            primaryStage.setScene(buildLoginScene());
            primaryStage.setTitle("Chat Login");
        });
    }

    private boolean connectAndAuthenticate(String host, int port, String authMode, String fullName, String userOrEmail, String pass) {
        try {
            socket = new Socket(host, port);
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
            if (authMode.equals("REGISTER")) {
                out.println("REGISTER|" + fullName + "|" + userOrEmail + "|" + pass);
                username = userOrEmail;
            } else {
                out.println("LOGIN|" + userOrEmail + "|" + pass);
                username = userOrEmail;
            }
            String response = in.readLine();
            if (response == null) {
                System.err.println("Server closed connection unexpectedly.");
                return false;
            }
            if (response.startsWith("AUTH_OK")) {
                String[] parts = response.split("\\|", 2);
                if (parts.length == 2) {
                    displayName = parts[1];
                    System.out.println("Authentication successful! Display Name: " + displayName);
                }
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

    private void handleServerLine(String line) {
        System.out.println("Server => " + line);
        String[] parts = line.split("\\|", 3);
        String command = parts[0];
        switch (command) {
            case "MY_CONVO": {
                if (parts.length >= 3) {
                    String convId = parts[1];
                    String dispName = parts[2];
                    ConversationListItem item = new ConversationListItem(convId, dispName);
                    conversationListView.getItems().add(item);
                }
                break;
            }
            case "MESSAGE_HISTORY": {
                if (parts.length >= 2 && chatArea != null) {
                    String lineOfHistory = parts[1];
                    if (lineOfHistory.startsWith("(FILE)")) {
                        String fname = lineOfHistory.substring("(FILE)".length()).trim();
                        filesListView.getItems().add(fname);
                    } else {
                        chatArea.appendText(lineOfHistory + "\n");
                    }
                }
                break;
            }
            case "NEW_MESSAGE": {
                if (parts.length == 3 && chatArea != null) {
                    String senderName = parts[1];
                    String plainText = parts[2];
                    chatArea.appendText(senderName + ": " + plainText + "\n");
                }
                break;
            }
            case "NEW_FILE": {
                if (parts.length == 3) {
                    String senderName = parts[1];
                    String fname = parts[2];
                    chatArea.appendText(senderName + " sent a file: " + fname + "\n");
                    if (!filesListView.getItems().contains(fname)) {
                        filesListView.getItems().add(fname);
                    }
                }
                break;
            }
            case "FILE_DATA": {
                if (parts.length == 3) {
                    String fname = parts[1];
                    String data = parts[2];
                    if ("NOT_FOUND".equals(data)) {
                        showAlert("File not found on server: " + fname);
                    } else if ("ERROR".equals(data)) {
                        showAlert("Error retrieving file: " + fname);
                    } else {
                        try {
                            byte[] fileBytes = Base64.getDecoder().decode(data);
                            FileChooser fileChooser = new FileChooser();
                            fileChooser.setTitle("Save " + fname);
                            fileChooser.setInitialFileName(fname);
                            File saveLocation = fileChooser.showSaveDialog(primaryStage);
                            if (saveLocation != null) {
                                Files.write(saveLocation.toPath(), fileBytes);
                                showAlert("File saved to: " + saveLocation.getAbsolutePath());
                            }
                        } catch (IllegalArgumentException e) {
                            e.printStackTrace();
                            showAlert("Base64 decoding error for file: " + fname);
                        } catch (IOException ex) {
                            ex.printStackTrace();
                            showAlert("Error saving file: " + ex.getMessage());
                        }
                    }
                }
                break;
            }
            case "CHAT_STARTED": {
                // Optionally refresh conversation list
                loadConversationsList();
                break;
            }
            default:
                break;
        }
    }

    private void sendMessage() {
        if (currentConversationId == null) {
            showAlert("No conversation is opened.");
            return;
        }
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;
        out.println("SEND_MESSAGE|" + currentConversationId + "|" + text);
        chatArea.appendText("You: " + text + "\n");
        inputField.clear();
    }

    private void sendFile() {
        if (currentConversationId == null) {
            showAlert("No conversation is opened.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select File to Send");
        File file = chooser.showOpenDialog(primaryStage);
        if (file == null) return;
        try {
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String base64Data = Base64.getEncoder().encodeToString(fileBytes);
            String filename = file.getName();
            out.println("SEND_FILE|" + currentConversationId + "|" + filename + "|" + base64Data);
            // Log a message in chat for record
            out.println("SEND_MESSAGE|" + currentConversationId + "|" + "File shared: " + filename);
            chatArea.appendText("You: File shared: " + filename + "\n");
        } catch (IOException ex) {
            ex.printStackTrace();
            showAlert("Error reading file: " + ex.getMessage());
        }
    }

    private void confirmAndDownload(String filename) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Download File");
        alert.setHeaderText("Download File: " + filename);
        alert.setContentText("Would you like to download this file?");
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            out.println("GET_FILE|" + currentConversationId + "|" + filename);
        }
    }

    private void downloadFile() {
        if (currentConversationId == null) {
            showAlert("No conversation is opened.");
            return;
        }
        TextInputDialog dialog = new TextInputDialog("document.pdf");
        dialog.setTitle("Download File");
        dialog.setHeaderText("Enter the exact filename to download:");
        dialog.setContentText("Filename:");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) return;
        String filename = result.get().trim();
        if (filename.isEmpty()) return;
        out.println("GET_FILE|" + currentConversationId + "|" + filename);
    }

    private String askForCredential(String label, String defaultVal) {
        TextInputDialog dialog = new TextInputDialog(defaultVal);
        dialog.setTitle("Enter " + label);
        dialog.setHeaderText(label);
        dialog.setContentText(label + ":");
        Optional<String> result = dialog.showAndWait();
        return result.orElse(null);
    }

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

    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Notification");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

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
