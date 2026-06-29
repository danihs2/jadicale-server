package com.jadicale.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Service
public class PasswdFileUserDetailsService implements UserDetailsService {

    @Value("${jadicale.passwd-file:${user.home}/.jadicale/passwd}")
    private String passwdFilePath;

    private Map<String, String> loadUsers() {
        Map<String, String> users = new HashMap<>();
        Path path = Paths.get(passwdFilePath);
        if (!Files.exists(path)) return users;
        try {
            Files.lines(path).forEach(line -> {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) return;
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String username = line.substring(0, colon);
                    String hash = line.substring(colon + 1).trim();
                    // Normalize htpasswd $2y$ and Python bcrypt $2b$ to $2a$ for Java BCrypt
                    if (hash.startsWith("$2y$") || hash.startsWith("$2b$")) {
                        hash = "$2a$" + hash.substring(4);
                    }
                    users.put(username, hash);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Cannot read passwd file: " + passwdFilePath, e);
        }
        return users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Map<String, String> users = loadUsers();
        String hash = users.get(username);
        if (hash == null) throw new UsernameNotFoundException("User not found: " + username);
        return User.builder()
                .username(username)
                .password(hash)
                .roles("USER")
                .build();
    }
}
