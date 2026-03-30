package org.saidone.quizmaker.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.datafaker.Faker;
import org.saidone.quizmaker.entity.Student;
import org.saidone.quizmaker.repository.StudentRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;

@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevStudentBootstrap implements CommandLineRunner {

    private final StudentRepository studentRepository;

    private static final Faker FAKER = new Faker(Locale.ITALIAN);
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public void run(String... args) {
        for (var i = 0; i < 2; i++) {
            val student = new Student();
            student.setId(UUID.randomUUID());
            student.setFullName(String.format("%s %s", FAKER.name().firstName(), FAKER.name().lastName()));
            student.setLoginKeyword(randomAlphanumeric(4));
            studentRepository.save(student);
        }

        log.info("Studenti demo creati!");
    }

    private static String randomAlphanumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

}
