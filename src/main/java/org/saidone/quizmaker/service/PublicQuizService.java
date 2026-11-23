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

package org.saidone.quizmaker.service;

import lombok.RequiredArgsConstructor;
import lombok.val;
import lombok.extern.slf4j.Slf4j;
import org.saidone.quizmaker.dto.QuestionDto;
import org.saidone.quizmaker.dto.QuizDto;
import org.saidone.quizmaker.entity.Question;
import org.saidone.quizmaker.entity.Quiz;
import org.saidone.quizmaker.mapper.QuestionMapper;
import org.saidone.quizmaker.policy.QuestionImageAuthorizationPolicy;
import org.saidone.quizmaker.repository.QuizRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.net.URI;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicQuizService {
    private final QuizRepository quizRepository;
    private final QuestionMapper questionMapper;
    private final QuestionImageAuthorizationPolicy imagePolicy;

    public QuizDto.PublicQuiz findPublicQuiz(UUID id) {
        val quiz = quizRepository.findByIdAndPublicAccessTrueAndArchivedFalseAndTeacherEnabledTrue(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz pubblico non disponibile"));
        return new QuizDto.PublicQuiz(quiz.getId(), quiz.getTitle(), quiz.getEmoji(),
                quiz.getQuestions().stream().map(question -> publicQuestion(quiz, question)).toList());
    }

    public boolean canReadImage(UUID quizId, UUID imageId) {
        return quizRepository.findByIdAndPublicAccessTrueAndArchivedFalseAndTeacherEnabledTrue(quizId)
                .map(quiz -> imagePolicy.referencesImage(quiz, imageId)).orElse(false);
    }

    private QuestionDto publicQuestion(Quiz quiz, Question question) {
        val dto = questionMapper.toDto(question);
        val imageId = storedImageId(question);
        if (imageId != null) {
            dto.setImageUrl(String.format("/play/quizzes/%s/images/%s", quiz.getId(), imageId));
        }
        dto.setImageId(null);
        dto.setImageKeywords(null);
        return dto;
    }

    private UUID storedImageId(Question question) {
        try {
            if (question.getImageId() != null && !question.getImageId().isBlank()) {
                return UUID.fromString(question.getImageId());
            }
            val url = question.getImageUrl();
            if (url == null || url.isBlank()) return null;
            val path = URI.create(url).getPath();
            val prefix = "/api/quizzes/images/";
            if (path == null || !path.startsWith(prefix)) return null;
            return UUID.fromString(path.substring(prefix.length()));
        } catch (IllegalArgumentException exception) {
            log.warn("Riferimento immagine non valido in un quiz pubblico", exception);
            return null;
        }
    }
}
