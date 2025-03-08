package com.example.client;

public class RegistrationData {
    private final String fullName;
    private final String email;
    private final String password;

    public RegistrationData(String fullName, String email, String password) {
        this.fullName = fullName;
        this.email = email;
        this.password = password;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }
}
