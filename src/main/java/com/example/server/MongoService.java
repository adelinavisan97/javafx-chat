package com.example.server;

import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import io.github.cdimascio.dotenv.Dotenv;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.mindrot.jbcrypt.BCrypt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class MongoService {

    private MongoClient mongoClient;
    private MongoDatabase database;
    private MongoCollection<Document> usersCollection;

    // Add a conversations collection for storing chats
    private MongoCollection<Document> conversationsCollection;

    public MongoService() {
        Dotenv dotenv = Dotenv.configure().load();
        String mongoUri = dotenv.get("MONGO_URI");

        // Connect using the URI from .env
        mongoClient = MongoClients.create(mongoUri);
        database = mongoClient.getDatabase("chatApp");

        // "users" collection for user credentials
        usersCollection = database.getCollection("users");

        // "conversations" collection for storing chat conversations
        conversationsCollection = database.getCollection("conversations");
    }

    /**
     * Registers a new user.
     * @param email The user's email (used as the unique username)
     * @param plainPassword The plaintext password
     * @param fullName The full name (first and last) of the user
     * @return true if the user was successfully created, false if the email already exists.
     */
    public boolean registerUser(String email, String plainPassword, String fullName) {
        email = email.toLowerCase(); // unify
        Document existing = usersCollection.find(new Document("email", email)).first();
        if (existing != null) {
            return false;
        }
        // Hash password, etc.
        String hashed = BCrypt.hashpw(plainPassword, BCrypt.gensalt());
        Document userDoc = new Document("fullName", fullName)
                .append("email", email)      // store all-lowercase
                .append("password", hashed);

        System.out.println("Registering user: " + userDoc.toJson());
        usersCollection.insertOne(userDoc);
        return true;
    }



    /**
     * Validates login credentials.
     * @param email The email (username)
     * @param plainPassword The plaintext password provided
     * @return true if valid, false otherwise.
     */
    public boolean loginUser(String email, String plainPassword) {
        Document userDoc = usersCollection.find(new Document("email", email)).first();
        if (userDoc == null) {
            return false;
        }
        String storedHash = userDoc.getString("password");
        return BCrypt.checkpw(plainPassword, storedHash);
    }

    public String getFullName(String email) {
        Document userDoc = usersCollection.find(new Document("email", email)).first();
        if (userDoc != null) {
            return userDoc.getString("fullName");
        }
        return null;
    }

    /**
     * Creates or retrieves a conversation document for the two specified users.
     * Returns the conversationId for that chat.
     */
    public String createOrGetConversation(String userA, String userB) {
        // Sort the two users to create a stable unique ID
        List<String> sorted = new ArrayList<>();
        sorted.add(userA.toLowerCase());
        sorted.add(userB.toLowerCase());
        Collections.sort(sorted);
        String conversationId = sorted.get(0) + "_" + sorted.get(1);

        Document existing = conversationsCollection.find(new Document("conversationId", conversationId)).first();
        if (existing == null) {
            // Create a new conversation doc
            Document newConv = new Document("conversationId", conversationId)
                    .append("participants", sorted)
                    .append("messages", new ArrayList<Document>());
            conversationsCollection.insertOne(newConv);
        }
        return conversationId;
    }

    /**
     * Saves a message to the conversation.
     * The message is encrypted before storing.
     * @param conversationId The ID of the conversation
     * @param sender Email of the sender
     * @param encryptedMessage The already encrypted message text
     */
    public void saveMessage(String conversationId, String sender, String encryptedMessage) {
        Document msgDoc = new Document("sender", sender)
                .append("text", encryptedMessage)
                .append("timestamp", new Date().getTime());

        Bson filter = Filters.eq("conversationId", conversationId);
        Bson update = new Document("$push", new Document("messages", msgDoc));
        conversationsCollection.updateOne(filter, update);
    }

    /**
     * Retrieves all messages from the conversation, decrypting them before returning.
     * Returns a list of plain-text messages in the format "senderName: text"
     */
    public List<String> getMessages(String conversationId, String currentUser) {
        List<String> result = new ArrayList<>();
        Document conv = conversationsCollection.find(new Document("conversationId", conversationId)).first();
        if (conv == null) {
            return result;
        }

        @SuppressWarnings("unchecked")
        List<Document> messages = (List<Document>) conv.get("messages", List.class);
        if (messages == null) {
            return result;
        }

        for (Document msgDoc : messages) {
            String sender = msgDoc.getString("sender");  // e.g. "alice@example.com"
            String cipherText = msgDoc.getString("text");
            long timestamp = msgDoc.getLong("timestamp");

            try {
                String plainText = CryptoUtil.decrypt(cipherText);

                if (sender.equalsIgnoreCase(currentUser)) {
                    // It's me
                    result.add("You: " + plainText);
                } else {
                    // It's someone else; fetch their fullName
                    String theirName = getFullName(sender);
                    if (theirName == null) {
                        theirName = sender; // fallback to email if missing
                    }
                    result.add(theirName + ": " + plainText);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return result;
    }


    /**
     * Finds the other participant in this conversation besides currentUser.
     */
    public String getRecipientFromConversation(String conversationId, String currentUser) {
        Document conv = conversationsCollection.find(new Document("conversationId", conversationId)).first();
        if (conv == null) return null;
        @SuppressWarnings("unchecked")
        List<String> participants = (List<String>) conv.get("participants");
        if (participants == null) return null;

        for (String user : participants) {
            if (!user.equalsIgnoreCase(currentUser)) {
                return user;
            }
        }
        return null;
    }

    public List<String> getAllConversationsForUser(String userEmail) {
        userEmail = userEmail.toLowerCase();
        System.out.println("[DEBUG] getAllConversationsForUser: " + userEmail);

        List<String> result = new ArrayList<>();
        FindIterable<Document> docs = conversationsCollection.find(
                Filters.in("participants", userEmail)
        );

        for (Document doc : docs) {
            System.out.println("[DEBUG] doc in participants: " + doc.toJson());
            String conversationId = doc.getString("conversationId");
            result.add(conversationId);
        }
        System.out.println("[DEBUG] returning convos: " + result);
        return result;
    }

    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
    public boolean userExists(String email) {
        Document userDoc = usersCollection.find(new Document("email", email.toLowerCase())).first();
        return (userDoc != null);
    }

}
