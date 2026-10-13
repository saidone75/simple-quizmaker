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

package org.saidone.quizmaker.dto;

import lombok.Data;
import org.saidone.quizmaker.entity.QuestionType;

import java.util.List;

@Data
public class QuestionDto {
    private QuestionType type = QuestionType.MULTIPLE_CHOICE;
    private String text;
    private String emoji;
    private String imageKeywords;
    private String imageUrl;
    private String imageId;
    private List<String> options;
    private Integer answer;
    private String feedback;

    public boolean hasValidAnswers() {
        return type != null
                && options != null
                && options.size() >= 2 && options.size() <= 4
                && options.stream().allMatch(option -> option != null && !option.isBlank())
                && answer != null && answer >= 0 && answer < options.size()
                && (type != QuestionType.TRUE_FALSE || options.equals(List.of("Vero", "Falso")));
    }
}
