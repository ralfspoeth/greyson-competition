package io.github.ralfspoeth.json.comparison;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static io.github.ralfspoeth.json.data.Builder.arrayBuilder;
import static io.github.ralfspoeth.json.data.Builder.objectBuilder;
import static io.github.ralfspoeth.json.query.Pointer.parse;
import static io.github.ralfspoeth.json.query.Selector.all;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A worked example of the kind of task Greyson handles more cleanly than a
 * mutable tree model such as Jackson's {@code JsonNode}: a schema-less document
 * that must be walked <em>exhaustively</em>, transformed <em>immutably</em>, and
 * queried across irregular nesting — without binding to any POJO.
 *
 * <p>The contrast in a sentence: the equivalent tree-model code reaches for
 * {@code instanceof}/{@code isJsonObject()} ladders (no compiler-checked
 * exhaustiveness), mutates nodes in place or {@code deepCopy()}s them (no
 * immutability guarantee), and navigates through nullable getters rather than
 * {@code Optional}. Each test below shows the Gson counterpart alongside the
 * Greyson code; see {@link RedactionComparisonTest} for the Jackson side too.</p>
 */
class GreysonShinesTest {

    // A messy, heterogeneous account export. Sensitive fields ("password",
    // "token", "secret", "cardNumber") sit at four different depths.
    private static final String EXPORT = """
            {
              "id": 42,
              "profile": {
                "name": "Ada Lovelace",
                "email": "ada@example.com",
                "password": "hunter2",
                "addresses": [
                  {"kind": "home", "city": "London", "zip": "E1 6AN"},
                  {"kind": "work", "city": "Cambridge", "zip": "CB2 1TN"}
                ]
              },
              "payment": {
                "methods": [
                  {"type": "card", "cardNumber": "4111111111111111", "exp": "12/29"},
                  {"type": "card", "cardNumber": "5500005555555559", "exp": "01/30"}
                ]
              },
              "sessions": [
                {"id": "s-1", "token": "abc.def.ghi", "ip": "10.0.0.1"},
                {"id": "s-2", "token": "uvw.xyz.123", "ip": "10.0.0.2"}
              ],
              "metadata": {
                "version": 3,
                "tags": ["beta", "internal"],
                "audit": {"secret": "do-not-log", "createdBy": "system"}
              }
            }
            """;

    /**
     * Recursively mask the string value of any member whose key is sensitive,
     * at any depth, returning a fresh immutable tree. Because {@link JsonValue}
     * is a sealed hierarchy of records, this {@code switch} is total: the
     * compiler proves every shape is handled, with no {@code instanceof} ladder
     * and no {@code default} branch to forget.
     */
    static JsonValue redact(JsonValue value, Predicate<String> sensitive) {
        return switch (value) {
            case JsonObject(var members) -> {
                var b = objectBuilder();
                members.forEach((key, val) -> b.put(key,
                        sensitive.test(key) && val instanceof JsonString
                                ? Basic.of("***")
                                : redact(val, sensitive)));
                yield b.build();
            }
            case JsonArray(var elements) -> {
                var b = arrayBuilder();
                elements.forEach(e -> b.add(redact(e, sensitive)));
                yield b.build();
            }
            case Basic<?> leaf -> leaf; // numbers, booleans, nulls, and non-sensitive strings
        };
    }

    static void redact(Builder<? extends JsonValue> bldr, Predicate<String> sensitive) {
        switch (bldr) {
            case Builder.ObjectBuilder ob -> ob.data().forEach((key, child) -> {
                if (sensitive.test(key) && child instanceof Builder.BasicBuilder bb && bb.get() instanceof JsonString) {
                    bb.set(Basic.of("***"));
                } else {
                    redact(child, sensitive);
                }
            });
            case Builder.ArrayBuilder ab -> ab.data().forEach(child -> redact(child, sensitive));
            case Builder.BasicBuilder _ -> {
            }
        }
    }

    // The Gson counterpart: a recursive walk over a mutable JsonElement tree,
    // with an isJsonObject()/isJsonArray() ladder in place of the sealed switch.
    static JsonElement gsonRedact(JsonElement node, Set<String> sensitive) {
        if (node.isJsonObject()) {
            var out = new com.google.gson.JsonObject();
            for (var e : node.getAsJsonObject().entrySet()) {
                var v = e.getValue();
                if (sensitive.contains(e.getKey()) && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                    out.addProperty(e.getKey(), "***");
                } else {
                    out.add(e.getKey(), gsonRedact(v, sensitive));
                }
            }
            return out;
        } else if (node.isJsonArray()) {
            var out = new com.google.gson.JsonArray();
            for (var e : node.getAsJsonArray()) out.add(gsonRedact(e, sensitive));
            return out;
        } else {
            return node;
        }
    }

    @Test
    void deepRedactionIsExhaustiveAndImmutable() throws IOException {
        Set<String> sensitive = Set.of("password", "token", "secret", "cardNumber");

        var docBuilder = Greyson.readBuilder(Reader.of(EXPORT)).orElseThrow();
        var orig = docBuilder.build();
        redact(docBuilder, sensitive::contains);
        var clean = docBuilder.build();

        var gsonClean = gsonRedact(JsonParser.parseString(EXPORT), sensitive);

        assertAll(
                // every sensitive leaf is masked, wherever it sits in the tree
                () -> assertEquals("***", parse("profile/password").stringOrThrow(clean)),
                () -> assertEquals("***", parse("payment/methods/[0]/cardNumber").stringOrThrow(clean)),
                () -> assertEquals("***", parse("payment/methods/[1]/cardNumber").stringOrThrow(clean)),
                () -> assertEquals("***", parse("sessions/[1]/token").stringOrThrow(clean)),
                () -> assertEquals("***", parse("metadata/audit/secret").stringOrThrow(clean)),
                // non-sensitive data is preserved verbatim
                () -> assertEquals("ada@example.com", parse("profile/email").stringOrThrow(clean)),
                () -> assertEquals(3, parse("metadata/version").intOrThrow(clean)),
                () -> assertEquals("London", parse("profile/addresses/[0]/city").stringOrThrow(clean)),
                // and the original document is untouched — redact never mutated it
                () -> assertEquals("hunter2", parse("profile/password").stringOrThrow(orig)),
                () -> assertEquals("4111111111111111",
                        parse("payment/methods/[0]/cardNumber").stringOrThrow(orig)),
                // the Gson counterpart masks the same leaves, via verbose tree navigation
                () -> assertEquals("***", gsonClean.getAsJsonObject()
                        .getAsJsonObject("profile").get("password").getAsString()),
                () -> assertEquals("***", gsonClean.getAsJsonObject()
                        .getAsJsonObject("payment").getAsJsonArray("methods")
                        .get(0).getAsJsonObject().get("cardNumber").getAsString())
        );
    }

    @Test
    void crossCuttingExtractionWithoutASchema() throws IOException {
        // reads source into an optional value
        var doc = Greyson.readValue(Reader.of(EXPORT));
        //
        var sessionIds = doc
                .stream()
                .flatMap(parse("sessions").select(all()))
                .flatMap(s -> s.get("id").stream())
                .flatMap(v -> v.string().stream())
                .toList();

        // how many payment methods are cards
        long cards = doc.stream()
                .flatMap(parse("payment/methods").select(all()))
                .flatMap(m -> m.get("type").stream())
                .flatMap(v -> v.string().stream())
                .filter("card"::equals)
                .count();

        // the Gson counterpart: manual loops over the JsonElement tree
        var gsonDoc = JsonParser.parseString(EXPORT).getAsJsonObject();
        var gsonSessionIds = new ArrayList<String>();
        for (var s : gsonDoc.getAsJsonArray("sessions")) {
            gsonSessionIds.add(s.getAsJsonObject().get("id").getAsString());
        }
        long gsonCards = 0;
        for (var m : gsonDoc.getAsJsonObject("payment").getAsJsonArray("methods")) {
            if ("card".equals(m.getAsJsonObject().get("type").getAsString())) gsonCards++;
        }
        final long finalGsonCards = gsonCards;

        assertAll(
                () -> assertEquals(List.of("s-1", "s-2"), sessionIds),
                () -> assertEquals(sessionIds, gsonSessionIds),
                () -> assertEquals(2L, cards),
                () -> assertEquals(cards, finalGsonCards)
        );
    }

    @Test
    void immutableTargetedUpdate() throws IOException {
        var doc = Greyson.readValue(Reader.of(EXPORT)).orElseThrow();
        var profileBefore = parse("profile").require(doc); // off-path for both edits below

        // bump a nested counter and revoke the first session, both immutably
        var bumped = parse("metadata/version").with(doc, Basic.of(4));
        var revoked = parse("sessions/[0]").without(bumped);

        // the Gson counterpart: JsonElement is mutable, so leaving the original
        // intact needs a full deepCopy() — which shares nothing.
        var gsonRoot = JsonParser.parseString(EXPORT);
        var gsonProfileBefore = gsonRoot.getAsJsonObject().get("profile");
        var gsonCopy = gsonRoot.deepCopy();
        gsonCopy.getAsJsonObject().getAsJsonObject("metadata").addProperty("version", 4);

        assertAll(
                () -> assertEquals(4, parse("metadata/version").intOrThrow(revoked)),
                () -> assertEquals(1, parse("sessions").require(revoked).elements().size()),
                () -> assertEquals("s-2", parse("sessions/[0]/id").stringOrThrow(revoked)),
                // the original is intact at every step
                () -> assertEquals(3, parse("metadata/version").intOrThrow(doc)),
                () -> assertEquals(2, parse("sessions").require(doc).elements().size()),
                // and the untouched "profile" subtree is shared by identity through
                // both edits — the rebuild touches only objects along each path
                () -> assertSame(profileBefore, parse("profile").require(revoked)),
                // Gson: the copy is updated and the original stays intact ONLY due to
                // deepCopy — and, unlike Greyson, the profile subtree is NOT shared
                () -> assertEquals(4, gsonCopy.getAsJsonObject()
                        .getAsJsonObject("metadata").get("version").getAsInt()),
                () -> assertEquals(3, gsonRoot.getAsJsonObject()
                        .getAsJsonObject("metadata").get("version").getAsInt()),
                () -> assertNotSame(gsonProfileBefore, gsonCopy.getAsJsonObject().get("profile"))
        );
    }
}
