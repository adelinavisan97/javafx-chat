package com.example.server;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.github.cdimascio.dotenv.Dotenv;
import org.bson.Document;
import org.mindrot.jbcrypt.BCrypt;

public class MongoService {

    private MongoClient mongoClient;
    private MongoDatabase database;
    private MongoCollection<Document> usersCollection;

    public MongoService() {
        Dotenv dotenv = Dotenv.configure().load();
        String mongoUri = dotenv.get("MONGO_URI");

        // Connect using the URI from .env
        mongoClient = MongoClients.create(mongoUri);
        database = mongoClient.getDatabase("chatApp");

        // "users" collection for user credentials
        usersCollection = database.getCollection("users");
    }

    /**
     * Registers a new user.
     * @param email The user's email (used as the unique username)
     * @param plainPassword The plaintext password
     * @param fullName The full name (first and last) of the user
     * @return true if the user was successfully created, false if the email already exists.
     */
    public boolean registerUser(String email, String plainPassword, String fullName) {
        // Check if user already exists by email
        Document existing = usersCollection.find(new Document("email", email)).first();
        if (existing != null) {
            return false;
        }
        // Hash the password using BCrypt
        String hashed = BCrypt.hashpw(plainPassword, BCrypt.gensalt());
        Document userDoc = new Document("fullName", fullName)
                .append("email", email)
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

    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}
