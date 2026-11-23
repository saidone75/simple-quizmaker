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

package org.saidone.quizmaker.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.saidone.quizmaker.service.PublicQuizService;
import org.saidone.quizmaker.service.QuestionImageStorageService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class PublicQuizWebController {
    private final PublicQuizService publicQuizService;
    private final QuestionImageStorageService imageStorage;
    private final ObjectMapper objectMapper;

    @GetMapping("/play/quizzes/{id}")
    public String play(@PathVariable UUID id, Model model) throws JsonProcessingException {
        val quiz = publicQuizService.findPublicQuiz(id);
        model.addAttribute("publicQuiz", quiz);
        model.addAttribute("publicQuizJson", objectMapper.writeValueAsString(quiz));
        return "public-quiz";
    }

    @GetMapping("/play/quizzes/{quizId}/images/{imageId}")
    public ResponseEntity<Resource> image(@PathVariable UUID quizId, @PathVariable UUID imageId) {
        if (!publicQuizService.canReadImage(quizId, imageId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Immagine del quiz anonimo non disponibile");
        }
        val type = imageStorage.resolvePublicMediaType(quizId, imageId);
        val resource = imageStorage.loadForPublicQuiz(quizId, imageId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(type).body(resource);
    }
}
