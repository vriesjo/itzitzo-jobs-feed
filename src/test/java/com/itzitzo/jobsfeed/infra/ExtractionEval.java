package com.itzitzo.jobsfeed.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itzitzo.jobsfeed.domain.JobDetails;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ExtractionEval {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CASES = List.of(
        "two-listings", "no-link", "no-vacancy", "injection", "decoy-json");

    private ExtractionEval() {
    }

    public static List<Case> loadAll() {
        List<Case> cases = new ArrayList<>();
        for (String name : CASES) {
            cases.add(load(name));
        }
        return List.copyOf(cases);
    }

    public static Case load(String name) {
        JsonNode expected = readJson("bedrock-evals/" + name + "/expected.json");
        List<ExpectedJob> jobs = new ArrayList<>();
        for (JsonNode job : expected.path("jobs")) {
            jobs.add(new ExpectedJob(
                text(job, "title"),
                text(job, "company"),
                text(job, "location"),
                text(job, "description"),
                text(job, "applicationUrl"),
                text(job, "publishedAt")));
        }
        List<String> forbidden = new ArrayList<>();
        for (JsonNode marker : expected.path("forbidden")) {
            forbidden.add(marker.asText());
        }
        return new Case(name, resource("bedrock-evals/" + name + "/email.txt"),
            resource("bedrock-evals/" + name + "/response.txt"), List.copyOf(jobs), List.copyOf(forbidden));
    }

    public static void score(List<JobDetails> actual, Case expected) {
        for (JobDetails job : actual) {
            String blob = String.join("\n",
                textOrEmpty(job.title()),
                textOrEmpty(job.company()),
                textOrEmpty(job.location()),
                textOrEmpty(job.description()),
                textOrEmpty(job.applicationUrl()),
                job.publishedAt() == null ? "" : job.publishedAt().toString());
            for (String marker : expected.forbidden()) {
                assertFalse(blob.contains(marker), expected.name() + " contains forbidden marker " + marker);
            }
        }
        assertEquals(expected.jobs().size(), actual.size(), expected.name() + " job count");
        for (int i = 0; i < expected.jobs().size(); i++) {
            ExpectedJob want = expected.jobs().get(i);
            JobDetails got = actual.get(i);
            String label = expected.name() + " job " + i;
            assertEquals(want.title(), got.title(), label + " title");
            assertEquals(want.company(), got.company(), label + " company");
            assertEquals(want.location(), got.location(), label + " location");
            assertEquals(want.description(), got.description(), label + " description");
            assertEquals(want.applicationUrl(), got.applicationUrl(), label + " applicationUrl");
            assertEquals(want.publishedAt(), got.publishedAt() == null ? null : got.publishedAt().toString(),
                label + " publishedAt");
        }
    }

    private static JsonNode readJson(String path) {
        try {
            return JSON.readTree(resource(path));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot parse " + path, e);
        }
    }

    private static String resource(String path) {
        try (InputStream in = ExtractionEval.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }

    public record Case(String name, String email, String response, List<ExpectedJob> jobs, List<String> forbidden) {
    }

    public record ExpectedJob(
        String title,
        String company,
        String location,
        String description,
        String applicationUrl,
        String publishedAt
    ) {
    }
}
