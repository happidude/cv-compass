package com.cvcompass.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

class FoldTests {
    @Test
    void foldsVietnameseComposedAndDecomposedTextAndWhitespace() {
        assertThat(Fold.fold("Đặng Thị Ánh")).isEqualTo("dang thi anh");
        assertThat(Fold.fold(Normalizer.normalize("Đặng Thị Ánh", Normalizer.Form.NFD)))
                .isEqualTo("dang thi anh");
        assertThat(Fold.fold(" \tĐẶNG\n Thị\r\nÁnh  ")).isEqualTo("dang thi anh");
        assertThat(Fold.fold("\u00a0Đỗ\u2003Đạt\u00a0")).isEqualTo("do dat");
        assertThat(Fold.fold(Fold.fold("Đặng Thị Ánh"))).isEqualTo("dang thi anh");
    }

    @Test
    void keepsNullAndFoldsEmptyValues() {
        assertThat(Fold.fold(null)).isNull();
        assertThat(Fold.fold("")).isEmpty();
        assertThat(Fold.fold("\t\n  ")).isEmpty();
    }
}
