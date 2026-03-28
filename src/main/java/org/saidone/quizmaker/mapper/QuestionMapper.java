package org.saidone.quizmaker.mapper;

import org.saidone.quizmaker.dto.QuestionDto;
import org.saidone.quizmaker.entity.Question;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface QuestionMapper {

    Question toEntity(QuestionDto dto);

    QuestionDto toDto(Question question);

}
