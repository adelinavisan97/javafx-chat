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
    private Scene loginScene;
    private Scene chatScene;

    private TextArea chatArea;
    private TextField inputField;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    // The username used for login will be the email (for uniqueness)
    private String username;
    private String displayName;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        // Initially show the login/registration UI
        loginScene = buildLoginScene();
        primaryStage.setScene(loginScene);
        primaryStage.setTitle("Chat Login");
        primaryStage.show();
    }

    /**
     * Builds the login/registration scene.
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
     * Handles the login process.
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
        if (connectAndAuthenticate("localhost", 12345, "LOGIN", displayName, email, password)) {
            username = email;
            chatScene = buildChatScene();
            primaryStage.setScene(chatScene);
            primaryStage.setTitle("Chat - " + displayName);
            startReaderThread();
        } else {
            showAlert("Authentication failed. Please try again.");
        }
    }

    /**
     * Handles the registration process.
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
            chatScene = buildChatScene();
            primaryStage.setScene(chatScene);
            primaryStage.setTitle("JavaFX Chat Client - " + username);
            startReaderThread();
        } else {
            showAlert("Registration failed. Please try again.");
        }
    }

    /**
     * Builds the chat UI scene, including a logout button in the top-right corner.
     */
    private Scene buildChatScene() {
        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);

        inputField = new TextField();
        inputField.setPromptText("Type your message...");

        Button sendButton = new Button("Send");
        sendButton.setOnAction(e -> sendMessage());
        inputField.setOnAction(e -> sendMessage());

        Button logoutButton = new Button("Logout");
        logoutButton.setOnAction(e -> handleLogout());

        HBox topBar = new HBox();
        topBar.setPadding(new Insets(5));
        topBar.setAlignment(Pos.CENTER_RIGHT);
        topBar.getChildren().add(logoutButton);

        HBox inputBox = new HBox(10, inputField, sendButton);
        inputBox.setPadding(new Insets(5));
        VBox mainBox = new VBox(10, chatArea, inputBox);
        mainBox.setPadding(new Insets(10));

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(mainBox);
        return new Scene(root, 400, 300);
    }

    /**
     * Handles logout by closing current connections and returning to the login scene.
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
        Platform.runLater(() -> primaryStage.setScene(buildLoginScene()));
    }

    /**
     * Generic prompt for non-sensitive fields.
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
     * Prompts for a password using a custom dialog with a PasswordField.
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
     * Prompts for registration data: full name, email, and password (with a show/hide toggle).
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

        // Create a visible text field for showing password if desired.
        TextField visiblePasswordField = new TextField();
        visiblePasswordField.setManaged(false);
        visiblePasswordField.setVisible(false);
        visiblePasswordField.textProperty().bindBidirectional(passwordField.textProperty());

        CheckBox showPasswordCheckBox = new CheckBox("Show Password");
        showPasswordCheckBox.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            if (isSelected) {
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

    /**
     * Attempts to connect to the server and perform either LOGIN or REGISTER.
     * For registration, sends:
     * "REGISTER|<fullName>|<email>|<password>"
     * For login, sends:
     * "LOGIN|<email>|<password>"
     * Returns true if the server responds with AUTH_OK.
     */
    private boolean connectAndAuthenticate(String host, int port, String authMode,  String fullName, String userOrEmail, String pass) {
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
                // Expect response to be "AUTH_OK|<fullName>"
                String[] parts = response.split("\\|", 2);
                if (parts.length == 2) {
                    displayName = parts[1]; // store the full name as displayName
                    System.out.println("Authentication successful! Display Name: " + displayName);
                }
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
     * Starts a background thread that continuously reads messages from the server.
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
     * If the incoming message starts with "<username>:", rewrite it as "You:".
     */
    private void handleIncomingMessage(String rawMessage) {
        String prefix = displayName + ": ";
        if (rawMessage.startsWith(prefix)) {
            String withoutName = rawMessage.substring(prefix.length());
            chatArea.appendText("You: " + withoutName + "\n");
        } else {
            chatArea.appendText(rawMessage + "\n");
        }
    }

    /**
     * Sends a normal chat message.4
     * We assume the server will prepend the username, so we send the raw text.
     */
    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        out.println(text);
        inputField.clear();
    }

    private void showError(String message) {
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

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Notification");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
