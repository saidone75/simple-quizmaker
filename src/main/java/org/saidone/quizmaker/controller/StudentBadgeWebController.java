package org.saidone.quizmaker.controller;

import lombok.val;

import lombok.RequiredArgsConstructor;
import org.saidone.quizmaker.service.StudentBadgeService;
import org.saidone.quizmaker.service.TeacherAuthenticationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class StudentBadgeWebController {
    private final StudentBadgeService studentBadgeService;
    private final TeacherAuthenticationService teacherAuthenticationService;

    @GetMapping("/student/badges")
    public String myBadges(Model model) {
        model.addAttribute("collection", studentBadgeService.forCurrentStudent());
        model.addAttribute("teacherView", false);
        return "student-badges";
    }

    @GetMapping("/teacher/students/{studentId}/badges")
    public String studentBadges(@PathVariable UUID studentId, Model model) {
        val teacher = teacherAuthenticationService.getCurrentTeacher();
        model.addAttribute("collection", studentBadgeService.forTeacher(studentId, teacher));
        model.addAttribute("teacherView", true);
        model.addAttribute("teacherThemePreference", teacher.getThemePreference());
        return "student-badges";
    }
}
