package org.saidone.quizmaker.repository;

import org.saidone.quizmaker.entity.StudentQuizAttempt;
import org.saidone.quizmaker.entity.Teacher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StudentQuizAttemptRepository extends JpaRepository<StudentQuizAttempt, UUID> {
    List<StudentQuizAttempt> findByStudentIdOrderByCompletedAtAscIdAsc(UUID studentId);

    void deleteAllByStudentIdAndStudentTeacher(UUID studentId, Teacher teacher);

    void deleteAllByStudentTeacher(Teacher teacher);
}
