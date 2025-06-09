package org.example;

import java.io.FileWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Scanner;

import org.mindrot.jbcrypt.BCrypt;

public class DatabaseHandler {
    private static final String DB_URL = "jdbc:sqlite:users.db";
    static double balance = 0.0;
    static Connection conn;

    static {
        try {
            conn = DriverManager.getConnection(DB_URL);
            Statement stmt = conn.createStatement();

            String sql = "CREATE TABLE IF NOT EXISTS users (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT NOT NULL, " +
                    "email TEXT NOT NULL UNIQUE, " +
                    "password TEXT NOT NULL)";
            stmt.executeUpdate(sql);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type TEXT NOT NULL,
                    amount REAL NOT NULL,
                    description TEXT NOT NULL,
                    user_email TEXT NOT NULL,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_email) REFERENCES users(email)
                );
                """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS loans (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    principal_amount REAL NOT NULL,
                    interest_rate REAL NOT NULL,
                    repayment_period INTEGER NOT NULL,
                    outstanding_balance REAL NOT NULL,
                    status TEXT NOT NULL,
                    created_at DATETIME,
                    FOREIGN KEY (user_id) REFERENCES users(id)
                );
                """);
                
            // === NEW: Create savings table ===
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS savings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_email TEXT NOT NULL,
                    amount REAL NOT NULL,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_email) REFERENCES users(email)
                );
                """);

            ResultSet rs = stmt.executeQuery("""
                SELECT SUM(CASE WHEN type='Credit' THEN amount ELSE -amount END) AS balance 
                FROM transactions
                """);
            if (rs.next()) {
                balance = rs.getDouble("balance");
            }

            System.out.println("Database table created successfully.");
        } catch (SQLException e) {
            System.out.println("Error creating user table: " + e.getMessage());
        }
    }

    public boolean userExists(String email) {
        String sql = "SELECT email FROM users WHERE email = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, email);
            ResultSet rs = pstmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            System.out.println("Error checking user: " + e.getMessage());
            return false;
        }
    }

    public void insertUser(String name, String email, String password) {
        String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt()); // 🔐 Hashing password
        String sql = "INSERT INTO users(name, email, password) VALUES(?,?,?)";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, name);
            pstmt.setString(2, email);
            pstmt.setString(3, hashedPassword);
            pstmt.executeUpdate();
            System.out.println("User inserted successfully.");
        } catch (SQLException e) {
            System.out.println("Error inserting user: " + e.getMessage());
        }
    }

    public boolean validateUser(String email, String password) {
        String sql = "SELECT password FROM users WHERE email = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, email);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                String storedHash = rs.getString("password");
                return BCrypt.checkpw(password, storedHash);// ✅ Check bcrypt hash
            }
        } catch (SQLException e) {
            System.out.println("Error validating user: " + e.getMessage());
        }
        return false;
    }

    public static void showHistory() {
        System.out.println("==Transaction History==");
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM transactions ORDER BY id DESC")) {

            System.out.println("ID | Type   | Amount  | Description");
            System.out.println("-----------------------------------");
            while (rs.next()) {
                System.out.printf("%-2d | %-6s | %7.2f | %s\n",
                        rs.getInt("id"),
                        rs.getString("type"),
                        rs.getDouble("amount"),
                        rs.getString("description"));
            }
        } catch (SQLException e) {
            System.err.println("Error retrieving transaction history:");
            e.printStackTrace();
        }
    }

    public static void saveTransaction(String type, double amount, String description, String userEmail) {
        String sql = "INSERT INTO transactions(type, amount, description, user_email) VALUES(?,?,?,?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setDouble(2, amount);
            ps.setString(3, description);
            ps.setString(4, userEmail);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving transaction:");
            e.printStackTrace();
        }
    }

    public static void checkLoanReminders(String email) {
        String query = "SELECT due_date, amount FROM loans WHERE email = ? AND status = 'active'";

        try (PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();

            java.time.LocalDate today = java.time.LocalDate.now();
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");

            boolean hasReminder = false;

            while (rs.next()) {
                String dueStr = rs.getString("due_date");
                java.time.LocalDate dueDate = java.time.LocalDate.parse(dueStr, fmt);
                long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, dueDate);

                if (daysLeft >= 0 && daysLeft <= 7) {
                    System.out.printf("Reminder: You have a loan of RM %.2f due on %s (in %d days).\n",
                            rs.getDouble("amount"), dueDate, daysLeft);
                    hasReminder = true;
                }
            }

            if (!hasReminder) {
                System.out.println("No loan repayments due within the next 7 days.");
            }

        } catch (Exception e) {
            System.out.println("Error checking loan reminders: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static void exportToCSV (String email) {
        String outputFile = "transaction_history.csv";

        String sql = "SELECT timestamp, description, type, amount FROM transactions";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            try (FileWriter fw = new FileWriter(outputFile)) {
                // Write CSV headers
                fw.write("Date,Description,Type,Amount\n");

                while (rs.next()) {
                    String row = String.format("%s,%s,%s,%.2f\n",
                            rs.getString("timestamp"),
                            rs.getString("description").replace(",", ";"),  // Handle commas in description
                            rs.getString("type"),
                            rs.getDouble("amount"));
                    fw.write(row);
                }

                System.out.println("Successfully exported transactions to " + outputFile);
            }
        } catch (Exception e) {
            System.out.println("Error exporting to CSV: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void disconnectDatabase() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
            System.out.println("Database connection closed.");
        }
    }


    // ====== SAVINGS FUNCTIONALITY ======

    public void activateSavings(String userEmail, double percentage) {
        String checkSql = "SELECT user_email FROM savings WHERE user_email = ?";
        String updateSql = "UPDATE savings SET amount = ? WHERE user_email = ?";
        String insertSql = "INSERT INTO savings(user_email, amount) VALUES (?, ?)";

        try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
            checkStmt.setString(1, userEmail);
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next()) {
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setDouble(1, percentage);
                        updateStmt.setString(2, userEmail);
                        int updated = updateStmt.executeUpdate();
                        if (updated > 0) {
                            System.out.println("Savings activated and updated for userEmail=" + userEmail);
                        } else {
                            System.out.println("Failed to update savings for userEmail=" + userEmail);
                        }
                    }
                } else {
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, userEmail);
                        insertStmt.setDouble(2, percentage);
                        int inserted = insertStmt.executeUpdate();
                        if (inserted > 0) {
                            System.out.println("Savings activated for userEmail=" + userEmail);
                        } else {
                            System.out.println("Failed to insert savings for userEmail=" + userEmail);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("Error activating savings for userEmail=" + userEmail + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Update the savings deduction percentage for a user.
     * Here, 'percentage' stored in 'amount' column.
     */
    public void updateSavings(String userEmail, double percentage) {
        String updateSql = "UPDATE savings SET amount = ? WHERE user_email = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
            pstmt.setDouble(1, percentage);
            pstmt.setString(2, userEmail);
            int affected = pstmt.executeUpdate();
            if (affected > 0) {
                System.out.println("Savings deduction percentage updated for userEmail=" + userEmail);
            } else {
                System.out.println("No savings record found for userEmail=" + userEmail);
            }
        } catch (SQLException e) {
            System.out.println("Error updating savings for userEmail=" + userEmail + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void runMonthlySavingsTransfer() {
        String selectActiveSql = "SELECT user_email, amount FROM savings"; 
        String getDebitBalanceSql = "SELECT balance FROM transactions WHERE user_email = ? AND type = 'debit'";
        String updateDebitSql = "UPDATE transactions SET balance = balance - ? WHERE user_email = ? AND type = 'debit'";
        String updateSavingsSql = "UPDATE transactions SET balance = balance + ? WHERE user_email = ? AND type = 'savings'";
        String insertSavingsSql = "INSERT INTO transactions (user_email, type, balance) VALUES (?, 'savings', ?)";

        try (PreparedStatement selectStmt = conn.prepareStatement(selectActiveSql);
            ResultSet rs = selectStmt.executeQuery()) {

            while (rs.next()) {
                String userEmail = rs.getString("user_email");
                double deductionPercent = rs.getDouble("amount");

                try (PreparedStatement debitStmt = conn.prepareStatement(getDebitBalanceSql)) {
                    debitStmt.setString(1, userEmail);
                    try (ResultSet debitRs = debitStmt.executeQuery()) {
                        if (debitRs.next()) {
                            double debitBalance = debitRs.getDouble("balance");
                            double deductionAmount = debitBalance * (deductionPercent / 100.0);

                            if (deductionAmount > 0 && debitBalance >= deductionAmount) {
                                try {
                                    conn.setAutoCommit(false);

                                    try (PreparedStatement updateDebitStmt = conn.prepareStatement(updateDebitSql)) {
                                        updateDebitStmt.setDouble(1, deductionAmount);
                                        updateDebitStmt.setString(2, userEmail);
                                        updateDebitStmt.executeUpdate();
                                    }

                                    try (PreparedStatement updateSavingsStmt = conn.prepareStatement(updateSavingsSql)) {
                                        updateSavingsStmt.setDouble(1, deductionAmount);
                                        updateSavingsStmt.setString(2, userEmail);
                                        int affectedRows = updateSavingsStmt.executeUpdate();

                                        if (affectedRows == 0) {
                                            try (PreparedStatement insertSavingsStmt = conn.prepareStatement(insertSavingsSql)) {
                                                insertSavingsStmt.setString(1, userEmail);
                                                insertSavingsStmt.setDouble(2, deductionAmount);
                                                insertSavingsStmt.executeUpdate();
                                            }
                                        }
                                    }

                                    conn.commit();
                                    System.out.println("Monthly transfer completed for userEmail=" + userEmail + ", amount=" + deductionAmount);
                                } catch (SQLException e) {
                                    conn.rollback();
                                    System.out.println("Transaction rolled back for userEmail=" + userEmail + ": " + e.getMessage());
                                    e.printStackTrace();
                                } finally {
                                    conn.setAutoCommit(true);
                                }
                            } else {
                                System.out.println("Insufficient funds or zero deduction for userEmail=" + userEmail);
                            }
                        } else {
                            System.out.println("No debit account found for userEmail=" + userEmail);
                        }
                    }
                } catch (SQLException e) {
                    System.out.println("Error processing debit account for userEmail=" + userEmail + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }

        } catch (SQLException e) {
            System.out.println("Error running monthly savings transfer: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ====== LOAN FUNCTIONALITY ======

    public int getUserId(String email) {
        String sql = "SELECT id FROM users WHERE email = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, email);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() ? rs.getInt("id") : -1;
        } catch (SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public void applyLoan(Scanner scanner, int userId) {
        System.out.print("Enter principal amount: ");
        double principal = scanner.nextDouble();

        System.out.print("Enter interest rate (e.g. 0.05 for 5%): ");
        double interestRate = scanner.nextDouble();

        System.out.print("Enter repayment period in months: ");
        int period = scanner.nextInt();

        double totalRepayment = principal * (1 + interestRate);
        Timestamp createdAt = new Timestamp(System.currentTimeMillis());

        String sql = "INSERT INTO loans (user_id, principal_amount, interest_rate, repayment_period, " +
                    "outstanding_balance, status, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, 'active', ?)";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            pstmt.setDouble(2, principal);
            pstmt.setDouble(3, interestRate);
            pstmt.setInt(4, period);
            pstmt.setDouble(5, totalRepayment);
            pstmt.setTimestamp(6, createdAt);
            pstmt.executeUpdate();
            System.out.println("Loan applied successfully. Total repayment: $" + totalRepayment);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void repayLoan(Scanner scanner, int userId) {
        String sql = "SELECT * FROM loans WHERE user_id = ? AND status = 'active' AND outstanding_balance > 0";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                System.out.println("No active loan to repay.");
                return;
            }

            int loanId = rs.getInt("id");
            double balance = rs.getDouble("outstanding_balance");
            int months = rs.getInt("repayment_period");
            double monthlyRepayment = balance / months;

            conn.setAutoCommit(false);
            try {
                // Insert a debit transaction for repayment
                String insertTransaction = "INSERT INTO transactions (type, amount, description, user_email) " +
                                        "VALUES ('debit', ?, 'Loan repayment', (SELECT email FROM users WHERE id = ?))";
                try (PreparedStatement txnStmt = conn.prepareStatement(insertTransaction)) {
                    txnStmt.setDouble(1, monthlyRepayment);
                    txnStmt.setInt(2, userId);
                    txnStmt.executeUpdate();
                }

                // Update loan balance and possibly status
                double newBalance = balance - monthlyRepayment;
                String updateLoan = "UPDATE loans SET outstanding_balance = ?, status = ? WHERE id = ?";
                try (PreparedStatement updLoan = conn.prepareStatement(updateLoan)) {
                    updLoan.setDouble(1, newBalance);
                    updLoan.setString(2, (newBalance <= 0.01) ? "repaid" : "active");
                    updLoan.setInt(3, loanId);
                    updLoan.executeUpdate();
                }

                conn.commit();
                System.out.println("Repayment of $" + monthlyRepayment + " successful.");
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
                System.out.println("Error during repayment.");
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean isBlocked(int userId) {
        String sql = "SELECT * FROM loans WHERE user_id = ? AND status = 'active' AND outstanding_balance > 0 AND created_at <= date('now', '-repayment_period months')";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
}
