package org.saidone.quizmaker.service;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import lombok.val;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class TeacherPublicQuizzesMigrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"jdbc:h2:mem:teacher-public-preference", "jdbc:sqlite::memory:"})
    void migrationInitializesExistingTeachers(String url) throws Exception {
        try (val connection = DriverManager.getConnection(url)) {
            try (val statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE teachers (id VARCHAR(36) PRIMARY KEY)");
                statement.executeUpdate("INSERT INTO teachers (id) VALUES ('legacy')");
                statement.executeUpdate("CREATE TABLE quizzes (id VARCHAR(36) PRIMARY KEY, public_access BOOLEAN)");
                statement.executeUpdate("INSERT INTO quizzes (id, public_access) VALUES ('legacy', NULL), ('public', TRUE)");
            }
            try (val liquibase = new Liquibase("db/public-preferences-migration.xml",
                    new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
                liquibase.update(new Contexts(), new LabelExpression());
                try (val statement = connection.createStatement();
                     val result = statement.executeQuery("SELECT public_access FROM quizzes ORDER BY id")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject(1)).isNotNull();
                    assertThat(result.getBoolean(1)).isFalse();
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                }
                try (val statement = connection.createStatement();
                     val result = statement.executeQuery("SELECT public_quizzes_enabled FROM teachers WHERE id = 'legacy'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject(1)).isNotNull();
                    assertThat(result.getBoolean(1)).isFalse();
                }
            }
        }
    }
}
