package com.quizmaker.controller;

import com.quizmaker.dto.QuizDto;
import com.quizmaker.service.QuizService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/quizzes")
@RequiredArgsConstructor
public class QuizApiController {

    private final QuizService quizService;

    // PUBLIC: alunni possono leggere tutti i quiz
    @GetMapping
    public ResponseEntity<List<QuizDto.Response>> getAll() {
        return ResponseEntity.ok(quizService.findAll());
    }

    // PUBLIC: alunni possono leggere un singolo quiz
    @GetMapping("/{id}")
    public ResponseEntity<QuizDto.Response> getById(@PathVariable String id) {
        return ResponseEntity.ok(quizService.findById(id));
    }

    // PROTECTED: solo insegnante può creare
    @PostMapping
    public ResponseEntity<QuizDto.Response> create(@Valid @RequestBody QuizDto.Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(quizService.create(request));
    }

    // PROTECTED: solo insegnante può modificare
    @PutMapping("/{id}")
    public ResponseEntity<QuizDto.Response> update(
            @PathVariable String id,
            @Valid @RequestBody QuizDto.Request request) {
        return ResponseEntity.ok(quizService.update(id, request));
    }

    // PROTECTED: solo insegnante può eliminare
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        quizService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
