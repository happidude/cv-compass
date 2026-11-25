package com.cvcompass.backend.search.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("cv.search")
public class SearchProperties {
    @Min(1)
    @Max(1000)
    private int rebuildBatchSize = 200;

    @NotBlank private String nightlyCron = "0 30 2 * * *";

    @Valid @NotNull private Query query = new Query();

    @Getter
    @Setter
    public static class Query {
        @Min(1)
        private int defaultLimit = 20;

        @Min(1)
        @Max(50)
        private int maxLimit = 50;

        @Min(1)
        @Max(50)
        private int suggestLimit = 10;

        @Min(1)
        @Max(50)
        private int resolveLimit = 5;

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax("1")
        private BigDecimal trigramThreshold = new BigDecimal("0.3");

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax("1")
        private BigDecimal keywordTrigramThreshold = new BigDecimal("0.5");

        @Valid @NotNull private Weights weights = new Weights();

        @AssertTrue(message = "default-limit must not exceed max-limit")
        public boolean isLimitRangeValid() {
            return defaultLimit <= maxLimit;
        }
    }

    @Getter
    @Setter
    public static class Weights {
        @NotNull
        @DecimalMin("0")
        private BigDecimal companyDirect = new BigDecimal("1000");

        @NotNull
        @DecimalMin("0")
        private BigDecimal companyParent = new BigDecimal("600");

        @NotNull
        @DecimalMin("0")
        private BigDecimal titleCatalog = new BigDecimal("200");

        @NotNull
        @DecimalMin("0")
        private BigDecimal titleCatalogBonus = new BigDecimal("30");

        @NotNull
        @DecimalMin("0")
        private BigDecimal companyText = new BigDecimal("10");

        @NotNull
        @DecimalMin("0")
        private BigDecimal titleText = new BigDecimal("10");

        @NotNull
        @DecimalMin("0")
        private BigDecimal companyCurrent = new BigDecimal("40");

        @NotNull
        @DecimalMin("0")
        private BigDecimal titleCurrent = new BigDecimal("20");

        @NotNull
        @DecimalMin("0")
        private BigDecimal keywordText = new BigDecimal("10");

        @NotNull
        @DecimalMin("0")
        private BigDecimal keywordFuzzy = new BigDecimal("10");

        @AssertTrue(message = "ranking tiers must remain disjoint after rounding and all bonuses")
        public boolean isTierOrderValid() {
            var values =
                    new BigDecimal[] {
                        companyDirect,
                        companyParent,
                        titleCatalog,
                        titleCatalogBonus,
                        companyText,
                        titleText,
                        companyCurrent,
                        titleCurrent,
                        keywordText,
                        keywordFuzzy
                    };
            for (var value : values) {
                if (value == null) return false;
            }
            var bonuses =
                    companyText
                            .add(titleText)
                            .add(titleCatalogBonus)
                            .add(companyCurrent)
                            .add(titleCurrent)
                            .add(keywordText)
                            .add(keywordFuzzy)
                            .add(new BigDecimal("0.000001"));
            return bonuses.compareTo(titleCatalog) < 0
                    && titleCatalog.add(bonuses).compareTo(companyParent) < 0
                    && companyParent.add(bonuses).compareTo(companyDirect) < 0;
        }
    }
}
