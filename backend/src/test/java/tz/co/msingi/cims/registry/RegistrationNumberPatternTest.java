package tz.co.msingi.cims.registry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationNumberPatternTest {

    static final RegistrationNumberPattern.Values V = new RegistrationNumberPattern.Values("demo", "ODIT", "NTA", 6, 2027, "SEPTEMBER");

    @Test
    void rendersTokens() {
        assertThat(RegistrationNumberPattern.parse(RegistrationNumberPattern.DEFAULT).render(V, 1)).isEqualTo("DEMO/NTA6/27/0001");
        assertThat(RegistrationNumberPattern.parse("{PROG}-{YEAR}-{INTAKE}-{SEQ:5}").render(V, 42)).isEqualTo("ODIT-2027-S-00042");
        assertThat(RegistrationNumberPattern.parse("{INST}{YY}{SEQ}").render(V, 7)).isEqualTo("DEMO270007");
    }

    @Test
    void prefixDecidesWhereTheRunningNumberRestarts() {
        RegistrationNumberPattern perProgramme = RegistrationNumberPattern.parse("{INST}/{PROG}/{YY}/{SEQ}");
        RegistrationNumberPattern.Values other = new RegistrationNumberPattern.Values("demo", "BTCIT", "NTA", 4, 2027, "SEPTEMBER");
        assertThat(perProgramme.prefix(V)).isNotEqualTo(perProgramme.prefix(other));
        RegistrationNumberPattern perYear = RegistrationNumberPattern.parse("{INST}/{YY}/{SEQ}");
        assertThat(perYear.prefix(V)).isEqualTo(perYear.prefix(other));
    }

    @Test
    void rejectsBadPatterns() {
        assertThatThrownBy(() -> RegistrationNumberPattern.parse("{INST}/{YY}")).hasMessageContaining("{SEQ} exactly once");
        assertThatThrownBy(() -> RegistrationNumberPattern.parse("{INST}/{SEQ}/{SEQ}")).hasMessageContaining("exactly once");
        assertThatThrownBy(() -> RegistrationNumberPattern.parse("{INST}/{FOO}/{SEQ}")).hasMessageContaining("Unknown token {FOO}");
        assertThatThrownBy(() -> RegistrationNumberPattern.parse("{INST} {SEQ}")).hasMessageContaining("Only letters, digits");
        assertThatThrownBy(() -> RegistrationNumberPattern.parse("{INST}/{SEQ:9}")).hasMessageContaining("3 to 6 digits");
        assertThatThrownBy(() -> RegistrationNumberPattern.parse("{INST}/{PROG}/{PROG}/{SEQ:6}")).hasMessageContaining("maximum is 30");
        assertThatThrownBy(() -> RegistrationNumberPattern.parse("{INST/{SEQ}")).isInstanceOf(IllegalArgumentException.class);
    }
}
