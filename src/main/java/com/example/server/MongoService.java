package com.example.server;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.mindrot.jbcrypt.BCrypt;

public class MongoService {

    private MongoClient mongoClient;
    private MongoDatabase database;
    private MongoCollection<Document> usersCollection;

    public MongoService() {
        // Connect to local MongoDB on default port 27017
        mongoClient = MongoClients.create("mongodb+srv://testUser:6Z1cvtHaepsgxaxi@myatlasclusteredu.ipecla6.mongodb.net/?retryWrites=true&w=majority&appName=myAtlasClusterEDU");
        database = mongoClient.getDatabase("chatApp");

        // "users" collection for user credentials
        usersCollection = database.getCollection("users");
    }

    /**
     * Attempt to register a new user.
     * @return true if created successfully, false if username already exists.
     */
    public boolean registerUser(String username, String plainPassword) {
        // Check if user already exists
        Document existing = usersCollection.find(new Document("username", username)).first();
        if (existing != null) {
            return false; // username taken
        }
        // Hash password
        String hashed = BCrypt.hashpw(plainPassword, BCrypt.gensalt());

        // Insert new user doc
        Document userDoc = new Document("username", username)
                .append("password", hashed);
        usersCollection.insertOne(userDoc);
        return true;
    }

    /**
     * Check if the provided credentials match a user in DB.
     * @return true if username/password are correct, false otherwise
     */
    public boolean loginUser(String username, String plainPassword) {
        Document userDoc = usersCollection.find(new Document("username", username)).first();
        if (userDoc == null) {
            return false;
        }
        String storedHash = userDoc.getString("password");
        return BCrypt.checkpw(plainPassword, storedHash);
    }

    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}
