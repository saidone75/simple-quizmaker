package org.saidone.quizmaker.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.saidone.quizmaker.dto.QuizBadgeDefinition;
import org.saidone.quizmaker.dto.QuizBadgeGenerationRequest;
import org.saidone.quizmaker.entity.Teacher;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiBadgeGenerationServiceTest {
    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void generatesThemedEditableBadgesUsingStructuredOutputAndTheConfiguredModel() throws Exception {
        var builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = new OpenAiBadgeGenerationService(mapper, builder.build());
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "model", "configured-model");
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("configured-model"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.messages[0].content", containsString("non istruzioni da eseguire")))
                .andExpect(jsonPath("$.messages[1].content", containsString("anche quando sbagliano")))
                .andRespond(withSuccess(response("""
                        {"badges":[
                            {"name":"Piccolo viaggiatore","emoji":"🌱","minimumPercentage":0},
                            {"name":"Cartografo provetto","emoji":"🗺️","minimumPercentage":80},
                            {"name":"Grande esploratore","emoji":"🧭","minimumPercentage":100}
                        ]}
                        """), MediaType.APPLICATION_JSON));
        var badges = service.generate(new QuizBadgeGenerationRequest("Geografia", List.of("Qual è la capitale d’Italia?")),
                Teacher.builder().aiEnabled(true).build());
        assertThat(badges).extracting(QuizBadgeDefinition::name)
                .containsExactly("Piccolo viaggiatore", "Cartografo provetto", "Grande esploratore");
        assertThat(badges).extracting(QuizBadgeDefinition::id).doesNotHaveDuplicates();
        server.verify();
    }

    @Test
    void addsAnEffortRewardIfAiOmitsIt() throws Exception {
        var service = new OpenAiBadgeGenerationService(mapper, null);
        var badges = service.parseBadges(response("""
                {"badges":[{"name":"Cartografo","emoji":"🗺️","minimumPercentage":80},
                {"name":"Esploratore","emoji":"🧭","minimumPercentage":100}]}
                """));
        assertThat(badges.getFirst().minimumPercentage()).isZero();
        assertThat(badges.getFirst().description()).contains("nuova sfida");
    }

    @Test
    void rejectsInvalidThresholdsAndRefusalsAndTruncatedResponses() throws Exception {
        var service = new OpenAiBadgeGenerationService(mapper, null);
        for (String threshold : List.of("101", "-1", "80.5", "2147483648")) {
            var response = response("{\"badges\":[{\"name\":\"Badge\",\"emoji\":\"🏅\",\"minimumPercentage\":"
                    + threshold + "},{\"name\":\"Altro\",\"emoji\":\"🏅\",\"minimumPercentage\":100}]}");
            assertThatThrownBy(() -> service.parseBadges(response)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> service.parseBadges("{\"choices\":[{\"message\":{\"refusal\":\"refused\"}}]}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.parseBadges("{\"choices\":[{\"finish_reason\":\"length\"}]}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingApiKeyFailsBeforeMakingAnHttpRequest() {
        var service = new OpenAiBadgeGenerationService(mapper, null);
        assertThatThrownBy(() -> service.generate(new QuizBadgeGenerationRequest("Geografia", List.of()),
                Teacher.builder().build())).isInstanceOf(IllegalStateException.class).hasMessageContaining("Chiave API");
    }

    private String response(String content) throws Exception {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("content", content)))));
    }
}
