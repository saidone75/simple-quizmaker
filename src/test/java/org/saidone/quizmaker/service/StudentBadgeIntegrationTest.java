package org.saidone.quizmaker.service;

import ai.djl.repository.zoo.ZooModel;
import jakarta.persistence.EntityManager;
import lombok.val;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.BinaryBitmap;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.saidone.quizmaker.dto.QuestionDto;
import org.saidone.quizmaker.dto.QuizDto;
import org.saidone.quizmaker.config.StudentAuthenticationToken;
import org.saidone.quizmaker.dto.QuizBadgeDefinition;
import org.saidone.quizmaker.dto.StudentBadgeDto;
import org.saidone.quizmaker.entity.*;
import org.saidone.quizmaker.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;


import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:badge-integration;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=true",
        "app.turnstile.enabled=false",
        "app.image-cleanup.enabled=false",
        "app.db-backup.enabled=false"
})
@ActiveProfiles("test")
@Transactional
class StudentBadgeIntegrationTest {
    @MockitoBean(name = "textEmbeddingModel") ZooModel<String, float[]> model;
    @Autowired WebApplicationContext context;
    @Autowired StudentRepository students;
    @Autowired TeacherRepository teachers;
    @Autowired QuizRepository quizzes;
    @Autowired UploadedImageRepository uploadedImages;
    @Autowired QuestionImageStorageService imageStorageService;
    @Autowired QuizSubmissionRepository submissions;
    @Autowired StudentQuizAttemptRepository attempts;
    @Autowired QuizSubmissionService submissionService;
    @Autowired StudentBadgeService badgeService;
    @Autowired StudentService studentService;
    @Autowired TeacherLifecycleService teacherLifecycleService;
    @Autowired QuizService quizService;
    @Autowired EntityManager entityManager;
    @Autowired ShortLinkService shortLinkService;
    @Autowired ObjectMapper objectMapper;
    MockMvc mvc;
    Teacher teacher;
    Student student;
    Quiz quiz;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        teacher = teachers.saveAndFlush(Teacher.builder().username("badge-teacher").password("unused").build());
        student = students.saveAndFlush(Student.builder().id(UUID.randomUUID()).fullName("Alice <student>")
                .loginKeyword("BADGE").teacher(teacher).build());
        val question = new Question();
        question.setText("Qual è la capitale d’Italia?");
        question.setOptions(List.of("Roma", "Parigi"));
        question.setAnswer(0);
        quiz = quizzes.saveAndFlush(Quiz.builder().title("Geografia <quiz>").emoji("🌍").teacher(teacher)
                .createdByUsername(teacher.getUsername()).questions(List.of(question)).published(true)
                .badges(List.of(new QuizBadgeDefinition("cartographer", "Cartografo provetto <premio>", "🗺️", 80),
                        new QuizBadgeDefinition("explorer", "Grande esploratore", "🧭", 100))).build());
        SecurityContextHolder.getContext().setAuthentication(new StudentAuthenticationToken(student));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private enum SessionRevocationAction { DISABLE, CHANGE_ADMIN_ROLE, RESET_PASSWORD }

    @ParameterizedTest
    @EnumSource(SessionRevocationAction.class)
    void accountChangesInvalidateEveryPreviouslyAuthenticatedTeacherSession(SessionRevocationAction action) throws Exception {
        val registry = context.getBean(SessionRegistry.class);
        teacher.setAdmin(true);
        teacher.setPassword(context.getBean(PasswordEncoder.class)
                .encode("session-test-password"));
        teachers.saveAndFlush(teacher);
        val sessions = new ArrayList<MockHttpSession>();
        try {
            for (int i = 0; i < 2; i++) {
                SecurityContextHolder.clearContext();
                val login = mvc.perform(post("/teacher/login").with(csrf()).param("username", teacher.getUsername())
                                .param("password", "session-test-password"))
                        .andExpect(status().is3xxRedirection()).andReturn();
                sessions.add((MockHttpSession) login.getRequest().getSession(false));
            }
            val actingAdmin = teachers.saveAndFlush(Teacher.builder().username("session-admin")
                    .password("unused").admin(true).enabled(true).build());
            val flag = switch (action) {
                case DISABLE -> "enabled";
                case CHANGE_ADMIN_ROLE -> "admin";
                case RESET_PASSWORD -> "reset-password";
            };
            mvc.perform(post("/teacher/system/teachers/{id}/{flag}", teacher.getId(), flag)
                            .with(user(actingAdmin.getUsername()).roles("TEACHER", "ADMIN")).with(csrf())
                            .param(flag, "false"))
                    .andExpect(status().is3xxRedirection());
            if (action == SessionRevocationAction.RESET_PASSWORD) {
                assertThat(context.getBean(PasswordEncoder.class).matches("session-test-password",
                        teachers.findById(teacher.getId()).orElseThrow().getPassword())).isFalse();
            }
            // The test transaction rolls back; simulate only the post-commit session revocation.
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            for (val session : sessions) {
                SecurityContextHolder.clearContext();
                mvc.perform(get("/api/teacher/logs/tail").session(session))
                        .andExpect(status().is3xxRedirection())
                        .andExpect(redirectedUrl("/teacher/login?expired=true"));
                assertThat(session.isInvalid()).isTrue();
            }
        } finally {
            sessions.forEach(session -> registry.removeSessionInformation(session.getId()));
        }
    }

    @Test
    void profilePreferenceControlsThePublicAccessButtonAndNewPublications() throws Exception {
        val id = quiz.getId();
        val settingsUrl = String.format("/teacher/quiz/%s/public", id);
        assertThat(teachers.findById(teacher.getId()).orElseThrow().isPublicQuizzesEnabled()).isFalse();
        val profile = mvc.perform(get("/teacher/profile").with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(model().attribute("teacherPublicQuizzesEnabled", false))
                .andExpect(content().string(containsString("Abilita la creazione di quiz anonimi"))).andReturn();
        val html = profile.getResponse().getContentAsString();
        assertThat(html.indexOf("Quiz anonimi")).isBetween(html.indexOf("Editor dei quiz"), html.indexOf("Sicurezza account"));
        mvc.perform(get("/teacher").with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString(settingsUrl))));
        mvc.perform(put("/api/quizzes/{id}/public-access", id).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/teacher/profile/public-quizzes").with(user(teacher.getUsername()).roles("TEACHER"))
                        .param("publicQuizzesEnabled", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/teacher/profile/public-quizzes").with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).param("publicQuizzesEnabled", "true"))
                .andExpect(redirectedUrl("/teacher/profile"));
        entityManager.flush();
        entityManager.clear();
        assertThat(teachers.findById(teacher.getId()).orElseThrow().isPublicQuizzesEnabled()).isTrue();
        mvc.perform(get("/teacher").with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString(settingsUrl)));
        mvc.perform(put("/api/quizzes/{id}/public-access", id).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/teacher/profile/public-quizzes").with(user(teacher.getUsername()).roles("TEACHER")).with(csrf()))
                .andExpect(redirectedUrl("/teacher/profile"));
        entityManager.flush();
        entityManager.clear();
        assertThat(teachers.findById(teacher.getId()).orElseThrow().isPublicQuizzesEnabled()).isFalse();
        mvc.perform(get("/teacher").with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString(settingsUrl))));
        mvc.perform(get("/play/quizzes/{id}", id).with(anonymous())).andExpect(status().isOk());
        mvc.perform(put("/api/quizzes/{id}/public-access", id).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    void publicQuizIsIndependentFromClassPublicationAndDoesNotRecordParticipants() throws Exception {
        teacher.setPublicQuizzesEnabled(true);
        teachers.saveAndFlush(teacher);
        val id = quiz.getId();
        quiz.setPublished(false);
        quizzes.saveAndFlush(quiz);
        val studentCount = students.count();
        val submissionCount = submissions.count();
        val attemptCount = attempts.count();
        mvc.perform(get("/play/quizzes/{id}", id).with(anonymous())).andExpect(status().isNotFound());
        mvc.perform(put("/api/quizzes/{id}/public-access", id).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicAccess").value(true))
                .andExpect(jsonPath("$.published").value(false));
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/play/quizzes/{id}", id).with(anonymous()))
                    .andExpect(status().isOk()).andExpect(view().name("public-quiz"))
                    .andExpect(content().string(containsString("public-quiz-data")))
                    .andExpect(content().string(containsString("public-replay-btn")))
                    .andExpect(content().string(not(containsString(teacher.getUsername()))))
                    .andExpect(content().string(not(containsString("student-back-home-btn"))))
                    .andExpect(content().string(not(containsString("screen-student"))))
                    .andExpect(content().string(not(containsString("Accesso libero"))))
                    .andExpect(content().string(not(containsString("login-screen"))))
                    .andExpect(content().string(not(containsString("/student/badges"))));
        }
        mvc.perform(get("/api/quizzes/{id}", id).with(anonymous())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/teacher/quiz/{id}/public", id).with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("public-quiz-link")));
        mvc.perform(get("/teacher").with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("btn-icon-action btn-retry-home")))
                .andExpect(content().string(not(containsString("🥷 Accesso anonimo attivo"))));
        assertThat(students.count()).isEqualTo(studentCount);
        assertThat(submissions.count()).isEqualTo(submissionCount);
        assertThat(attempts.count()).isEqualTo(attemptCount);
        teacher.setEnabled(false);
        teachers.saveAndFlush(teacher);
        mvc.perform(get("/play/quizzes/{id}", id).with(anonymous())).andExpect(status().isNotFound());
        teacher.setEnabled(true);
        teachers.saveAndFlush(teacher);
        mvc.perform(put("/api/quizzes/{id}/public-access", id).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/play/quizzes/{id}", id).with(anonymous())).andExpect(status().isNotFound());
        mvc.perform(get("/teacher").with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("btn-icon-action btn-retry-home")));
    }

    @Test
    void publicAccessRequiresOwnershipAndCsrfAndAllowsEditing() throws Exception {
        teacher.setPublicQuizzesEnabled(true);
        teachers.saveAndFlush(teacher);
        val id = quiz.getId();
        val other = teachers.saveAndFlush(Teacher.builder().username("public-other").password("unused").build());
        mvc.perform(put("/api/quizzes/{id}/public-access", id).with(user(other.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/quizzes/{id}/public-access", id).with(user(teacher.getUsername()).roles("TEACHER"))
                        .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        quiz.setPublished(false);
        quiz.setPublicAccess(true);
        quizzes.saveAndFlush(quiz);
        val request = QuizDto.Request.builder().title("Changed title").emoji(quiz.getEmoji())
                .questions(List.of(objectMapper.convertValue(quiz.getQuestions().getFirst(), QuestionDto.class))).build();
        val json = objectMapper.writeValueAsString(request);
        mvc.perform(put("/api/quizzes/{id}", id).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicAccess").value(true));
        mvc.perform(get("/play/quizzes/{id}", id).with(anonymous()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Changed title")));
        mvc.perform(get("/teacher/quiz/{id}/edit", id).with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk());
        mvc.perform(get("/teacher/quiz/{id}/public", id).with(user(other.getUsername()).roles("TEACHER")))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/quizzes/{id}/archived", id).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content("{\"published\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicAccess").value(false));
        mvc.perform(get("/play/quizzes/{id}", id).with(anonymous())).andExpect(status().isNotFound());
        mvc.perform(put("/api/quizzes/{id}/public-access", id).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publicImagesAreLimitedToTheEnabledQuizAndRevokedWithIt() throws Exception {
        val imageId = UUID.randomUUID();
        val path = Files.createTempFile("quizmaker-public-test-", ".png");
        val bytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        try {
            Files.write(path, bytes);
            val image = new UploadedImage();
            image.setId(imageId);
            image.setTeacherId(teacher.getId());
            image.setFilePath(path.toString());
            uploadedImages.saveAndFlush(image);
            quiz.setPublicAccess(true);
            quiz.getQuestions().getFirst().setImageUrl(String.format("/api/quizzes/images/%s", imageId));
            quizzes.saveAndFlush(quiz);
            val page = mvc.perform(get("/play/quizzes/{id}", quiz.getId()).with(anonymous()))
                    .andExpect(status().isOk()).andReturn();
            val publicQuiz = (QuizDto.PublicQuiz) page.getModelAndView().getModel().get("publicQuiz");
            assertThat(publicQuiz.questions().getFirst().getImageUrl())
                    .isEqualTo(String.format("/play/quizzes/%s/images/%s", quiz.getId(), imageId));
            assertThat(publicQuiz.questions().getFirst().getImageId()).isNull();
            mvc.perform(get("/play/quizzes/{quizId}/images/{id}", quiz.getId(), imageId).with(anonymous()))
                    .andExpect(status().isOk()).andExpect(content().bytes(bytes));
            mvc.perform(get("/play/quizzes/{quizId}/images/{id}", quiz.getId(), UUID.randomUUID()).with(anonymous()))
                    .andExpect(status().isNotFound());
            quiz.setPublicAccess(false);
            quizzes.saveAndFlush(quiz);
            mvc.perform(get("/play/quizzes/{quizId}/images/{id}", quiz.getId(), imageId).with(anonymous()))
                    .andExpect(status().isNotFound());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void shortLinksAreStableAndPreservePublicQuizAccessRules() throws Exception {
        quiz.setPublicAccess(true);
        quizzes.saveAndFlush(quiz);
        val target = "/play/quizzes/" + quiz.getId();
        val code = shortLinkService.getOrCreate(target);
        assertThat(code).matches("[a-z]{8}");
        entityManager.flush();
        entityManager.clear();
        assertThat(shortLinkService.getOrCreate(target)).isEqualTo(code);
        assertThat(shortLinkService.resolve(code)).contains(target);
        mvc.perform(get("/q/{code}", code).with(anonymous()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("http://localhost" + target))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));
        mvc.perform(get(target).with(anonymous())).andExpect(status().isOk());
        mvc.perform(get("/teacher/quiz/{id}/public", quiz.getId()).with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("http://localhost/q/" + code)));
        val stored = quizzes.findById(quiz.getId()).orElseThrow();
        stored.setPublicAccess(false);
        quizzes.saveAndFlush(stored);
        mvc.perform(get(target).with(anonymous())).andExpect(status().isNotFound());
        assertThat(shortLinkService.getOrCreate(target)).isEqualTo(code);
        stored.setPublicAccess(true);
        quizzes.saveAndFlush(stored);
        mvc.perform(get(target).with(anonymous())).andExpect(status().isOk());
        stored.setArchived(true);
        quizzes.saveAndFlush(stored);
        mvc.perform(get(target).with(anonymous())).andExpect(status().isNotFound());
        mvc.perform(get("/q/unknowncodeab").with(anonymous())).andExpect(status().isNotFound());
        mvc.perform(get("/q/bad-code").with(anonymous())).andExpect(status().isNotFound());
    }

    @Test
    void qrCodeContainsThePublicLinkAndIsRestrictedToTheOwner() throws Exception {
        quiz.setPublicAccess(true);
        quizzes.saveAndFlush(quiz);
        val result = mvc.perform(get("/teacher/quiz/{id}/public-qr", quiz.getId())
                        .with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andReturn();
        val image = ImageIO.read(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
        val pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        val source = new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels);
        val bitmap = new BinaryBitmap(new HybridBinarizer(source));
        assertThat(new QRCodeReader().decode(bitmap).getText())
                .isEqualTo("http://localhost/q/" + shortLinkService.getOrCreate("/play/quizzes/" + quiz.getId()));
        mvc.perform(get("/teacher/quiz/{id}/public-qr", quiz.getId()).with(anonymous()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void publishedQuizMustBeUnpublishedBeforeEditingAndIsLockedAgainAfterRepublishing() throws Exception {
        val quizId = quiz.getId();
        val originalTitle = quiz.getTitle();
        val question = objectMapper.convertValue(quiz.getQuestions().getFirst(), QuestionDto.class);
        val edit = QuizDto.Request.builder().title("Titolo modificato").emoji(quiz.getEmoji())
                .questions(List.of(question)).badges(List.of()).build();
        val json = objectMapper.writeValueAsString(edit);
        mvc.perform(get("/teacher/quiz/{id}/edit", quizId).with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/teacher"));
        mvc.perform(put("/api/quizzes/{id}", quizId).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Spubblica il quiz dalla dashboard prima di modificarlo."));
        entityManager.flush();
        entityManager.clear();
        val unchanged = quizzes.findById(quizId).orElseThrow();
        assertThat(unchanged.getTitle()).isEqualTo(originalTitle);
        assertThat(unchanged.getVersion()).isEqualTo(1);
        assertThat(unchanged.getBadges()).hasSize(2);
        mvc.perform(put("/api/quizzes/{id}/publication", quizId).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content("{\"published\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/teacher/quiz/{id}/edit", quizId).with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("data-published=\"false\"")))
                .andExpect(content().string(not(containsString("Per modificarlo, spubblicalo dalla dashboard."))));
        mvc.perform(put("/api/quizzes/{id}", quizId).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Titolo modificato"));
        mvc.perform(put("/api/quizzes/{id}/publication", quizId).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content("{\"published\":true}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/quizzes/{id}", quizId).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void questionChangesMarkPreviousSubmissionsObsoleteUntilAnUnlockedRetry() throws Exception {
        val quizId = quiz.getId();
        val studentId = student.getId();
        submissionService.submit(quizId, student, List.of(0));
        entityManager.flush();
        assertThat(submissions.findByStudentIdAndQuizId(studentId, quizId).orElseThrow().getVersion()).isEqualTo(1);
        val question = objectMapper.convertValue(quiz.getQuestions().getFirst(), QuestionDto.class);
        question.setOptions(List.of("Parigi", "Roma"));
        question.setAnswer(1);
        val edit = QuizDto.Request.builder().title(quiz.getTitle()).emoji(quiz.getEmoji())
                .questions(List.of(question)).build();
        SecurityContextHolder.getContext().setAuthentication(teacherAuthentication());
        quizService.updatePublicationStatus(quizId, false, teacher);
        assertThat(quizService.update(quizId, edit, teacher).getVersion()).isEqualTo(2);
        quizService.updatePublicationStatus(quizId, true, teacher);
        entityManager.flush();
        entityManager.clear();
        val activeStudent = students.findById(studentId).orElseThrow();
        val activeTeacher = teachers.findById(teacher.getId()).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(new StudentAuthenticationToken(activeStudent));
        val review = submissionService.review(quizId, activeStudent);
        assertThat(review.obsolete()).isTrue();
        assertThat(review.answers()).isEmpty();
        assertThat(review.score()).isEqualTo(1);
        assertThat(review.version()).isEqualTo(1);
        assertThat(review.currentVersion()).isEqualTo(2);
        mvc.perform(get("/api/quizzes/{id}/review", quizId).with(authentication(new StudentAuthenticationToken(activeStudent))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.obsolete").value(true))
                .andExpect(jsonPath("$.answers").isEmpty()).andExpect(jsonPath("$.score").value(1));
        mvc.perform(get("/").with(authentication(new StudentAuthenticationToken(activeStudent))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Versione precedente")));
        mvc.perform(post("/api/quizzes/{id}/submit", quizId).with(authentication(new StudentAuthenticationToken(activeStudent)))
                        .with(csrf()).contentType("application/json").content("{\"answers\":[1]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/teacher/results").with(user(activeTeacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Versione precedente (v1)")))
                .andExpect(content().string(containsString("Non ci sono ancora abbastanza dati.")));
        SecurityContextHolder.getContext().setAuthentication(teacherAuthentication());
        submissionService.unlockQuizForStudent(studentId, quizId, activeTeacher);
        SecurityContextHolder.getContext().setAuthentication(new StudentAuthenticationToken(activeStudent));
        submissionService.submit(quizId, activeStudent, List.of(1));
        entityManager.flush();
        entityManager.clear();
        val retried = submissions.findByStudentIdAndQuizId(studentId, quizId).orElseThrow();
        assertThat(retried.getVersion()).isEqualTo(2);
        assertThat(retried.getUnlocked()).isFalse();
        val currentReview = submissionService.review(quizId, students.findById(studentId).orElseThrow());
        assertThat(currentReview.obsolete()).isFalse();
        assertThat(currentReview.answers()).containsExactly(1);
    }

    @Test
    void versionChangesOnlyWhenQuestionsChangeAndSubmissionUsesCurrentVersion() {
        val quizId = quiz.getId();
        val question = objectMapper.convertValue(quiz.getQuestions().getFirst(), QuestionDto.class);
        val edit = QuizDto.Request.builder().title("Nuovo titolo").emoji("🧪")
                .badges(List.of()).questions(List.of(question)).build();
        SecurityContextHolder.getContext().setAuthentication(teacherAuthentication());
        quizService.updatePublicationStatus(quizId, false, teacher);
        assertThat(quizService.update(quizId, edit, teacher).getVersion()).isEqualTo(1);
        assertThat(quizService.updatePublicationStatus(quizId, true, teacher).getVersion()).isEqualTo(1);
        quizService.updatePublicationStatus(quizId, false, teacher);
        question.setText("Domanda aggiornata");
        assertThat(quizService.update(quizId, edit, teacher).getVersion()).isEqualTo(2);
        assertThat(quizService.update(quizId, edit, teacher).getVersion()).isEqualTo(2);
        question.setFeedback("Nuova spiegazione");
        assertThat(quizService.update(quizId, edit, teacher).getVersion()).isEqualTo(3);
        quizService.updatePublicationStatus(quizId, true, teacher);
        // No submit-time version guard: the server stores the current version.
        SecurityContextHolder.getContext().setAuthentication(new StudentAuthenticationToken(student));
        submissionService.submit(quizId, student, List.of(0));
        entityManager.flush();
        assertThat(submissions.findByStudentIdAndQuizId(student.getId(), quizId).orElseThrow().getVersion()).isEqualTo(3);
    }

    @Test
    void unlockedRetryPersistsTheNewSubmissionDateAndKeepsItConsistentWithTheAttempt() {
        val quizId = quiz.getId();
        val studentId = student.getId();
        val teacherId = teacher.getId();
        submissionService.submit(quizId, student, List.of(0));
        entityManager.flush();
        val originalId = submissions.findByStudentIdAndQuizId(studentId, quizId).orElseThrow().getId();
        val oldDate = LocalDateTime.of(2025, 1, 1, 12, 0);
        // Simulate a previous day's persisted submission without sleeping or changing the clock.
        entityManager.createNativeQuery("UPDATE quiz_submissions SET submitted_at = :oldDate WHERE id = :id")
                .setParameter("oldDate", "2025-01-01 12:00:00")
                .setParameter("id", originalId.toString()).executeUpdate();
        entityManager.clear();

        val activeTeacher = teachers.findById(teacherId).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(teacherAuthentication());
        submissionService.unlockQuizForStudent(studentId, quizId, activeTeacher);
        entityManager.flush();
        entityManager.clear();
        assertThat(submissions.findByStudentIdAndQuizId(studentId, quizId).orElseThrow().getSubmittedAt())
                .isEqualTo(oldDate);

        val activeStudent = students.findById(studentId).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(new StudentAuthenticationToken(activeStudent));
        val beforeRetry = LocalDateTime.now(ZoneId.systemDefault()).minusSeconds(1);
        submissionService.submit(quizId, activeStudent, List.of(1));
        entityManager.flush();
        entityManager.clear();

        val saved = submissions.findByStudentIdAndQuizId(studentId, quizId).orElseThrow();
        assertThat(saved.getId()).isEqualTo(originalId);
        assertThat(saved.getAnswers()).containsExactly(1);
        assertThat(saved.getScore()).isZero();
        assertThat(saved.getSubmittedAt()).isAfter(beforeRetry);
        val history = attempts.findByStudentIdOrderByCompletedAtAscIdAsc(studentId);
        assertThat(history).hasSize(2);
        assertThat(saved.getSubmittedAt()).isEqualTo(history.getLast().getCompletedAt());
        assertThat(submissionService.review(quizId, activeStudent).submittedAt()).isEqualTo(saved.getSubmittedAt());
        assertThat(submissionService.findAllResults(activeTeacher)).singleElement()
                .satisfies(result -> assertThat(result.submittedAt()).isEqualTo(saved.getSubmittedAt()));
    }

    @Test
    void directSubmissionInsertionStillInitializesItsDate() {
        val saved = submissions.saveAndFlush(QuizSubmission.builder().student(student).quiz(quiz)
                .answers(List.of(0)).score(1).totalQuestions(1).build());
        val id = saved.getId();
        entityManager.clear();

        assertThat(submissions.findById(id).orElseThrow().getSubmittedAt()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void imageRemovalKeepsStoredQuizzesSafeUntilTheirLastReferenceIsSaved(boolean sharedWithAnotherQuiz) throws Exception {
        val image = new UploadedImage();
        val imageId = UUID.randomUUID();
        image.setId(imageId);
        image.setTeacherId(teacher.getId());
        image.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(2));
        image.setFilePath(String.format("target/removal-test-missing-%s.png", imageId));
        uploadedImages.saveAndFlush(image);
        quiz.getQuestions().getFirst().setImageId(imageId.toString());
        quiz.getQuestions().getFirst().setImageUrl(String.format("/api/quizzes/images/%s", imageId));
        quiz.setPublished(false);
        quizzes.saveAndFlush(quiz);
        if (sharedWithAnotherQuiz) {
            val otherQuestion = new Question();
            otherQuestion.setImageUrl(String.format("/api/quizzes/images/%s", imageId));
            quizzes.saveAndFlush(Quiz.builder().title("Another quiz using the image").emoji("🖼️")
                    .teacher(teacher).createdByUsername(teacher.getUsername())
                    .questions(List.of(otherQuestion)).published(true).build());
        }

        mvc.perform(delete("/api/quizzes/images/{id}", imageId)
                        .with(user(teacher.getUsername()).roles("TEACHER")).with(csrf()))
                .andExpect(status().isForbidden());
        val scheduler = new OrphanQuestionImageCleanupScheduler(quizzes, uploadedImages, imageStorageService);
        scheduler.cleanupOrphanImages();
        assertThat(uploadedImages.findById(imageId)).isPresent();
        assertThat(quizzes.findById(quiz.getId()).orElseThrow().getQuestions().getFirst().getImageId())
                .isEqualTo(imageId.toString());

        val question = objectMapper.convertValue(quiz.getQuestions().getFirst(), QuestionDto.class);
        question.setImageId(null);
        question.setImageUrl(null);
        val edit = QuizDto.Request.builder().title(quiz.getTitle()).emoji(quiz.getEmoji())
                .questions(List.of(question)).build();
        val json = objectMapper.writeValueAsString(edit);
        mvc.perform(put("/api/quizzes/{id}", quiz.getId())
                        .with(user(teacher.getUsername()).roles("TEACHER")).with(csrf())
                        .contentType("application/json").content(json))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        val savedQuestion = quizzes.findById(quiz.getId()).orElseThrow().getQuestions().getFirst();
        assertThat(savedQuestion.getImageId()).isNull();
        assertThat(savedQuestion.getImageUrl()).isNull();
        assertThat(uploadedImages.findById(imageId)).isPresent();

        scheduler.cleanupOrphanImages();

        assertThat(uploadedImages.existsById(imageId)).isEqualTo(sharedWithAnotherQuiz);
    }

    @Test
    void imageCleanupTraversesDeletedBatchesAndPreservesArchivedQuizReferences() {
        val firstImageId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        val lastImageId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        for (int i = 0; i < 205; i++) {
            val image = new UploadedImage();
            image.setId(i == 204 ? lastImageId : UUID.fromString(String.format("00000000-0000-0000-0000-%012x", i + 1)));
            image.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(2));
            image.setFilePath(String.format("target/cleanup-test-missing-%s", image.getId()));
            uploadedImages.save(image);
        }
        uploadedImages.flush();
        quiz.getQuestions().getFirst().setImageId(firstImageId.toString());
        quizzes.saveAndFlush(quiz);
        val archivedQuestion = new Question();
        archivedQuestion.setImageUrl(String.format("/api/quizzes/images/%s", lastImageId));
        quizzes.saveAndFlush(Quiz.builder().title("Archived quiz").emoji("📦")
                .teacher(teacher).createdByUsername(teacher.getUsername())
                .questions(List.of(archivedQuestion)).archived(true).build());
        entityManager.clear();
        val scheduler = new OrphanQuestionImageCleanupScheduler(quizzes, uploadedImages, imageStorageService);

        scheduler.cleanupOrphanImages();

        assertThat(uploadedImages.findAll()).extracting(UploadedImage::getId)
                .containsExactlyInAnyOrder(firstImageId, lastImageId);
    }

    @Test
    void submissionAndBadgeCollectionWorkWithDetachedAuthenticationPrincipal() {
        entityManager.flush();
        entityManager.clear();
        val principal = students.findById(student.getId()).orElseThrow();
        assertThat(org.hibernate.Hibernate.isInitialized(principal.getTeacher())).isFalse();
        entityManager.clear();
        SecurityContextHolder.getContext().setAuthentication(new StudentAuthenticationToken(principal));

        val response = submissionService.submit(quiz.getId(), principal, List.of(0));
        assertThat(response.getScore()).isEqualTo(1);
        assertThat(response.getNewBadges()).extracting(StudentBadgeDto.EarnedBadge::code)
                .contains("FIRST_QUIZ");
        entityManager.flush();
        entityManager.clear();
        assertThat(submissions.findByStudentIdAndQuizId(principal.getId(), quiz.getId())).isPresent();
        assertThat(attempts.findByStudentIdOrderByCompletedAtAscIdAsc(principal.getId())).hasSize(1);
        assertThat(badgeService.forCurrentStudent().completedQuizzes()).isEqualTo(1);
    }

    @Test
    void classBadgePanelSavesOnlyCurrentTeachersSettingsAndSnapshotsNewAttempts() throws Exception {
        val other = teachers.saveAndFlush(Teacher.builder().username("badge-other").password("unused").build());
        mvc.perform(get("/teacher/students").with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Badge della classe")))
                .andExpect(content().string(containsString("Giorni diversi di attività")));
        mvc.perform(post("/teacher/students/badges").with(user(teacher.getUsername()).roles("TEACHER")).with(csrf())
                        .param("badgeId", "FIRST_QUIZ", "FIVE_QUIZZES", "TEN_QUIZZES", "THREE_DAYS", "SEVEN_DAYS")
                        .param("target", "2", "6", "12", "4", "8").param("enabled", "FIRST_QUIZ"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("badgeSettingsSuccess"));
        entityManager.flush();
        entityManager.clear();
        val savedTeacher = teachers.findById(teacher.getId()).orElseThrow();
        assertThat(savedTeacher.getClassBadgeRules()).hasSize(5);
        assertThat(savedTeacher.getClassBadgeRules().getFirst().target()).isEqualTo(2);
        assertThat(teachers.findById(other.getId()).orElseThrow().getClassBadgeRules()).isNull();
        val savedStudent = students.findById(student.getId()).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(new StudentAuthenticationToken(savedStudent));
        submissionService.submit(quiz.getId(), savedStudent, List.of(0));
        entityManager.flush();
        entityManager.clear();
        val history = attempts.findByStudentIdOrderByCompletedAtAscIdAsc(student.getId());
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getClassBadgeRules()).isEqualTo(savedTeacher.getClassBadgeRules());
        assertThat(StudentBadgeEvaluator.evaluate(savedStudent, history).badges())
                .extracting(StudentBadgeDto.EarnedBadge::code).doesNotContain("FIRST_QUIZ");
    }

    @Test
    void allClassBadgesCanBeDisabledWithoutAnEnabledFormParameter() throws Exception {
        mvc.perform(post("/teacher/students/badges").with(user(teacher.getUsername()).roles("TEACHER")).with(csrf())
                        .param("badgeId", "FIRST_QUIZ", "FIVE_QUIZZES", "TEN_QUIZZES", "THREE_DAYS", "SEVEN_DAYS")
                        .param("target", "1", "5", "10", "3", "7"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("badgeSettingsSuccess"));
        entityManager.flush();
        entityManager.clear();
        assertThat(teachers.findById(teacher.getId()).orElseThrow().getClassBadgeRules())
                .hasSize(5).allSatisfy(rule -> assertThat(rule.enabled()).isFalse());
    }

    @Test
    void classBadgeSettingsRejectInvalidThresholdsAndMissingCsrf() throws Exception {
        mvc.perform(post("/teacher/students/badges").with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/teacher/students/badges").with(user(teacher.getUsername()).roles("TEACHER")).with(csrf())
                        .param("badgeId", "FIRST_QUIZ", "FIVE_QUIZZES", "TEN_QUIZZES", "THREE_DAYS", "SEVEN_DAYS")
                        .param("target", "0", "5", "10", "3", "7"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("badgeSettingsError"));
        assertThat(teachers.findById(teacher.getId()).orElseThrow().getClassBadgeRules()).isNull();
    }

    @Test
    void badgesAndFrozenDefinitionsSurviveAnUnlockedWorseRetryAndQuizDeletion() {
        val first = submissionService.submit(quiz.getId(), student, List.of(0));
        assertThat(first.getNewBadges()).extracting(StudentBadgeDto.EarnedBadge::name)
                .contains("Cartografo provetto <premio>", "Grande esploratore");
        entityManager.flush();
        entityManager.clear();
        assertThat(attempts.findByStudentIdOrderByCompletedAtAscIdAsc(student.getId()))
                .singleElement().satisfies(a -> assertThat(a.getQuizBadges()).containsExactlyElementsOf(quiz.getBadges()));

        val savedQuiz = quizzes.findById(quiz.getId()).orElseThrow();
        savedQuiz.setBadges(List.of());
        quizzes.save(savedQuiz);
        val submission = submissions.findByStudentIdAndQuizId(student.getId(), quiz.getId()).orElseThrow();
        submission.setUnlocked(true);
        submissions.save(submission);
        assertThat(submissionService.submit(quiz.getId(), student, List.of(1)).getNewBadges()).isEmpty();

        SecurityContextHolder.getContext().setAuthentication(teacherAuthentication());
        quizService.delete(quiz.getId(), teacher);
        entityManager.flush();
        entityManager.clear();
        val collection = badgeService.forTeacher(student.getId(), teacher);
        assertThat(collection.badges()).extracting(StudentBadgeDto.EarnedBadge::name)
                .contains("Cartografo provetto <premio>", "Grande esploratore");
        assertThat(collection.completedQuizzes()).isEqualTo(1);
    }

    @Test
    void studentPageRendersRealBadgesAndEscapesStudentQuizAndBadgeNames() throws Exception {
        submissionService.submit(quiz.getId(), student, List.of(0));
        mvc.perform(get("/student/badges").with(authentication(new StudentAuthenticationToken(student))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cartografo provetto &lt;premio&gt;")))
                .andExpect(content().string(containsString("Geografia &lt;quiz&gt;")))
                .andExpect(content().string(containsString("Alice &lt;student&gt;")));
        mvc.perform(get("/").with(authentication(new StudentAuthenticationToken(student))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/student/badges\"")))
                .andExpect(content().string(not(containsString("class=\"quiz-pick-badges\""))));
    }

    @Test
    void teacherCanSeeOnlyTheirStudentsAndStudentsCannotUseTeacherPages() throws Exception {
        mvc.perform(get("/teacher/students/{id}/badges", student.getId()).with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk());
        teachers.saveAndFlush(Teacher.builder().username("other-teacher").password("unused").build());
        mvc.perform(get("/teacher/students/{id}/badges", student.getId()).with(user("other-teacher").roles("TEACHER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/teacher/students/{id}/badges", student.getId())
                        .with(authentication(new StudentAuthenticationToken(student))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/student/badges").with(anonymous())).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void deletingStudentRemovesBadgeEvidence() {
        submissionService.submit(quiz.getId(), student, List.of(0));
        studentService.delete(student.getId(), teacher);
        entityManager.flush();
        assertThat(attempts.findByStudentIdOrderByCompletedAtAscIdAsc(student.getId())).isEmpty();
        assertThat(students.findById(student.getId())).isEmpty();
    }

    @Test
    void deletingTeacherRemovesBadgeEvidenceBeforeTheirStudents() {
        submissionService.submit(quiz.getId(), student, List.of(0));
        val admin = teachers.saveAndFlush(Teacher.builder().username("admin").password("unused").admin(true).build());
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "admin", "", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TEACHER"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        teacherLifecycleService.deleteTeacherCompletely(teacher.getId(), admin);
        entityManager.flush();
        assertThat(attempts.findByStudentIdOrderByCompletedAtAscIdAsc(student.getId())).isEmpty();
        assertThat(teachers.findById(teacher.getId())).isEmpty();
    }

    @Test
    void editorLoadsThemedBadgesAndShowsAiActionOnlyWhenEnabled() throws Exception {
        quiz.setPublished(false);
        quizzes.saveAndFlush(quiz);
        mvc.perform(get("/teacher/quiz/{id}/edit", quiz.getId()).with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-initial-badges-json=")))
                .andExpect(content().string(containsString("Cartografo provetto &lt;premio&gt;")))
                .andExpect(content().string(not(containsString("id=\"generate-quiz-badges-btn\""))));
        teacher.setAiEnabled(true);
        teachers.saveAndFlush(teacher);
        mvc.perform(get("/teacher/quiz/{id}/edit", quiz.getId()).with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"generate-quiz-badges-btn\"")));
    }

    @Test
    void apiRejectsAiGenerationWhenTeacherIsDisabledAndRejectsStudentAccess() throws Exception {
        val json = "{\"title\":\"Geografia\",\"questionTexts\":[]}";
        mvc.perform(post("/api/quizzes/badges/generate").with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content(json))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/quizzes/badges/generate").with(authentication(new StudentAuthenticationToken(student)))
                        .with(csrf()).contentType("application/json").content(json))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiSavesThemedBadgesAndRejectsInvalidThresholds() throws Exception {
        quiz.setPublished(false);
        quizzes.saveAndFlush(quiz);
        String json = """
                {"title":"Geografia","emoji":"🌍","questions":[{"text":"Capitale d’Italia?","options":["Roma","Parigi"],"answer":0}],
                 "badges":[{"id":"traveler","name":"Piccolo viaggiatore","emoji":"🌱","minimumPercentage":%d}]}
                """;
        mvc.perform(put("/api/quizzes/{id}", quiz.getId()).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content(json.formatted(101)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/quizzes/{id}", quiz.getId()).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content(json.formatted(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.badges[0].name").value("Piccolo viaggiatore"))
                .andExpect(jsonPath("$.badges[0].minimumPercentage").value(0));
        entityManager.flush();
        entityManager.clear();
        assertThat(quizzes.findById(quiz.getId()).orElseThrow().getBadges())
                .containsExactly(new QuizBadgeDefinition("traveler", "Piccolo viaggiatore", "🌱", 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "3", "4", "true-false"})
    void quizFormatsSurviveApiSaveReloadAndStudentSubmission(String format) throws Exception {
        val mapper = new ObjectMapper();
        val question = new QuestionDto();
        question.setText("Il Sole è un pianeta.");
        if (format.equals("true-false")) {
            question.setType(QuestionType.TRUE_FALSE);
            question.setOptions(List.of("Vero", "Falso"));
        } else {
            question.setOptions(java.util.stream.IntStream.range(0, Integer.parseInt(format))
                    .mapToObj(index -> String.format("Risposta %d", index)).toList());
        }
        question.setAnswer(question.getOptions().size() - 1);
        val request = QuizDto.Request.builder().title(String.format("Quiz %s", format)).emoji("☀️").questions(List.of(question)).build();
        val body = mvc.perform(post("/api/quizzes").with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        val id = UUID.fromString(mapper.readTree(body).path("id").asText());
        entityManager.flush();
        entityManager.clear();
        val restored = quizzes.findById(id).orElseThrow().getQuestions().getFirst();
        assertThat(restored.getType()).isEqualTo(question.getType());
        assertThat(restored.getOptions()).containsExactlyElementsOf(question.getOptions());
        assertThat(restored.getAnswer()).isEqualTo(question.getAnswer());
        mvc.perform(get("/teacher/quiz/{id}/edit", id).with(user(teacher.getUsername()).roles("TEACHER")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("id=\"quiz-format-input\"")));
        mvc.perform(put("/api/quizzes/{id}", id).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/quizzes/{id}/publication", id).with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content("{\"published\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/quizzes/{id}/submit", id).with(authentication(new StudentAuthenticationToken(student)))
                        .with(csrf()).contentType("application/json").content(String.format("{\"answers\":[%d]}", question.getAnswer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(1));
    }

    private org.springframework.security.core.Authentication teacherAuthentication() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(teacher.getUsername(), "",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TEACHER")));
    }
}
