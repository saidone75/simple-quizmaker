package org.saidone.quizmaker.policy;

import lombok.RequiredArgsConstructor;
import org.saidone.quizmaker.config.StudentAuthenticationToken;
import org.saidone.quizmaker.dto.QuestionDto;
import org.saidone.quizmaker.entity.Question;
import org.saidone.quizmaker.entity.Quiz;
import org.saidone.quizmaker.entity.Teacher;
import org.saidone.quizmaker.repository.QuizRepository;
import org.saidone.quizmaker.repository.TeacherRepository;
import org.saidone.quizmaker.repository.UploadedImageRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Component("questionImageAuthorizationPolicy")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuestionImageAuthorizationPolicy {
    private static final String IMAGE_PATH = "/api/quizzes/images/";
    private final UploadedImageRepository uploadedImageRepository;
    private final QuizRepository quizRepository;
    private final TeacherRepository teacherRepository;
    private final TeacherAuthorizationPolicy teacherAuthorizationPolicy;

    public boolean canRead(UUID imageId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return false;
        if (authentication instanceof StudentAuthenticationToken studentToken) {
            var student = studentToken.getPrincipal();
            return student != null && references(
                    quizRepository.findByTeacherAndPublishedTrueAndArchivedFalseOrderByCreatedAtDesc(student.getTeacher()), imageId);
        }
        if (authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_TEACHER"))) return false;
        return teacherRepository.findByUsernameIgnoreCase(authentication.getName())
                .map(teacher -> canUse(imageId, teacher)).orElse(false);
    }

    public boolean canDelete(UUID imageId, Teacher teacher) {
        if (!teacherAuthorizationPolicy.isTeacher(teacher) || !canUse(imageId, teacher)) return false;
        // A shared reference must not let one teacher destroy another teacher's image.
        return quizRepository.findAll().stream()
                .filter(quiz -> references(List.of(quiz), imageId))
                .allMatch(quiz -> quiz.getTeacher().getId().equals(teacher.getId()));
    }

    public void validateQuestions(List<QuestionDto> questions, Teacher teacher) {
        for (var question : questions) {
            validateReference(question.getImageId(), teacher);
            validateReference(imageIdFromUrl(question.getImageUrl()), teacher);
        }
    }

    private void validateReference(String value, Teacher teacher) {
        if (value == null || value.isBlank()) return;
        UUID imageId;
        try {
            imageId = UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new AccessDeniedException("Riferimento immagine non valido");
        }
        if (!canUse(imageId, teacher)) throw new AccessDeniedException("Accesso immagine non consentito");
    }

    private boolean canUse(UUID imageId, Teacher teacher) {
        return uploadedImageRepository.findById(imageId).map(image -> {
            if (teacher.getId().equals(image.getTeacherId())) return true;
            return references(quizRepository.findAllByTeacherOrderByCreatedAtDesc(teacher), imageId);
        }).orElse(false);
    }

    private boolean references(List<Quiz> quizzes, UUID imageId) {
        return quizzes.stream().filter(quiz -> quiz.getQuestions() != null)
                .flatMap(quiz -> quiz.getQuestions().stream())
                .anyMatch(question -> references(question, imageId));
    }

    private boolean references(Question question, UUID imageId) {
        if (question == null) return false;
        return matches(question.getImageId(), imageId) || matches(imageIdFromUrl(question.getImageUrl()), imageId);
    }

    private boolean matches(String value, UUID imageId) {
        if (value == null) return false;
        try {
            return imageId.equals(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String imageIdFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            var path = URI.create(url.trim()).getPath();
            return path != null && path.startsWith(IMAGE_PATH) ? path.substring(IMAGE_PATH.length()) : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
