package org.saidone.quizmaker.service;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionImageOwnerMigrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"jdbc:h2:mem:image-owner-migration", "jdbc:sqlite::memory:"})
    void migrationAddsNullableOwnerColumn(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var liquibase = new Liquibase("db/changelog/db.changelog-master.xml",
                     new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            liquibase.update(new Contexts(), new LabelExpression());
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO question_images (id, file_path) VALUES ('00000000-0000-0000-0000-000000000001', '/legacy.png')");
                try (var result = statement.executeQuery("SELECT teacher_id FROM question_images")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isNull();
                }
                statement.executeUpdate("UPDATE question_images SET teacher_id = '00000000-0000-0000-0000-000000000002'");
            }
        }
    }
}
