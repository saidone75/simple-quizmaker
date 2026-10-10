package org.saidone.quizmaker.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.saidone.quizmaker.dto.QuizBadgeDefinition;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Immutable evidence for badges, independent of quiz deletion and submission resets. */
@Entity
@Table(name = "student_quiz_attempts", indexes = {
        @Index(name = "idx_student_attempts", columnList = "student_id,completed_at")
})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class StudentQuizAttempt {
    @Id
    @JdbcTypeCode(java.sql.Types.VARCHAR)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false, updatable = false)
    private Student student;

    // Snapshot, deliberately without a foreign key: earned badges survive quiz deletion.
    @JdbcTypeCode(java.sql.Types.VARCHAR)
    @Column(name = "quiz_id", nullable = false, updatable = false)
    private UUID quizId;

    @Column(name = "quiz_title", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String quizTitle;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quiz_badges", updatable = false, columnDefinition = "jsonb")
    private List<QuizBadgeDefinition> quizBadges;

    @Column(nullable = false, updatable = false)
    private int score;

    @Column(name = "total_questions", nullable = false, updatable = false)
    private int totalQuestions;

    @Convert(converter = LocalDateTimeColumnConverter.class)
    @Column(name = "completed_at", nullable = false, updatable = false)
    private LocalDateTime completedAt;
}
