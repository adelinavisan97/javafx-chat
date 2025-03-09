package com.example.server;

import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.ClientSession;
import io.github.cdimascio.dotenv.Dotenv;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.mindrot.jbcrypt.BCrypt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class MongoService {
    private final MongoClient mongoClient;
    private final MongoDatabase database;
    private final MongoCollection<Document> usersCollection;
    private final MongoCollection<Document> conversationsCollection;

    public MongoService() {
        Dotenv dotenv = Dotenv.configure().load();
        String mongoUri = dotenv.get("MONGO_URI");
        mongoClient = MongoClients.create(mongoUri);
        database = mongoClient.getDatabase("chatApp");
        usersCollection = database.getCollection("users");
        conversationsCollection = database.getCollection("conversations");
    }

    // -------------------- User Authentication --------------------
    public boolean registerUser(String email, String plainPassword, String fullName) {
        email = email.toLowerCase();
        Document existing = usersCollection.find(new Document("email", email)).first();
        if (existing != null) return false;
        String hashed = BCrypt.hashpw(plainPassword, BCrypt.gensalt());
        Document userDoc = new Document("fullName", fullName)
                .append("email", email)
                .append("password", hashed)
                .append("conversations", new ArrayList<Document>());
        usersCollection.insertOne(userDoc);
        return true;
    }

    public boolean loginUser(String email, String plainPassword) {
        Document userDoc = usersCollection.find(new Document("email", email)).first();
        if (userDoc == null) return false;
        String storedHash = userDoc.getString("password");
        return BCrypt.checkpw(plainPassword, storedHash);
    }

    public String getFullName(String email) {
        Document userDoc = usersCollection.find(new Document("email", email)).first();
        return userDoc != null ? userDoc.getString("fullName") : null;
    }

    public List<UserRecord> searchUsersByPrefix(String prefix) {
        List<UserRecord> results = new ArrayList<>();
        FindIterable<Document> docs = usersCollection.find(
                Filters.regex("email", "^" + prefix, "i")
        );
        for (Document doc : docs) {
            String em = doc.getString("email");
            String fn = doc.getString("fullName");
            results.add(new UserRecord(em, fn));
        }
        return results;
    }

    // -------------------- Conversation Management --------------------
    public String createOrGetConversation(String userA, String userB) {
        List<String> sorted = new ArrayList<>();
        sorted.add(userA.toLowerCase());
        sorted.add(userB.toLowerCase());
        Collections.sort(sorted);
        String conversationId = sorted.get(0) + "_" + sorted.get(1);
        Document existing = conversationsCollection.find(new Document("conversationId", conversationId)).first();
        if (existing == null) {
            Document newConv = new Document("conversationId", conversationId)
                    .append("participants", sorted)
                    .append("messages", new ArrayList<Document>());
            conversationsCollection.insertOne(newConv);
        }
        return conversationId;
    }

    public void addConversationToUser(String userEmail, String conversationId, String displayName) {
        Document newRef = new Document("conversationId", conversationId)
                .append("displayName", displayName);
        Bson filter = Filters.eq("email", userEmail);
        Bson update = Updates.push("conversations", newRef);
        usersCollection.updateOne(filter, update);
    }

    public List<ConvRef> getUserConversations(String userEmail) {
        Document userDoc = usersCollection.find(new Document("email", userEmail)).first();
        List<ConvRef> result = new ArrayList<>();
        if (userDoc == null) return result;
        @SuppressWarnings("unchecked")
        List<Document> convList = (List<Document>) userDoc.get("conversations", List.class);
        if (convList == null) return result;
        for (Document d : convList) {
            String cid = d.getString("conversationId");
            String disp = d.getString("displayName");
            result.add(new ConvRef(cid, disp));
        }
        return result;
    }

    // -------------------- Message Storage --------------------
    /**
     * Saves a message with a transaction rollback mechanism.
     */
    public void saveMessage(String conversationId, String sender, String encryptedMessage) {
        try (ClientSession session = mongoClient.startSession()) {
            session.startTransaction();
            try {
                Document msgDoc = new Document("sender", sender)
                        .append("text", encryptedMessage)
                        .append("isFile", false)
                        .append("timestamp", new Date().getTime());

                Bson filter = Filters.eq("conversationId", conversationId);
                Bson update = Updates.push("messages", msgDoc);

                conversationsCollection.updateOne(session, filter, update);
                session.commitTransaction();
            } catch (Exception e) {
                session.abortTransaction(); // Rollback on failure
                e.printStackTrace();
            }
        }
    }


    public List<String> getMessages(String conversationId, String currentUser) {
        List<String> result = new ArrayList<>();
        Document conv = conversationsCollection.find(new Document("conversationId", conversationId)).first();
        if (conv == null) return result;
        @SuppressWarnings("unchecked")
        List<Document> messages = (List<Document>) conv.get("messages", List.class);
        if (messages == null) return result;
        for (Document msgDoc : messages) {
            boolean isFile = msgDoc.getBoolean("isFile", false);
            String sender = msgDoc.getString("sender");
            String text = msgDoc.getString("text"); // Already stored as plain text summary for file messages.
            if (!isFile) {
                try {
                    String plainText = CryptoUtil.decrypt(text);
                    if (sender.equalsIgnoreCase(currentUser)) {
                        result.add("You: " + plainText);
                    } else {
                        String senderName = getFullName(sender);
                        result.add((senderName != null ? senderName : sender) + ": " + plainText);
                    }
                } catch (Exception e) {
                    // Skip on decryption error
                }
            } else {
                // Use the stored summary text for file messages.
                if (sender.equalsIgnoreCase(currentUser)) {
                    result.add("You shared a file: " + msgDoc.getString("fileName"));
                } else {
                    String senderName = getFullName(sender);
                    result.add((senderName != null ? senderName : sender) + " shared a file: " + msgDoc.getString("fileName"));
                }
            }
        }
        return result;
    }

    public List<String> getFileNames(String conversationId) {
        List<String> fileNames = new ArrayList<>();
        Document conv = conversationsCollection.find(Filters.eq("conversationId", conversationId)).first();
        if (conv == null) return fileNames;
        @SuppressWarnings("unchecked")
        List<Document> messages = (List<Document>) conv.get("messages", List.class);
        if (messages == null) return fileNames;
        for (Document msgDoc : messages) {
            if (msgDoc.getBoolean("isFile", false)) {
                fileNames.add(msgDoc.getString("fileName"));
            }
        }
        return fileNames;
    }

    // -------------------- File Sharing --------------------
    public void saveFileMessage(String conversationId,
                                String senderEmail,
                                String fileName,
                                String encryptedBase64,
                                String senderFullName) {
        String summary = senderFullName + " shared a file: " + fileName;
        Document fileMsg = new Document("sender", senderEmail)
                .append("isFile", true)
                .append("fileName", fileName)
                .append("fileData", encryptedBase64)
                .append("text", summary)
                .append("timestamp", new Date().getTime());
        Bson filter = Filters.eq("conversationId", conversationId);
        Bson update = Updates.push("messages", fileMsg);
        conversationsCollection.updateOne(filter, update);
    }

    public String fetchFileBase64(String conversationId, String fileName) {
        Document conv = conversationsCollection.find(new Document("conversationId", conversationId)).first();
        if (conv == null) return null;
        @SuppressWarnings("unchecked")
        List<Document> messages = (List<Document>) conv.get("messages", List.class);
        if (messages == null) return null;
        for (Document msgDoc : messages) {
            boolean isFile = msgDoc.getBoolean("isFile", false);
            String storedFileName = msgDoc.getString("fileName");
            if (isFile && storedFileName != null && storedFileName.equals(fileName)) {
                return msgDoc.getString("fileData");
            }
        }
        return null;
    }

    // -------------------- Recipient Utilities --------------------
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

    public boolean userExists(String email) {
        Document userDoc = usersCollection.find(new Document("email", email.toLowerCase())).first();
        return userDoc != null;
    }

    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}
