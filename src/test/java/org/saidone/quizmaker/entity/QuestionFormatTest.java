/*
 * Alice's Simple Quiz Maker - fun quizzes for curious minds
 * Copyright (C) 2026 Miss Alice & Saidone
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.saidone.quizmaker.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.saidone.quizmaker.dto.QuestionDto;
import org.saidone.quizmaker.mapper.QuestionMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionFormatTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QuestionMapper questionMapper = Mappers.getMapper(QuestionMapper.class);

    @Test
    void oldQuestionsWithoutTypeRemainMultipleChoice() throws Exception {
        var json = """
                {"text":"Domanda", "options":["A","B","C","D"], "answer":2}
                """;
        var entity = objectMapper.readValue(json, Question.class);
        var dto = objectMapper.readValue(json, QuestionDto.class);
        assertThat(entity.getType()).isEqualTo(QuestionType.MULTIPLE_CHOICE);
        assertThat(dto.hasValidAnswers()).isTrue();
        assertThat(questionMapper.toDto(entity).getAnswer()).isEqualTo(2);
    }

    @Test
    void trueFalseTypeAndCorrectAnswerSurviveMappingAndJsonStorage() throws Exception {
        var dto = new QuestionDto();
        dto.setText("Il Sole è un pianeta.");
        dto.setType(QuestionType.TRUE_FALSE);
        dto.setOptions(List.of("Vero", "Falso"));
        dto.setAnswer(1);
        var stored = objectMapper.writeValueAsString(questionMapper.toEntity(dto));
        var restored = questionMapper.toDto(objectMapper.readValue(stored, Question.class));
        assertThat(restored).isEqualTo(dto);
        assertThat(restored.hasValidAnswers()).isTrue();
        assertThat(stored).doesNotContain("validAnswers");
    }
}
