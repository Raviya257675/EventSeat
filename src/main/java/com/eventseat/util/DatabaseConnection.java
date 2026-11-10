
package com.eventseat.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.io.InputStream;
import java.io.IOException;
import java.util.Properties;

public class DatabaseConnection {

    private static final Properties properties =
            new Properties();

    static {

        try (InputStream input =
                     DatabaseConnection.class
                             .getClassLoader()
                             .getResourceAsStream("db.properties")) {

            if (input == null) {
                throw new IllegalStateException(
                        "db.properties file not found!"
                );
            }

            properties.load(input);

        } catch (IOException e) {

            throw new ExceptionInInitializerError(e);
        }
    }

    public static Connection getConnection()
            throws SQLException {

        String url =
                properties.getProperty("db.url");

        String username =
                properties.getProperty("db.username");

        String password =
                properties.getProperty("db.password");

        if (url == null || username == null
                || password == null
                || password.isBlank()) {

            throw new SQLException(
                    "Database configuration is incomplete."
            );
        }

        return DriverManager.getConnection(
                url,
                username,
                password
        );
    }
}
