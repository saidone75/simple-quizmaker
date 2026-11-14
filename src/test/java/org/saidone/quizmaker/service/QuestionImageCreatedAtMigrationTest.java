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

class QuestionImageCreatedAtMigrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"jdbc:h2:mem:image-created-at-migration", "jdbc:sqlite::memory:"})
    void migrationBackfillsExistingImagesWithoutChangingTheirPaths(String url) throws Exception {
        try (val connection = DriverManager.getConnection(url)) {
            try (val statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE question_images (id VARCHAR(36) PRIMARY KEY, file_path TEXT NOT NULL)");
                statement.executeUpdate("INSERT INTO question_images (id, file_path) VALUES ('legacy', '/legacy.png')");
            }
            try (val liquibase = new Liquibase("db/changelog/012-question-image-created-at.xml",
                    new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
                liquibase.update(new Contexts(), new LabelExpression());
                try (val statement = connection.createStatement();
                     val result = statement.executeQuery("SELECT file_path, created_at FROM question_images WHERE id = 'legacy'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("/legacy.png");
                    assertThat(result.getTimestamp(2)).isNotNull();
                }
            }
        }
    }
}
