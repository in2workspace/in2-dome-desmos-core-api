package es.in2.desmos.domain.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionTests {

    @Test
    void itShouldReturnNullWhenInputIsNull() {
        assertThat(Version.from(null)).isNull();
    }

    @Test
    void itShouldReturnNullWhenInputIsBlank() {
        Version result = Version.from("   ");

        assertThat(result).isNull();
    }

    @Test
    void itShouldParseThreePartVersion() {
        Version result = Version.parse("1.2.3");

        assertThat(result.major()).isEqualTo(1);
    }

    @Test
    void itShouldParseThreePartVersionMinorIsCorrect() {
        Version result = Version.parse("1.2.3");

        assertThat(result.minor()).isEqualTo(2);
    }

    @Test
    void itShouldParseThreePartVersionPatchIsCorrect() {
        Version result = Version.parse("1.2.3");

        assertThat(result.patch()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "v1.2.3",
            "V1.2.3"
    })
    void itShouldParseVersionWithVPrefix(String version) {
        Version result = Version.parse(version);

        assertThat(result.major()).isEqualTo(1);
    }

    @Test
    void itShouldParseVersionWithVPrefixMinorIsCorrect() {
        Version result = Version.parse("v1.2.3");

        assertThat(result.minor()).isEqualTo(2);
    }

    @Test
    void itShouldParseVersionWithVPrefixPatchIsCorrect() {
        Version result = Version.parse("v1.2.3");

        assertThat(result.patch()).isEqualTo(3);
    }

    @Test
    void itShouldPreserveOriginalFormat() {
        Version result = Version.parse("v1.2");

        assertThat(result.original()).isEqualTo("v1.2");
    }

    @Test
    void itShouldPreserveOriginalFormatWithoutPrefix() {
        Version result = Version.parse("1.2.3");

        assertThat(result.original()).isEqualTo("1.2.3");
    }

    @Test
    void itShouldSerializeToOriginalFormat() {
        Version version = Version.parse("v1.0");

        assertThat(version.toJson()).isEqualTo("v1.0");
    }

    @Test
    void itShouldSerializeToOriginalFormatWithoutPrefix() {
        Version version = Version.parse("1.2.3");

        assertThat(version.toJson()).isEqualTo("1.2.3");
    }

    @Test
    void itShouldThrowExceptionForInvalidFormat() {
        assertThatThrownBy(() -> Version.parse("1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itShouldThrowExceptionForInvalidNumericVersion() {
        assertThatThrownBy(() -> Version.parse("a.b.c"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "1.0.0, 2.0.0, -1",
            "1.0.0, 1.1.0, -1",
            "1.0.0, 1.0.1, -1",
            "1.2.3, 1.2.3, 0"
    })
    void itShouldCompareVersions(String version1, String version2, int expectedResult) {
        Version v1 = Version.parse(version1);
        Version v2 = Version.parse(version2);

        assertThat(Integer.signum(v1.compareTo(v2))).isEqualTo(expectedResult);
    }

    @Test
    void itShouldReturnPositiveForGreaterVersion() {
        Version v1 = Version.parse("2.0.0");
        Version v2 = Version.parse("1.0.0");

        assertThat(v1.compareTo(v2)).isGreaterThan(0);
    }

    @Test
    void itShouldBeEqualRegardlessOfPrefix() {
        Version v1 = Version.parse("1.0");
        Version v2 = Version.parse("v1.0");

        assertThat(v1).isEqualTo(v2);
    }

    @Test
    void itShouldHaveSameHashCodeForEqualVersions() {
        Version v1 = Version.parse("1.0");
        Version v2 = Version.parse("v1.0");

        assertThat(v1).hasSameHashCodeAs(v2);
    }

    @Test
    void itShouldCompareVersionsWithDifferentFormats() {
        Version v1 = Version.parse("v1.0");
        Version v2 = Version.parse("1.1");

        assertThat(v1.compareTo(v2)).isLessThan(0);
    }
}

