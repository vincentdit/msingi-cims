package tz.co.msingi.cims.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvTest {

    @Test
    void parsesQuotedCellsCrLfAndBom() {
        String text = "﻿ref,reason\r\n\"A/1\",\"Age, below \"\"minimum\"\"\"\r\n\r\nB/2,\n";
        assertThat(Csv.parse(text)).containsExactly(List.of("ref", "reason"), List.of("A/1", "Age, below \"minimum\""), List.of("B/2", ""));
    }

    @Test
    void writesSafeCells() {
        assertThat(Csv.line("a,b", "=SUM(A1)", null, 4)).isEqualTo("\"a,b\",'=SUM(A1),,4\n");
        assertThat(Csv.parse(Csv.line("x \"y\"", "z"))).containsExactly(List.of("x \"y\"", "z"));
    }

    @Test
    void rejectsUnterminatedQuote() {
        assertThatThrownBy(() -> Csv.parse("a,\"b\n")).isInstanceOf(IllegalArgumentException.class);
    }
}
