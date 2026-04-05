(function () {
    const dataEl = document.getElementById('quiz-editor-data');

    const rawQuizId = dataEl?.dataset.quizId;
    const quizId = rawQuizId && rawQuizId !== 'null' && rawQuizId !== '' ? rawQuizId : null;

    const rawQuestions = dataEl?.dataset.initialQuestionsJson;
    const initialQuestionsJson = rawQuestions && rawQuestions !== 'null' && rawQuestions !== '' ? rawQuestions : null;

    initEditor(quizId, initialQuestionsJson);

    document.getElementById('quiz-title-input')?.addEventListener('input', updateBadge);
    document.getElementById('add-question-btn')?.addEventListener('click', addQuestion);
    document.getElementById('save-btn')?.addEventListener('click', saveQuiz);
    document.getElementById('generate-quiz-ai-btn')?.addEventListener('click', generateQuizWithAi);
})();
