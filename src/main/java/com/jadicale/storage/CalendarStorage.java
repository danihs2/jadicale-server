package com.jadicale.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@Service
public class CalendarStorage {

    @Value("${jadicale.storage-dir:${user.home}/.jadicale/collections}")
    private String storageDir;

    private Path userDir(String username) {
        return Paths.get(storageDir, username);
    }

    private Path calendarDir(String username, String calendar) {
        return userDir(username).resolve(calendar);
    }

    private Path eventFile(String username, String calendar, String filename) {
        return calendarDir(username, calendar).resolve(filename);
    }

    public void createCalendar(String username, String calendar) throws IOException {
        Files.createDirectories(calendarDir(username, calendar));
    }

    public boolean calendarExists(String username, String calendar) {
        return Files.isDirectory(calendarDir(username, calendar));
    }

    public List<String> listCalendars(String username) throws IOException {
        Path dir = userDir(username);
        List<String> result = new ArrayList<>();
        if (!Files.exists(dir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) result.add(entry.getFileName().toString());
            }
        }
        return result;
    }

    public void saveEvent(String username, String calendar, String filename, byte[] content) throws IOException {
        Files.createDirectories(calendarDir(username, calendar));
        Files.write(eventFile(username, calendar, filename), content);
    }

    public byte[] loadEvent(String username, String calendar, String filename) throws IOException {
        return Files.readAllBytes(eventFile(username, calendar, filename));
    }

    public boolean eventExists(String username, String calendar, String filename) {
        return Files.exists(eventFile(username, calendar, filename));
    }

    public void deleteEvent(String username, String calendar, String filename) throws IOException {
        Files.deleteIfExists(eventFile(username, calendar, filename));
    }

    public List<String> listEvents(String username, String calendar) throws IOException {
        Path dir = calendarDir(username, calendar);
        List<String> result = new ArrayList<>();
        if (!Files.exists(dir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.ics")) {
            for (Path entry : stream) result.add(entry.getFileName().toString());
        }
        return result;
    }

    public String etag(String username, String calendar, String filename) {
        try {
            byte[] content = Files.readAllBytes(eventFile(username, calendar, filename));
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }
}
