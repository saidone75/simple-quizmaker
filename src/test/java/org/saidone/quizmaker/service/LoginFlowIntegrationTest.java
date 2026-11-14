package org.saidone.quizmaker.service;

import ai.djl.repository.zoo.ZooModel;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.springframework.security.core.session.SessionRegistry;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.saidone.quizmaker.entity.Student;
import org.saidone.quizmaker.entity.Teacher;
import org.saidone.quizmaker.repository.StudentRepository;
import org.saidone.quizmaker.repository.TeacherRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:login-flow;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
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
class LoginFlowIntegrationTest {
    @MockitoBean(name = "textEmbeddingModel") ZooModel<String, float[]> model;
    @Autowired WebApplicationContext context;
    @Autowired StudentRepository students;
    @Autowired TeacherRepository teachers;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void internalErrorDispatchReturnsErrorInsteadOfRedirectingToLogin() throws Exception {
        val mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        SecurityContextHolder.clearContext();
        mvc.perform(get("/error").with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            return request;
                        })
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void teacherLogoutFollowedByStudentLoginLoadsStudentHome() throws Exception {
        val teacher = teachers.saveAndFlush(Teacher.builder().username("login-flow-teacher")
                .password(passwordEncoder.encode("test-password")).build());
        val student = students.saveAndFlush(Student.builder().id(UUID.randomUUID()).teacher(teacher)
                .fullName("Login flow student").loginKeyword("FLOWX").build());
        val mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        try {
            SecurityContextHolder.clearContext();
            val login = mvc.perform(post("/teacher/login").with(csrf())
                            .param("username", teacher.getUsername()).param("password", "test-password"))
                    .andExpect(status().is3xxRedirection()).andReturn();
            val teacherSession = (MockHttpSession) login.getRequest().getSession(false);
            mvc.perform(get("/teacher").session(teacherSession)).andExpect(status().isOk());
            mvc.perform(post("/teacher/logout").session(teacherSession).with(csrf()))
                    .andExpect(redirectedUrl("/teacher/login?logout=true"));
            assertThat(teacherSession.isInvalid()).isTrue();
            SecurityContextHolder.clearContext();
            val studentLogin = mvc.perform(post("/student/login").with(csrf()).param("keyword", "FLOWX"))
                    .andExpect(redirectedUrl("/")).andReturn();
            val studentSession = (MockHttpSession) studentLogin.getRequest().getSession(false);
            mvc.perform(get("/").session(studentSession)).andExpect(status().isOk()).andExpect(view().name("student"));
            mvc.perform(get("/student/badges").session(studentSession)).andExpect(status().isOk());
            assertThat(studentSession.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
            val registry = context.getBean(SessionRegistry.class);
            assertThat(registry.getSessionInformation(studentSession.getId())).isNull();
            mvc.perform(post("/student/logout").session(studentSession).with(csrf()))
                    .andExpect(redirectedUrl("/student/login"));
            mvc.perform(get("/").session(studentSession)).andExpect(status().isOk()).andExpect(view().name("student-login"));
        } finally {
            SecurityContextHolder.clearContext();
            students.deleteById(student.getId());
            teachers.deleteById(teacher.getId());
        }
    }
}
