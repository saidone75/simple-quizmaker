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

import lombok.RequiredArgsConstructor;
import lombok.val;
import jakarta.persistence.EntityNotFoundException;
import org.saidone.quizmaker.dto.QuizDto;
import org.saidone.quizmaker.entity.Teacher;
import org.saidone.quizmaker.service.QuizQrCodeService;
import org.saidone.quizmaker.service.QuizService;
import org.saidone.quizmaker.service.TeacherAuthenticationService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class TeacherPublicQuizWebController {
    private final QuizService quizService;
    private final TeacherAuthenticationService teacherAuthenticationService;
    private final QuizQrCodeService qrCodeService;

    @GetMapping("/teacher/quiz/{id}/public")
    public String settings(@PathVariable UUID id, Model model) {
        val teacher = teacherAuthenticationService.getCurrentTeacher();
        model.addAttribute("quiz", findOwnedQuiz(id, teacher));
        model.addAttribute("teacherThemePreference", teacher.getThemePreference());
        model.addAttribute("teacherPublicQuizzesEnabled", teacher.isPublicQuizzesEnabled());
        model.addAttribute("publicQuizUrl", publicUrl(id));
        return "admin/public-quiz";
    }

    @PostMapping("/teacher/quiz/{id}/public")
    public String update(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean enabled,
                         RedirectAttributes attributes) {
        try {
            quizService.updatePublicAccess(id, enabled, teacherAuthenticationService.getCurrentTeacher());
            attributes.addFlashAttribute("publicAccessSuccess", enabled ? "Accesso pubblico attivato." : "Accesso pubblico disattivato.");
        } catch (IllegalArgumentException exception) {
            attributes.addFlashAttribute("publicAccessError", exception.getMessage());
        }
        return String.format("redirect:/teacher/quiz/%s/public", id);
    }

    @GetMapping("/teacher/quiz/{id}/public-qr")
    public ResponseEntity<byte[]> qr(@PathVariable UUID id) throws IOException {
        val quiz = findOwnedQuiz(id, teacherAuthenticationService.getCurrentTeacher());
        if (!Boolean.TRUE.equals(quiz.getPublicAccess()) || Boolean.TRUE.equals(quiz.getArchived())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Accesso pubblico non attivo");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .body(qrCodeService.png(publicUrl(id)));
    }

    private QuizDto.Response findOwnedQuiz(UUID id, Teacher teacher) {
        try {
            return quizService.findByIdForTeacher(id, teacher);
        } catch (EntityNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz non trovato", exception);
        }
    }

    private String publicUrl(UUID id) {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path("/play/quizzes/{id}")
                .buildAndExpand(id).toUriString();
    }
}
