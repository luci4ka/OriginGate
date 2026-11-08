package io.github.origingate.core.lookup;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Small helpers for reading provider JSON. Missing or wrongly typed values read as null or false. */
final class Json {
    private Json() { }

    /** Parses {@code body} as a JSON object, refusing empty, oversized, or invalid responses. */
    static JsonObject parse(String label, String body) throws LookupException {
        if (body == null || body.isEmpty() || body.length() > HttpLookup.MAX_BODY_CHARS) {
            throw new LookupException(label + " sent an empty or oversized response");
        }
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new LookupException(label + " sent a response that is not valid JSON", ex);
        }
    }

    static JsonObject object(JsonObject parent, String name) {
        if (parent == null) return null;
        JsonElement value = parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    static String text(JsonObject parent, String name) {
        if (parent == null) return null;
        JsonElement value = parent.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    static boolean bool(JsonObject parent, String name) {
        if (parent == null) return false;
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonPrimitive()) return false;
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        return "yes".equalsIgnoreCase(value.getAsString()) || "true".equalsIgnoreCase(value.getAsString());
    }

    /** ": message" from the response's {@code message} field, or "" when it has none. */
    static String message(JsonObject root) {
        String message = text(root, "message");
        return message == null ? "" : ": " + IpInfo.clean(message);
    }

    static String message(String body) {
        try {
            return message(JsonParser.parseString(body).getAsJsonObject());
        } catch (RuntimeException ex) {
            return "";
        }
    }
}
