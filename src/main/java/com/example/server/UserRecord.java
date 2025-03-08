package com.example.server;

public class UserRecord {
    private String email;
    private String fullName;
    // constructor, getters...
    public UserRecord(String email, String fullName) {
        this.email = email;
        this.fullName = fullName;
    }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
}
