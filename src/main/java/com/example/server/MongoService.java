package com.example.server;

import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
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
    private MongoCollection<Document> conversationsCollection;

    public MongoService() {
        Dotenv dotenv = Dotenv.configure().load();
        String mongoUri = dotenv.get("MONGO_URI");
        mongoClient = MongoClients.create(mongoUri);
        database = mongoClient.getDatabase("chatApp");
        usersCollection = database.getCollection("users");
        conversationsCollection = database.getCollection("conversations");
    }

    public boolean registerUser(String email, String plainPassword, String fullName) {
        email = email.toLowerCase();
        Document existing = usersCollection.find(new Document("email", email)).first();
        if (existing != null) return false;

        String hashed = BCrypt.hashpw(plainPassword, BCrypt.gensalt());
        Document userDoc = new Document("fullName", fullName)
                .append("email", email)
                .append("password", hashed)
                .append("conversations", new ArrayList<Document>()); // empty array init

        System.out.println("Registering user: " + userDoc.toJson());
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
        if (userDoc != null) {
            return userDoc.getString("fullName");
        }
        return null;
    }

    /**
     *  Search user by email prefix (case-insensitive).
     */
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

    /**
     * Creates or retrieves a conversation doc in 'conversations' (like before).
     */
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

    public void saveMessage(String conversationId, String sender, String encryptedMessage) {
        Document msgDoc = new Document("sender", sender)
                .append("text", encryptedMessage)
                .append("timestamp", new Date().getTime());
        Bson filter = Filters.eq("conversationId", conversationId);
        Bson update = new Document("$push", new Document("messages", msgDoc));
        conversationsCollection.updateOne(filter, update);
    }

    /**
     * Add a reference in the user doc's "conversations" array:
     * { conversationId: <convId>, displayName: <someName> }
     */
    public void addConversationToUser(String userEmail, String conversationId, String displayName) {
        Document newRef = new Document("conversationId", conversationId)
                .append("displayName", displayName);
        Bson filter = Filters.eq("email", userEmail);
        Bson update = Updates.push("conversations", newRef);
        usersCollection.updateOne(filter, update);
    }

    /**
     * Returns all conversation references from the user's doc:
     * each element => { conversationId, displayName }
     */
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

    /**
     * Decrypt messages from the conversation doc in "conversations" collection.
     */
    public List<String> getMessages(String conversationId, String currentUser) {
        List<String> result = new ArrayList<>();
        Document conv = conversationsCollection.find(new Document("conversationId", conversationId)).first();
        if (conv == null) return result;

        @SuppressWarnings("unchecked")
        List<Document> messages = (List<Document>) conv.get("messages", List.class);
        if (messages == null) return result;

        for (Document msgDoc : messages) {
            String sender = msgDoc.getString("sender");
            String cipherText = msgDoc.getString("text");
            try {
                String plainText = CryptoUtil.decrypt(cipherText);
                // if I'm the sender => "You: <text>"
                if (sender.equalsIgnoreCase(currentUser)) {
                    result.add("You: " + plainText);
                } else {
                    // It's someone else => fetch their fullName
                    String theirName = getFullName(sender);
                    if (theirName == null) theirName = sender;
                    result.add(theirName + ": " + plainText);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return result;
    }

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
        return (userDoc != null);
    }

    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}
