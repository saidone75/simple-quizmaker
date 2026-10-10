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

class StudentBadgeMigrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"jdbc:h2:mem:student-badge-migration", "jdbc:sqlite::memory:"})
    void upgradesExistingResultsAndPreservesHistoryAfterQuizDeletion(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url);
             var liquibase = new Liquibase("db/changelog/db.changelog-master.xml",
                     new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            var contexts = new Contexts();
            var labels = new LabelExpression();
            int oldChanges = (int) liquibase.listUnrunChangeSets(contexts, labels).stream()
                    .takeWhile(c -> !c.getId().equals("009-student-badges")).count();
            liquibase.update(oldChanges, contexts, labels);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO teachers (id,username,password,is_admin,ai_enabled,enabled,created_at) VALUES "
                        + "('00000000-0000-0000-0000-000000000001','badge-admin','unused',TRUE,FALSE,TRUE,CURRENT_TIMESTAMP)");
                statement.executeUpdate("INSERT INTO students (id,full_name,login_keyword,teacher_id) VALUES "
                        + "('00000000-0000-0000-0000-000000000010','Alice','ABCDE','00000000-0000-0000-0000-000000000001')");
                statement.executeUpdate("INSERT INTO quizzes (id,title,emoji,questions,published,teacher_id,created_at,created_by_username) VALUES "
                        + "('00000000-0000-0000-0000-000000000020','Geografia','🌍','[]',TRUE,'00000000-0000-0000-0000-000000000001',CURRENT_TIMESTAMP,'admin')");
                statement.executeUpdate("INSERT INTO quiz_submissions (id,student_id,quiz_id,answers,score,total_questions,unlocked,submitted_at) VALUES "
                        + "('00000000-0000-0000-0000-000000000030','00000000-0000-0000-0000-000000000010',"
                        + "'00000000-0000-0000-0000-000000000020','[]',4,5,FALSE,'2026-10-01 12:00:00')");
            }
            liquibase.update(contexts, labels);
            // Reapplying migrations must not duplicate the imported attempt.
            liquibase.update(contexts, labels);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM quiz_submissions");
                statement.executeUpdate("DELETE FROM quizzes");
                try (var result = statement.executeQuery("SELECT quiz_title,score,total_questions,completed_at,quiz_badges FROM student_quiz_attempts")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("Geografia");
                    assertThat(result.getInt(2)).isEqualTo(4);
                    assertThat(result.getInt(3)).isEqualTo(5);
                    assertThat(result.getString(4)).startsWith("2026-10-01 12:00:00");
                    assertThat(result.getString(5)).isNull();
                    assertThat(result.next()).isFalse();
                }
                statement.executeUpdate("DELETE FROM student_quiz_attempts");
                statement.executeUpdate("DELETE FROM students");
            }
        }
    }
}
