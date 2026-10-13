package org.saidone.quizmaker.service;

import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseBackupSchedulerTest {
    @TempDir
    Path directory;

    private DatabaseBackupScheduler scheduler(Path database, Path backups, int retention) {
        val datasource = new SQLiteDataSource();
        datasource.setUrl("jdbc:sqlite:" + database);
        val scheduler = new DatabaseBackupScheduler(datasource);
        configure(scheduler, datasource, backups, retention);
        return scheduler;
    }

    private DatabaseBackupScheduler scheduler(Path database, Path backups, int retention, boolean failValidation) {
        val datasource = new SQLiteDataSource();
        datasource.setUrl("jdbc:sqlite:" + database);
        val scheduler = new DatabaseBackupScheduler(datasource) {
            @Override
            void validateBackup(Path backupFile) throws SQLException {
                assertTrue(backupFile.toString().endsWith(".tmp"));
                assertTrue(Files.exists(backups.resolve("quizmaker-20200101-000000.db")));
                if (failValidation) {
                    throw new SQLException("invalid backup");
                }
                super.validateBackup(backupFile);
            }
        };
        configure(scheduler, datasource, backups, retention);
        return scheduler;
    }

    private void configure(DatabaseBackupScheduler scheduler, SQLiteDataSource datasource, Path backups, int retention) {
        ReflectionTestUtils.setField(scheduler, "datasourceUrl", datasource.getUrl());
        ReflectionTestUtils.setField(scheduler, "backupDirectory", backups.toString());
        ReflectionTestUtils.setField(scheduler, "retentionCount", retention);
    }

    private List<Path> files(Path backups) throws Exception {
        try (val paths = Files.list(backups)) {
            return paths.sorted().toList();
        }
    }

    @Test
    void restoresCommittedWalDataAndAppliesRetentionOnlyAfterValidation() throws Exception {
        val database = directory.resolve("quizmaker.db");
        val backups = directory.resolve("backup's directory");
        Files.createDirectories(backups);
        val oldBackup = backups.resolve("quizmaker-20200101-000000.db");
        Files.writeString(oldBackup, "previous backup");
        val unrelated = backups.resolve("other.db");
        Files.writeString(unrelated, "unrelated");
        val scheduler = scheduler(database, backups, 1, false);

        try (val source = DriverManager.getConnection("jdbc:sqlite:" + database);
             val statement = source.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("CREATE TABLE answers (id INTEGER PRIMARY KEY, value TEXT)");
            statement.execute("INSERT INTO answers VALUES (1, 'committed in WAL')");
            assertTrue(Files.size(Path.of(database + "-wal")) > 0);
            scheduler.backupDatabase();
            val completed = files(backups).stream().filter(p -> !p.equals(unrelated)).toList();
            assertEquals(1, completed.size());
            assertTrue(completed.getFirst().toString().endsWith(".db"));
            assertFalse(Files.exists(oldBackup));
            try (val restored = DriverManager.getConnection("jdbc:sqlite:" + completed.getFirst());
                 val query = restored.createStatement();
                 val rows = query.executeQuery("SELECT value FROM answers")) {
                assertTrue(rows.next());
                assertEquals("committed in WAL", rows.getString(1));
                assertFalse(rows.next());
            }
        }
        assertEquals("unrelated", Files.readString(unrelated));
    }

    @Test
    void failedValidationPreservesOldBackupsAndRemovesTemporaryFile() throws Exception {
        val database = directory.resolve("quizmaker.db");
        val backups = directory.resolve("backups");
        Files.createDirectories(backups);
        val oldBackup = backups.resolve("quizmaker-20200101-000000.db");
        Files.writeString(oldBackup, "previous backup");
        try (val source = DriverManager.getConnection("jdbc:sqlite:" + database);
             val statement = source.createStatement()) {
            statement.execute("CREATE TABLE answers (id INTEGER PRIMARY KEY)");
        }
        val scheduler = scheduler(database, backups, 1, true);
        scheduler.backupDatabase();
        assertEquals(List.of(oldBackup), files(backups));
        assertEquals("previous backup", Files.readString(oldBackup));
    }

    @Test
    void corruptSourcePreservesOldBackups() throws Exception {
        val database = directory.resolve("quizmaker.db");
        Files.writeString(database, "not a SQLite database");
        val backups = directory.resolve("backups");
        Files.createDirectories(backups);
        val oldBackup = backups.resolve("quizmaker-20200101-000000.db");
        Files.writeString(oldBackup, "previous backup");
        scheduler(database, backups, 1).backupDatabase();
        assertEquals(List.of(oldBackup), files(backups));
    }

    @Test
    void integrityCheckRejectsCorruptFile() throws Exception {
        val corrupt = directory.resolve("corrupt.db");
        Files.writeString(corrupt, "not a SQLite database");
        assertThrows(SQLException.class, () -> scheduler(corrupt, directory, 1).validateBackup(corrupt));
    }

    @Test
    void repeatedBackupsDoNotOverwriteAndRetentionZeroKeepsAll() throws Exception {
        val database = directory.resolve("quizmaker.db");
        try (val source = DriverManager.getConnection("jdbc:sqlite:" + database);
             val statement = source.createStatement()) {
            statement.execute("CREATE TABLE answers (id INTEGER PRIMARY KEY)");
        }
        val backups = directory.resolve("backups");
        val scheduler = scheduler(database, backups, 0);
        scheduler.backupDatabase();
        scheduler.backupDatabase();
        assertEquals(2, files(backups).size());
        for (val backup : files(backups)) {
            assertTrue(backup.toString().endsWith(".db"));
            scheduler.validateBackup(backup);
        }
    }
}
