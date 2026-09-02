package io.github.ralfspoeth.json.comparison;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.query.Selector;
import io.github.ralfspoeth.json.query.Shape;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static io.github.ralfspoeth.json.query.Pointer.parse;
import static io.github.ralfspoeth.json.query.Pointer.self;
import static io.github.ralfspoeth.json.query.Shape.each;
import static io.github.ralfspoeth.json.query.Shape.member;
import static io.github.ralfspoeth.json.query.Shape.number;
import static io.github.ralfspoeth.json.query.Shape.string;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Structural checking without a schema, three ways.
 *
 * <p>This is the case Greyson's {@link Shape} is built for: a payload nobody
 * controls, no schema document to validate against, and a need to answer two
 * different questions from one description — <em>which records are usable?</em>
 * and <em>what exactly is wrong with the rest?</em>
 *
 * <p>Jackson and Gson can both answer those questions, but neither has a way to
 * <em>say</em> the shape, so each field check, each type name, and — most
 * tellingly — each path string has to be assembled by hand.
 */
class ShapeComparisonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FEED = """
            {"records": [
              {"id": "r1", "amount": 100,    "ccy": "USD"},
              {"id": "r2", "amount": "oops", "ccy": "EUR"},
              {"id": "r3",                   "ccy": "GBP"},
              {            "amount": 50,     "ccy": "JPY"},
              {"id": "r5", "amount": 75,     "ccy": "CHF"}
            ]}
            """;

    /** One description, reused for both filtering and reporting. */
    private static final Shape RECORD = member("id", string())
            .and(member("amount", number()))
            .and(member("ccy", string()));

    // ---- what is wrong, and where? ----------------------------------------

    @Test
    void greysonReportsDefectsWithFullPaths() throws IOException {
        var doc = Greyson.readValue(Reader.of(FEED)).orElseThrow();

        // the whole check is one expression; the paths come out of it for free,
        // because each() rebases onto [i] and must() rebases onto "records"
        var report = parse("records").must(each(RECORD))
                .violations(doc)
                .map(Shape.Violation::toString)
                .toList();

        assertEquals(List.of(
                "records/[1]/amount: expected number, got string",
                "records/[2]/amount: missing required member",
                "records/[3]/id: missing required member"
        ), report);
    }

    @Test
    void allThreeAgreeOnWhichPathsAreDefective() throws IOException {
        var doc = Greyson.readValue(Reader.of(FEED)).orElseThrow();
        var greyson = parse("records").must(each(RECORD))
                .violations(doc)
                .map(v -> v.at().toString())
                .toList();

        assertAll(
                () -> assertEquals(
                        List.of("records/[1]/amount", "records/[2]/amount", "records/[3]/id"),
                        greyson),
                () -> assertEquals(greyson, jacksonDefectPaths()),
                () -> assertEquals(greyson, gsonDefectPaths())
        );
    }

    // Jackson: no way to state the shape, so the walk, the type test and the
    // path assembly are all written out — once per field, and again for every
    // new record type.
    private static List<String> jacksonDefectPaths() throws IOException {
        var defects = new ArrayList<String>();
        JsonNode records = MAPPER.readTree(FEED).path("records");
        for (int i = 0; i < records.size(); i++) {
            JsonNode record = records.get(i);
            jacksonCheck(record, i, "id", JsonNode::isTextual, defects);
            jacksonCheck(record, i, "amount", JsonNode::isNumber, defects);
            jacksonCheck(record, i, "ccy", JsonNode::isTextual, defects);
        }
        return defects;
    }

    private static void jacksonCheck(
            JsonNode record, int index, String field,
            Predicate<JsonNode> ok, List<String> defects) {
        var value = record.get(field);
        if (value == null || !ok.test(value)) {
            defects.add("records/[" + index + "]/" + field);
        }
    }

    // Gson: same story, with its own null/type vocabulary.
    private static List<String> gsonDefectPaths() {
        var defects = new ArrayList<String>();
        var records = JsonParser.parseString(FEED).getAsJsonObject().getAsJsonArray("records");
        for (int i = 0; i < records.size(); i++) {
            JsonObject record = records.get(i).getAsJsonObject();
            gsonCheck(record, i, "id", true, defects);
            gsonCheck(record, i, "amount", false, defects);
            gsonCheck(record, i, "ccy", true, defects);
        }
        return defects;
    }

    private static void gsonCheck(
            JsonObject record, int index, String field, boolean wantString, List<String> defects) {
        JsonElement value = record.get(field);
        boolean ok = value != null
                && value.isJsonPrimitive()
                && (wantString ? value.getAsJsonPrimitive().isString()
                : value.getAsJsonPrimitive().isNumber());
        if (!ok) {
            defects.add("records/[" + index + "]/" + field);
        }
    }

    // ---- which records are usable? ----------------------------------------

    @Test
    void theSameDescriptionAlsoFilters() throws IOException {
        var doc = Greyson.readValue(Reader.of(FEED)).orElseThrow();

        // RECORD is not re-stated here — the shape that explains is the shape
        // that filters, and where() keeps it inside the query algebra
        var usable = parse("records").select(Selector.all().where(RECORD))
                .presentValues(v -> self().member("id").stringValue(v))
                .apply(doc)
                .toList();

        assertAll(
                () -> assertEquals(List.of("r1", "r5"), usable),
                () -> assertEquals(usable, jacksonUsable()),
                () -> assertEquals(usable, gsonUsable())
        );
    }

    private static List<String> jacksonUsable() throws IOException {
        var usable = new ArrayList<String>();
        for (JsonNode record : MAPPER.readTree(FEED).path("records")) {
            var id = record.get("id");
            var amount = record.get("amount");
            var ccy = record.get("ccy");
            if (id != null && id.isTextual()
                    && amount != null && amount.isNumber()
                    && ccy != null && ccy.isTextual()) {
                usable.add(id.asText());
            }
        }
        return usable;
    }

    private static List<String> gsonUsable() {
        var usable = new ArrayList<String>();
        for (JsonElement element : JsonParser.parseString(FEED)
                .getAsJsonObject().getAsJsonArray("records")) {
            var record = element.getAsJsonObject();
            var id = record.get("id");
            var amount = record.get("amount");
            var ccy = record.get("ccy");
            if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
                    && amount != null && amount.isJsonPrimitive() && amount.getAsJsonPrimitive().isNumber()
                    && ccy != null && ccy.isJsonPrimitive() && ccy.getAsJsonPrimitive().isString()) {
                usable.add(id.getAsString());
            }
        }
        return usable;
    }

    // ---- the description is data -------------------------------------------

    @Test
    void theShapeCanDescribeItself() {
        // neither Jackson nor Gson has anything to print here: the "shape" only
        // exists as control flow inside the loops above
        assertEquals(
                "\"id\": string and \"amount\": number and \"ccy\": string",
                RECORD.explain());
    }
}
