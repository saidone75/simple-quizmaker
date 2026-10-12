package org.saidone.quizmaker.service;

import ai.djl.repository.zoo.ZooModel;
import jakarta.persistence.EntityManager;
import lombok.val;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @Autowired QuizSubmissionRepository submissions;
    @Autowired StudentQuizAttemptRepository attempts;
    @Autowired QuizSubmissionService submissionService;
    @Autowired StudentBadgeService badgeService;
    @Autowired StudentService studentService;
    @Autowired TeacherLifecycleService teacherLifecycleService;
    @Autowired QuizService quizService;
    @Autowired EntityManager entityManager;
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
                .andExpect(redirectedUrl("/student/login"));
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
        var mapper = new ObjectMapper();
        var question = new QuestionDto();
        question.setText("Il Sole è un pianeta.");
        if (format.equals("true-false")) {
            question.setType(QuestionType.TRUE_FALSE);
            question.setOptions(List.of("Vero", "Falso"));
        } else {
            question.setOptions(java.util.stream.IntStream.range(0, Integer.parseInt(format))
                    .mapToObj(index -> "Risposta " + index).toList());
        }
        question.setAnswer(question.getOptions().size() - 1);
        var request = QuizDto.Request.builder().title("Quiz " + format).emoji("☀️").questions(List.of(question)).build();
        var body = mvc.perform(post("/api/quizzes").with(user(teacher.getUsername()).roles("TEACHER"))
                        .with(csrf()).contentType("application/json").content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var id = UUID.fromString(mapper.readTree(body).path("id").asText());
        entityManager.flush();
        entityManager.clear();
        var restored = quizzes.findById(id).orElseThrow().getQuestions().getFirst();
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
                        .with(csrf()).contentType("application/json").content("{\"answers\":[" + question.getAnswer() + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(1));
    }

    private org.springframework.security.core.Authentication teacherAuthentication() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(teacher.getUsername(), "",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TEACHER")));
    }
}
