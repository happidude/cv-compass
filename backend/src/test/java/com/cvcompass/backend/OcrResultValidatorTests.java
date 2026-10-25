package com.cvcompass.backend;

import com.cvcompass.backend.ingestion.config.OcrJobProperties;
import com.cvcompass.backend.ingestion.enums.*;
import com.cvcompass.backend.ingestion.exception.OcrStateException;
import com.cvcompass.backend.ingestion.service.OcrResultValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class OcrResultValidatorTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final OcrResultValidator validator = new OcrResultValidator(json,new OcrJobProperties(),
            new ClassPathResource("ocr/ocr-v2.schema.json"),new ClassPathResource("ocr/ocr-v1.schema.json"));
    OcrResultValidatorTests() throws Exception { }
    private String valid() { return OcrFixtures.envelope("request","1","synthetic-v1"); }
    private ObjectNode tree() { return (ObjectNode) json.readTree(valid()); }
    private void rejected(String input) {
        assertThatThrownBy(() -> validator.parse(input)).isInstanceOfSatisfying(OcrStateException.class,
                error -> assertThat(error.reason()).isEqualTo(OcrStateError.INVALID_RESULT));
    }
    @Test void preservesPerPageRawTextAndDerivesMethod() {
        var payload = validator.parse(valid());
        assertThat(payload.method()).isEqualTo(ExtractionMethod.TEXT_EXTRACTION);
        assertThat(json.readTree(payload.resultJson()).at("/pages/0/raw_text").asString()).isEqualTo("Synthetic text");
        assertThat(payload.toString()).doesNotContain("Synthetic text");
    }
    @Test void acceptsV2RawOnlyAndRejectsTextOrMissingRaw() {
        var tree = tree(); var result = (ObjectNode)tree.get("ocr_result");
        result.put("schema_version", OcrProtocol.RESULT_VERSION);
        var page = (ObjectNode)result.withArray("pages").get(0);
        page.remove("text");
        assertThat(validator.parse(tree.toString()).schemaVersion()).isEqualTo(OcrProtocol.RESULT_VERSION);
        page.put("text", "Synthetic text"); rejected(tree.toString());
        page.remove("text"); page.remove("raw_text"); rejected(tree.toString());
    }
    @Test void rejectsDuplicateTrailingUnknownAndIncorrectTypes() {
        rejected(valid().replace("\"delivered\":true","\"delivered\":true,\"delivered\":true"));
        rejected(valid()+" {}");
        rejected(valid().replace("\"exit_code\":0","\"exit_code\":3"));
        rejected(valid().replace("\"delivered\":true","\"delivered\":false"));
        rejected(valid().replace("\"total\":3","\"total\":\"3\""));
        rejected(valid().replace("\"exit_code\":0","\"exit_code\":4294967296"));
        rejected(valid().replace("\"page_count\":1","\"page_count\":4294967297"));
        rejected(valid().replace("Synthetic text","\\u0000"));
        rejected(valid().replace("Synthetic text","\\ud800"));
        var tree = tree(); tree.put("unrecognized",true); rejected(tree.toString());
    }
    @Test void statusAndExitAreDerivedFromPagesAndDocumentErrors() {
        var tree = tree(); var result = (ObjectNode)tree.get("ocr_result");
        result.put("status","partial"); tree.put("exit_code",1);
        rejected(tree.toString());
        result.withArray("errors").addObject().put("code","CLEANUP_FAILED").put("stage","cleanup");
        // Cleanup belongs to warnings, not a fabricated document error.
        rejected(tree.toString());
    }
    @Test void unicodeCodePointCapsDoNotCountUtf16UnitsOrTruncate() {
        var tree = tree(); var page = (ObjectNode)tree.at("/ocr_result/pages/0");
        String boundary = "\uD83D\uDE00".repeat(50_000);
        page.put("text",boundary); page.put("raw_text",boundary);
        assertThat(validator.parse(tree.toString()).resultJson()).contains(boundary);
        page.put("raw_text",boundary+"x"); rejected(tree.toString());
    }
    @Test void blankOnlyIsFailedAndMethodNone() {
        var tree = tree(); var result = (ObjectNode)tree.get("ocr_result"); var page = (ObjectNode)result.get("pages").get(0);
        page.put("status","blank").put("method","none").put("text","").put("raw_text","");
        page.withArray("warnings").add("BLANK_PAGE");
        rejected(tree.toString());
        result.put("status","failed"); tree.put("exit_code",2);
        assertThat(validator.parse(tree.toString()).method()).isEqualTo(ExtractionMethod.NONE);
    }
    @Test void unicodeWhitespaceOnlyCannotBecomeAnOkPage() {
        for (String whitespace : java.util.List.of("\u00a0","\u2007","\u202f","\u0085"," \n\t")) {
            var tree = tree(); ((ObjectNode)tree.at("/ocr_result/pages/0")).put("text",whitespace);
            rejected(tree.toString());
        }
    }
    @ParameterizedTest
    @CsvSource({"0,false","1,true","50000,true","50001,false","4294967297,false",
            "9223372036854775808,false","999999999999999999999999999999999999999,false","-1,false"})
    void confidenceSampleCountObeysM6IntegerBounds(String sampleCount,boolean accepted) {
        var tree=tree(); var page=(ObjectNode)tree.at("/ocr_result/pages/0"); page.put("method","ocr");
        page.putObject("confidence").put("engine","tesseract").put("metric","mean_word_confidence")
                .put("value",88).put("sample_count",new java.math.BigInteger(sampleCount));
        if (accepted) {
            var result=validator.parse(tree.toString());
            assertThat(json.readTree(result.resultJson()).at("/pages/0/confidence/sample_count").bigIntegerValue())
                    .isEqualTo(new java.math.BigInteger(sampleCount));
        } else rejected(tree.toString());
    }
    @Test void schemaAndCanonicalLimitsAreByteIdenticalToWorker() throws Exception {
        for (String name : java.util.List.of("ocr-v1.schema.json","ocr-v1.limits.json",
                "ocr-v2.schema.json","ocr-v2.limits.json")) {
            try (var input = new ClassPathResource("ocr/"+name).getInputStream()) {
                assertThat(input.readAllBytes()).isEqualTo(Files.readAllBytes(Path.of("../worker/design/"+name)));
            }
        }
        var limits = json.readTree(new ClassPathResource("ocr/ocr-v1.limits.json").getInputStream());
        assertThat(limits.get("max_pages").intValue()).isEqualTo(OcrProtocol.MAX_PAGES);
        assertThat(limits.get("max_text_code_points_per_field").intValue()).isEqualTo(OcrProtocol.MAX_TEXT_CODE_POINTS);
    }
}
