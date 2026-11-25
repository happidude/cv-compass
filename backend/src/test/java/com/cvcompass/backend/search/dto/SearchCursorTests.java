package com.cvcompass.backend.search.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvcompass.backend.search.exception.SearchFailure;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SearchCursorTests {
    @Test
    void cursorPreservesSixDecimalPlacesAndTheLargestId() {
        var cursor = new SearchCursor(new BigDecimal("1234.000001"), Long.MAX_VALUE);
        assertThat(SearchCursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(SearchCursor.decode(null)).isNull();
    }

    @Test
    void corruptNonCanonicalAndOutOfRangeCursorsAreRejectedWithoutEchoingInput() {
        for (String value :
                new String[] {
                    "",
                    "1.1:1",
                    "-1.000000:1",
                    "NaN:1",
                    "1.000000:0",
                    "1.000000:9223372036854775808",
                    "01.000000:1",
                    "1.000000:1:2"
                }) {
            String encoded =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(value.getBytes(StandardCharsets.US_ASCII));
            assertThatThrownBy(() -> SearchCursor.decode(encoded))
                    .isInstanceOf(SearchFailure.class)
                    .hasMessage("VALIDATION_ERROR");
        }
        assertThatThrownBy(() -> SearchCursor.decode("%%%invalid"))
                .isInstanceOf(SearchFailure.class);
    }
}
