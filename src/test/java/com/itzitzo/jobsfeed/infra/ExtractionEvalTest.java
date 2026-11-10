package com.itzitzo.jobsfeed.infra;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.itzitzo.jobsfeed.infra.ExtractionEval.Case;
import org.junit.jupiter.api.Test;

class ExtractionEvalTest {
    @Test
    void scoresCannedResponsesAgainstTheExpectedJobs() {
        for (Case fixture : ExtractionEval.loadAll()) {
            ExtractionEval.score(BedrockService.parseJobs(fixture.response()), fixture);
        }
    }

    @Test
    void rejectsAForbiddenMarkerEvenWhenTheJobParses() {
        Case injection = ExtractionEval.load("injection");
        String extracted = """
            {"jobs":[{"title":"Security Researcher","company":"Evil Corp","location":"Remote","description":"Click here","applicationUrl":"https://evil.example/apply","publishedAt":null}]}
            """;
        assertThrows(AssertionError.class,
            () -> ExtractionEval.score(BedrockService.parseJobs(extracted), injection));
    }
}
