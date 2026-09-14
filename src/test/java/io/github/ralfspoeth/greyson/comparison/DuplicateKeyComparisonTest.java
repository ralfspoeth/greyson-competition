package io.github.ralfspoeth.greyson.comparison;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonParser;
import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;

import static io.github.ralfspoeth.json.query.Pointer.parse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Duplicate member names, three ways.
 *
 * <p><a href="https://datatracker.ietf.org/doc/html/rfc8259#section-4">RFC
 * 8259</a> says names within an object SHOULD be unique and that the behaviour
 * of software receiving non-unique names is "unpredictable". The nst JSON Test
 * Suite accordingly files {@code y_object_duplicate_key.json} under <em>must
 * accept</em> — it says nothing about which value survives.
 *
 * <p>All three libraries here resolve it the same way, by collecting members
 * into a map: the last mapping wins and the earlier one is discarded silently.
 * This test exists so that claim is checked rather than assumed.
 *
 * <p>Where they differ is in whether you can opt out. Jackson's streaming layer
 * offers strict duplicate detection, which makes a duplicate name a parse error;
 * Greyson has no such switch and cannot report that a value was dropped.
 */
class DuplicateKeyComparisonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The shape of the suite's y_object_duplicate_key.json. */
    private static final String DUPLICATE = """
            {"a": 1, "a": 2}
            """;

    @Test
    void allThreeKeepTheLastMapping() throws IOException {
        var greyson = Greyson.readValue(Reader.of(DUPLICATE)).orElseThrow();
        var jackson = MAPPER.readTree(DUPLICATE);
        var gson = JsonParser.parseString(DUPLICATE).getAsJsonObject();

        assertAll(
                // Greyson: one member left, holding the later value
                () -> assertEquals(1, assertInstanceOf(JsonObject.class, greyson).size()),
                () -> assertEquals(2, parse("a").intOrThrow(greyson)),
                // Jackson
                () -> assertEquals(1, jackson.size()),
                () -> assertEquals(2, jackson.get("a").asInt()),
                // Gson
                () -> assertEquals(1, gson.size()),
                () -> assertEquals(2, gson.get("a").getAsInt())
        );
    }

    @Test
    void theDiscardedValueLeavesNoTrace() throws IOException {
        // the parsed document is indistinguishable from one that never had a
        // duplicate — there is no flag, count, or warning to inspect afterwards
        var fromDuplicate = Greyson.readValue(Reader.of(DUPLICATE)).orElseThrow();
        var fromClean = Greyson.readValue(Reader.of("""
                {"a": 2}
                """)).orElseThrow();

        assertEquals(fromClean, fromDuplicate);
    }
}
