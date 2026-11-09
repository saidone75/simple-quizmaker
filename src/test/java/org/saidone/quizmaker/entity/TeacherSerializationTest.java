package org.saidone.quizmaker.entity;

import lombok.val;
import org.junit.jupiter.api.Test;
import org.saidone.quizmaker.dto.ClassBadgeRule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TeacherSerializationTest {
    @Test
    void serializationPreservesClassBadgeSettings() throws Exception {
        val teacher = Teacher.builder().username("teacher").classBadgeRules(ClassBadgeRule.normalize(ClassBadgeRule.defaults())).build();
        val bytes = new ByteArrayOutputStream();
        try (val output = new ObjectOutputStream(bytes)) {
            output.writeObject(teacher);
        }
        try (val input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            val restored = (Teacher) input.readObject();
            assertThat(restored.getClassBadgeRules()).isEqualTo(teacher.getClassBadgeRules());
        }
    }
}
