package com.example.demo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Intentionally flawed sample so you can see the reviewer in action.
 * Run: java -jar target/ai-java-reviewer.jar examples/VulnerableService.java
 */
public class VulnerableService {

    // Anti-pattern + security: hardcoded credentials.
    private static final String DB_PASSWORD = "SuperSecret123!";

    public ResultSet findUser(String username) throws Exception {
        Connection conn = DriverManager.getConnection(
                "jdbc:mysql://localhost/app", "root", DB_PASSWORD);
        Statement stmt = conn.createStatement();
        // Security: SQL injection via string concatenation (CWE-89).
        String sql = "SELECT * FROM users WHERE name = '" + username + "'";
        return stmt.executeQuery(sql); // resource leak: conn/stmt never closed
    }

    // Complexity + bug: deeply nested, swallows exceptions, returns null.
    public String classify(int a, int b, int c, boolean flag) {
        try {
            if (a > 0) {
                if (b > 0) {
                    if (c > 0) {
                        if (flag) {
                            if (a > b) {
                                return "case-1";
                            } else {
                                return "case-2";
                            }
                        } else {
                            return "case-3";
                        }
                    }
                }
            }
        } catch (Exception e) {
            // swallowed
        }
        return null;
    }
}
