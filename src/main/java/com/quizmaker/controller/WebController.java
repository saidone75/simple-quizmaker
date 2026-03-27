package com.quizmaker.controller;

import com.quizmaker.service.QuizService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class WebController {

    private final QuizService quizService;

    // Pagina alunni (default)
    @GetMapping("/")
    public String studentPage(Model model) {
        model.addAttribute("quizzes", quizService.findAll());
        return "student";
    }

    // Pagina admin - login
    @GetMapping("/admin/login")
    public String loginPage() {
        return "admin/login";
    }

    // Pagina admin - dashboard
    @GetMapping("/admin")
    public String adminDashboard(Model model) {
        model.addAttribute("quizzes", quizService.findAll());
        return "admin/dashboard";
    }

    // Pagina admin - crea quiz
    @GetMapping("/admin/quiz/new")
    public String newQuiz() {
        return "admin/quiz-editor";
    }

    // Pagina admin - modifica quiz
    @GetMapping("/admin/quiz/{id}/edit")
    public String editQuiz(@org.springframework.web.bind.annotation.PathVariable String id, Model model) {
        model.addAttribute("quiz", quizService.findById(id));
        return "admin/quiz-editor";
    }
}
