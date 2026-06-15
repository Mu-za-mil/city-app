package com.cityapp.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Initialises the Firebase Admin SDK.
 *
 * WHY FIREBASE ADMIN SDK (not the client SDK):
 *   Client SDK: runs on the user's device. Used in Android/iOS/web apps.
 *   Admin SDK: runs on our server. Has elevated privileges.
 *   Specifically: the Admin SDK can SEND push notifications to any device.
 *   The client SDK can only receive notifications on its own device.
 *
 * CREDENTIALS FILE:
 *   Contains the private key that proves to Firebase: "this server is authorised."
 *   NEVER commit this file to Git. Always load from environment variable path.
 *   In Kubernetes: mounted as a secret (Phase 16).
 *
 * GRACEFUL DEGRADATION:
 *   If credentials are missing or invalid: log a warning. Don't crash.
 *   FCM push notifications will silently fail.
 *   Email and in-app notifications still work.
 *   The app degrades gracefully without push.
 */
@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${app.fcm.credentials-path:}")
    private String credentialsPath;

    @Bean
    public FirebaseMessaging firebaseMessaging() {
        if (credentialsPath == null || credentialsPath.isBlank()) {
            log.warn("⚠️ FIREBASE_CREDENTIALS_PATH not set. " +
                    "FCM push notifications disabled. " +
                    "Email and in-app notifications still work.");
            return null;   // Null bean: FCM calls are skipped gracefully
        }

        try {
            InputStream credentialsStream;

            if (credentialsPath.startsWith("classpath:")) {
                // Development: load from src/main/resources
                String classPathLocation = credentialsPath
                        .replace("classpath:", "");
                credentialsStream = getClass().getClassLoader()
                        .getResourceAsStream(classPathLocation);
            } else {
                // Production: load from file system path
                credentialsStream = new FileInputStream(credentialsPath);
            }

            if (credentialsStream == null) {
                log.error("Firebase credentials file not found at: {}", credentialsPath);
                return null;
            }

            GoogleCredentials credentials = GoogleCredentials
                    .fromStream(credentialsStream);

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();

            // Prevent re-initialisation if app reloads (test environments)
            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options);
                log.info("✅ Firebase Admin SDK initialised");
            }

            return FirebaseMessaging.getInstance();

        } catch (IOException e) {
            log.error("Failed to initialise Firebase: {}. " +
                    "FCM push notifications disabled.", e.getMessage());
            return null;   // Graceful degradation
        }
    }
}
