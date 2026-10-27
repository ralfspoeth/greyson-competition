package io.github.ralfspoeth.greyson.comparison;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.Basic;
import io.github.ralfspoeth.json.data.JsonNumber;
import io.github.ralfspoeth.json.query.Shape;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Map;

import static io.github.ralfspoeth.json.query.Pointer.self;
import static io.github.ralfspoeth.json.query.Shape.anything;
import static io.github.ralfspoeth.json.query.Shape.array;
import static io.github.ralfspoeth.json.query.Shape.exact;
import static io.github.ralfspoeth.json.query.Shape.exactlyOne;
import static io.github.ralfspoeth.json.query.Shape.member;
import static io.github.ralfspoeth.json.query.Shape.nul;
import static io.github.ralfspoeth.json.query.Shape.number;
import static io.github.ralfspoeth.json.query.Shape.object;
import static io.github.ralfspoeth.json.query.Shape.optional;
import static io.github.ralfspoeth.json.query.Shape.satisfying;
import static io.github.ralfspoeth.json.query.Shape.string;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validating a <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0</a>
 * request object, three ways.
 *
 * <p>This is a good test of a shape language because the rules are not merely
 * "these members, those types":
 * <ul>
 * <li>{@code jsonrpc} must equal the <em>literal</em> {@code "2.0"} - a
 * discriminator, not a type.</li>
 * <li>{@code id} is <em>optional but constrained</em>: absent means the message
 * is a notification and no response is owed, and present means string, number
 * or null.</li>
 * <li>{@code params} is likewise optional but constrained, to array or
 * object.</li>
 * </ul>
 *
 * <p>So absence carries meaning, and conflating "absent" with "present but
 * malformed" is not a lenient reading - it silently turns a request into a
 * notification and leaves the peer waiting for a reply that never comes.
 *
 * <p>Greyson says all of that once, as a value. Jackson and Gson have no way to
 * state it, so each rule becomes a hand-written null-and-type check - and both
 * libraries coerce on the convenient accessors, which makes the shorter code the
 * wrong code.
 */
class JsonRpcComparisonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The request object of JSON-RPC 2.0, as one value. */
    private static final Shape REQUEST = member("jsonrpc", exact(Basic.of("2.0")))
            .and(member("method", string()))
            .and(optional("id", string().or(number()).or(nul())))
            .and(optional("params", array().or(object())));

    /** Message text to whether it is a well-formed request object. */
    private static final Map<String, Boolean> MESSAGES = Map.ofEntries(
            // -- valid ------------------------------------------------------
            Map.entry("""
                    {"jsonrpc": "2.0", "method": "subtract", "id": 1}""", true),
            Map.entry("""
                    {"jsonrpc": "2.0", "method": "subtract", "id": "abc"}""", true),
            // the spec permits a null id, and it is still a request
            Map.entry("""
                    {"jsonrpc": "2.0", "method": "subtract", "id": null}""", true),
            // a notification: no id at all
            Map.entry("""
                    {"jsonrpc": "2.0", "method": "update"}""", true),
            Map.entry("""
                    {"jsonrpc": "2.0", "method": "subtract", "params": [42, 23], "id": 1}""", true),
            Map.entry("""
                    {"jsonrpc": "2.0", "method": "subtract", "params": {"a": 1}, "id": 1}""", true),
            // -- invalid ----------------------------------------------------
            Map.entry("""
                    {"jsonrpc": "1.0", "method": "subtract", "id": 1}""", false),
            Map.entry("""
                    {"method": "subtract", "id": 1}""", false),
            Map.entry("""
                    {"jsonrpc": "2.0", "id": 1}""", false),
            // method must be a string, not something that stringifies
            Map.entry("""
                    {"jsonrpc": "2.0", "method": 42, "id": 1}""", false),
            // a boolean id must not be read as an absent one
            Map.entry("""
                    {"jsonrpc": "2.0", "method": "subtract", "id": true}""", false),
            Map.entry("""
                    {"jsonrpc": "2.0", "method": "subtract", "params": "nope", "id": 1}""", false)
    );

    // ---- the response object, §5 ------------------------------------------

    /** The spec's integers, which {@code number()} alone cannot express. */
    private static final Shape WHOLE = satisfying("a whole number",
            v -> v instanceof JsonNumber(var n) && n.stripTrailingZeros().scale() <= 0);

    /**
     * §5.1: an integer code, a string message, optional unconstrained data. No
     * leading {@code object()}: each {@code member} already rejects a non-object
     * with that very complaint, so adding it only multiplies the message.
     */
    private static final Shape ERROR = member("code", WHOLE)
            .and(member("message", string()))
            .and(optional("data", anything()));

    /**
     * §5. Note what the request shape did <em>not</em> need: an id that is
     * required rather than optional, and a choice between two members that must
     * be exclusive.
     */
    private static final Shape RESPONSE = member("jsonrpc", exact(Basic.of("2.0")))
            .and(member("id", string().or(WHOLE).or(nul())))
            .and(exactlyOne(Map.of("result", anything(), "error", ERROR)));

    /** Response text to whether it is a well-formed response object. */
    private static final Map<String, Boolean> RESPONSES = Map.ofEntries(
            // -- valid ------------------------------------------------------
            Map.entry("""
                    {"jsonrpc": "2.0", "result": 7, "id": 1}""", true),
            // a null result is a result: the spec constrains its presence, not its value
            Map.entry("""
                    {"jsonrpc": "2.0", "result": null, "id": "abc"}""", true),
            // a null id is what you send when the request's id was undeterminable
            Map.entry("""
                    {"jsonrpc": "2.0", "error": {"code": -32600, "message": "Invalid Request"}, "id": null}""", true),
            Map.entry("""
                    {"jsonrpc": "2.0", "error": {"code": -32000, "message": "x", "data": {"d": 1}}, "id": 2}""", true),
            // -1.0 is an integer value, scale notwithstanding
            Map.entry("""
                    {"jsonrpc": "2.0", "error": {"code": -1.0, "message": "x"}, "id": 3}""", true),
            // -- invalid ----------------------------------------------------
            Map.entry("""
                    {"jsonrpc": "2.0", "id": 1}""", false),
            Map.entry("""
                    {"jsonrpc": "2.0", "result": 7, "error": {"code": -1, "message": "x"}, "id": 1}""", false),
            Map.entry("""
                    {"jsonrpc": "2.0", "result": 7}""", false),
            Map.entry("""
                    {"jsonrpc": "2.0", "result": 7, "id": true}""", false),
            Map.entry("""
                    {"jsonrpc": "2.0", "error": {"code": -1.5, "message": "x"}, "id": 1}""", false),
            Map.entry("""
                    {"jsonrpc": "2.0", "error": {"code": -1}, "id": 1}""", false),
            Map.entry("""
                    {"jsonrpc": "2.0", "error": "oops", "id": 1}""", false),
            Map.entry("""
                    {"jsonrpc": "1.0", "result": 7, "id": 1}""", false)
    );

    @Test
    void allThreeClassifyEveryResponseAlike() {
        assertAll(RESPONSES.entrySet().stream().map(e -> () -> {
            var json = e.getKey();
            var valid = e.getValue();
            assertAll(
                    "response " + json,
                    () -> assertEquals(valid, greysonResponseValid(json), "greyson"),
                    () -> assertEquals(valid, jacksonResponseValid(json), "jackson"),
                    () -> assertEquals(valid, gsonResponseValid(json), "gson")
            );
        }));
    }

    private static boolean greysonResponseValid(String json) throws IOException {
        return Greyson.readValue(Reader.of(json))
                .filter(RESPONSE.predicate())
                .isPresent();
    }

    // Jackson. Two rules get noticeably harder than in the request: the
    // exclusive choice becomes a presence count, and "integer" has to avoid
    // isIntegralNumber(), which asks whether the *node* is an integer type -
    // false for -1.0, which is an integer value. decimalValue() is the honest
    // check, and is what Greyson's predicate does too.
    private static boolean jacksonResponseValid(String json) throws IOException {
        JsonNode n = MAPPER.readTree(json);
        if (!n.isObject()) return false;
        JsonNode version = n.get("jsonrpc");
        if (version == null || !version.isTextual() || !"2.0".equals(version.textValue())) return false;
        if (!n.has("id")) return false;
        JsonNode id = n.get("id");
        if (!(id.isTextual() || isWhole(id) || id.isNull())) return false;
        if (n.has("result") == n.has("error")) return false;   // both, or neither
        if (n.has("error")) {
            JsonNode error = n.get("error");
            if (!error.isObject()) return false;
            JsonNode code = error.get("code");
            if (code == null || !isWhole(code)) return false;
            JsonNode message = error.get("message");
            return message != null && message.isTextual();
        }
        return true;
    }

    private static boolean isWhole(JsonNode n) {
        return n.isNumber() && n.decimalValue().stripTrailingZeros().scale() <= 0;
    }

    // Gson: the same, and with no isIntegralNumber() even to be tempted by -
    // the number has to be pulled out as a BigDecimal and inspected.
    private static boolean gsonResponseValid(String json) {
        JsonElement e = JsonParser.parseString(json);
        if (!e.isJsonObject()) return false;
        JsonObject o = e.getAsJsonObject();
        JsonElement version = o.get("jsonrpc");
        if (version == null || !isString(version) || !"2.0".equals(version.getAsString())) return false;
        if (!o.has("id")) return false;
        JsonElement id = o.get("id");
        if (!(isString(id) || isWhole(id) || id.isJsonNull())) return false;
        if (o.has("result") == o.has("error")) return false;
        if (o.has("error")) {
            JsonElement error = o.get("error");
            if (!error.isJsonObject()) return false;
            JsonObject err = error.getAsJsonObject();
            JsonElement code = err.get("code");
            if (code == null || !isWhole(code)) return false;
            JsonElement message = err.get("message");
            return message != null && isString(message);
        }
        return true;
    }

    private static boolean isWhole(JsonElement e) {
        return isNumber(e) && e.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
    }

    @Test
    void greysonLocatesTheDefectInsideTheErrorObject() throws IOException {
        var doc = Greyson.readValue(Reader.of("""
                {"jsonrpc": "2.0", "error": {"code": -1.5, "message": "x"}, "id": 1}""")).orElseThrow();

        // exactly one of result/error is present, so its own violations come
        // through under its key. An exclusive-or of member() shapes would have
        // reported "nothing matched" and thrown this away - and the two boolean
        // predicates above cannot say it at all.
        assertEquals(List.of("error/code: expected a whole number, got -1.5"),
                RESPONSE.violations(doc).map(Shape.Violation::toString).toList());
    }

    @Test
    void greysonNamesWhichSideOfTheChoiceWentWrong() throws IOException {
        var both = Greyson.readValue(Reader.of("""
                {"jsonrpc": "2.0", "result": 7, "error": {"code": -1, "message": "x"}, "id": 1}""")).orElseThrow();
        var neither = Greyson.readValue(Reader.of("""
                {"jsonrpc": "2.0", "id": 1}""")).orElseThrow();

        assertAll(
                () -> assertEquals(
                        List.of("<root>: expected exactly one of \"error\", \"result\", got \"error\", \"result\""),
                        RESPONSE.violations(both).map(Shape.Violation::toString).toList()),
                () -> assertEquals(
                        List.of("<root>: expected exactly one of \"error\", \"result\", got none"),
                        RESPONSE.violations(neither).map(Shape.Violation::toString).toList())
        );
    }

    // ---- all three agree --------------------------------------------------

    @Test
    void allThreeClassifyEveryMessageAlike() {
        assertAll(MESSAGES.entrySet().stream().map(e -> () -> {
            var json = e.getKey();
            var valid = e.getValue();
            assertAll(
                    "message " + json,
                    () -> assertEquals(valid, greysonValid(json), "greyson"),
                    () -> assertEquals(valid, jacksonValid(json), "jackson"),
                    () -> assertEquals(valid, gsonValid(json), "gson")
            );
        }));
    }

    private static boolean greysonValid(String json) throws IOException {
        return Greyson.readValue(Reader.of(json))
                .filter(REQUEST.predicate())
                .isPresent();
    }

    // Jackson: no way to state the shape, so every rule is a check. Note that
    // the convenient accessors coerce - method.asText() on the number 42 yields
    // "42" and would accept it - so the type tests have to be explicit, and
    // has() rather than get() != null is what separates an absent id from a
    // present null one.
    private static boolean jacksonValid(String json) throws IOException {
        JsonNode n = MAPPER.readTree(json);
        if (!n.isObject()) return false;
        JsonNode version = n.get("jsonrpc");
        if (version == null || !version.isTextual() || !"2.0".equals(version.textValue())) return false;
        JsonNode method = n.get("method");
        if (method == null || !method.isTextual()) return false;
        if (n.has("id")) {
            JsonNode id = n.get("id");
            if (!(id.isTextual() || id.isNumber() || id.isNull())) return false;
        }
        if (n.has("params")) {
            JsonNode params = n.get("params");
            return params.isArray() || params.isObject();
        }
        return true;
    }

    // Gson: the same story, plus there is no isString() on JsonElement - it is
    // isJsonPrimitive() and then getAsJsonPrimitive().isString(), twice per
    // field. getAsString() coerces here too.
    private static boolean gsonValid(String json) {
        JsonElement e = JsonParser.parseString(json);
        if (!e.isJsonObject()) return false;
        JsonObject o = e.getAsJsonObject();
        JsonElement version = o.get("jsonrpc");
        if (version == null || !isString(version) || !"2.0".equals(version.getAsString())) return false;
        JsonElement method = o.get("method");
        if (method == null || !isString(method)) return false;
        if (o.has("id")) {
            JsonElement id = o.get("id");
            if (!(isString(id) || isNumber(id) || id.isJsonNull())) return false;
        }
        if (o.has("params")) {
            JsonElement params = o.get("params");
            return params.isJsonArray() || params.isJsonObject();
        }
        return true;
    }

    private static boolean isString(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static boolean isNumber(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    // ---- what only Greyson gives you --------------------------------------

    @Test
    void greysonSaysWhichRuleBrokeAndWhere() throws IOException {
        var doc = Greyson.readValue(Reader.of("""
                {"jsonrpc": "1.0", "method": 42, "params": "nope", "id": true}""")).orElseThrow();

        var report = REQUEST.violations(doc).map(Shape.Violation::toString).toList();

        assertEquals(List.of(
                "jsonrpc: expected \"2.0\", got \"1.0\"",
                "method: expected string, got number",
                "id: expected (string or number or null), got true",
                "params: expected (array or object), got string"
        ), report);
        // the boolean-valued predicates above answer "is it valid"; neither
        // Jackson nor Gson has anything to print here without writing the
        // messages, the paths and the accumulation by hand
    }

    @Test
    void aMalformedIdIsNotAnAbsentOne() throws IOException {
        var notification = Greyson.readValue(Reader.of("""
                {"jsonrpc": "2.0", "method": "update"}""")).orElseThrow();
        var brokenId = Greyson.readValue(Reader.of("""
                {"jsonrpc": "2.0", "method": "update", "id": true}""")).orElseThrow();

        assertAll(
                // the notification is well-formed, and owes no response
                () -> assertTrue(REQUEST.predicate().test(notification)),
                () -> assertTrue(self().member("id").apply(notification).isEmpty(),
                        "no id: a notification"),
                // the broken one is rejected rather than mistaken for one
                () -> assertFalse(REQUEST.predicate().test(brokenId)),
                () -> assertEquals(
                        List.of("id: expected (string or number or null), got true"),
                        REQUEST.violations(brokenId).map(Shape.Violation::toString).toList())
        );
    }

    @Test
    void theShapeDescribesItself() {
        // the spec, printable - Jackson's and Gson's rules exist only as the
        // control flow of the methods above
        assertEquals(
                "\"jsonrpc\": \"2.0\" and \"method\": string "
                        + "and \"id\"?: (string or number or null) "
                        + "and \"params\"?: (array or object)",
                REQUEST.explain());
    }
}
