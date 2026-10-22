package com.example.trojan_rideshare;

import java.util.UUID;

public class User {
    private final String id;
    private final String email;
    private final String password; // Hashed string
    private final String fullName;
    private String nickname;
    private final int graduationYear;
    private final int uscId;

    public User(String email, String password, String fullName, String nickname, int graduationYear, int uscId) {
        this.id = UUID.randomUUID().toString();
        this.email = email;
        this.password = hashPassword(password);
        this.fullName = fullName;
        this.nickname = nickname;
        this.graduationYear = graduationYear;
        this.uscId = uscId;
    }

    public User(String id, String email, String password, String fullName, String nickname, int graduationYear, int uscId) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.fullName = fullName;
        this.nickname = nickname;
        this.graduationYear = graduationYear;
        this.uscId = uscId;
    }

    private String hashPassword(String plainPassword) {
        return Integer.toHexString(plainPassword.hashCode());
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public String getFullName() { return fullName; }
    public String getNickname() { return nickname; }
    public int getGraduationYear() { return graduationYear; }
    public int getUscId() { return uscId; }

    public void setNickname(String nickname) { this.nickname = nickname; }

    public void updateNickname(String newNickName) {
        this.nickname = newNickName;
    }

    public String getUserInfo() {
        return "{\n" +
                "  \"id\": \"" + id + "\",\n" +
                "  \"email\": \"" + email + "\",\n" +
                "  \"fullName\": \"" + fullName + "\",\n" +
                "  \"nickname\": \"" + nickname + "\",\n" +
                "  \"graduationYear\": " + graduationYear + ",\n" +
                "  \"uscId\": " + uscId + "\n" +
                "}";
    }
}
