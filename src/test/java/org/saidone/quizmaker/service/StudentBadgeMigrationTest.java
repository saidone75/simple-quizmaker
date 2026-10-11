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

class StudentBadgeMigrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"jdbc:h2:mem:student-badge-migration", "jdbc:sqlite::memory:"})
    void upgradesExistingResultsAndPreservesHistoryAfterQuizDeletion(String url) throws Exception {
        try (val connection = DriverManager.getConnection(url);
             val liquibase = new Liquibase("db/changelog/db.changelog-master.xml",
                     new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            val contexts = new Contexts();
            val labels = new LabelExpression();
            int oldChanges = (int) liquibase.listUnrunChangeSets(contexts, labels).stream()
                    .takeWhile(c -> !c.getId().equals("009-student-badges")).count();
            liquibase.update(oldChanges, contexts, labels);
            try (val statement = connection.createStatement()) {
                val teacherId = "00000000-0000-0000-0000-000000000001";
                val studentId = "00000000-0000-0000-0000-000000000010";
                val quizId = "00000000-0000-0000-0000-000000000020";
                statement.executeUpdate(String.format("""
                        INSERT INTO teachers (id,username,password,is_admin,ai_enabled,enabled,created_at)
                        VALUES ('%s','badge-admin','unused',TRUE,FALSE,TRUE,CURRENT_TIMESTAMP)
                        """, teacherId));
                statement.executeUpdate(String.format("""
                        INSERT INTO students (id,full_name,login_keyword,teacher_id)
                        VALUES ('%s','Alice','ABCDE','%s')
                        """, studentId, teacherId));
                statement.executeUpdate(String.format("""
                        INSERT INTO quizzes (id,title,emoji,questions,published,teacher_id,created_at,created_by_username)
                        VALUES ('%s','Geografia','🌍','[]',TRUE,'%s',CURRENT_TIMESTAMP,'admin')
                        """, quizId, teacherId));
                statement.executeUpdate(String.format("""
                        INSERT INTO quiz_submissions (id,student_id,quiz_id,answers,score,total_questions,unlocked,submitted_at)
                        VALUES ('00000000-0000-0000-0000-000000000030','%s','%s','[]',4,5,FALSE,'2026-10-01 12:00:00')
                        """, studentId, quizId));
            }
            liquibase.update(contexts, labels);
            // reapplying migrations must not duplicate the imported attempt.
            liquibase.update(contexts, labels);
            try (val statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM quiz_submissions");
                statement.executeUpdate("DELETE FROM quizzes");
                try (val result = statement.executeQuery("""
                        SELECT quiz_title,score,total_questions,completed_at,quiz_badges
                        FROM student_quiz_attempts
                        """)) {
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
