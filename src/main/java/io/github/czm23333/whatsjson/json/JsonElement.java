package io.github.czm23333.whatsjson.json;

public interface JsonElement {
    default boolean isArray() {
        return this instanceof JsonArray;
    }

    default JsonArray asArray() {
        return (JsonArray) this;
    }

    default boolean isObject() {
        return this instanceof JsonObject;
    }

    default JsonObject asObject() {
        return (JsonObject) this;
    }

    default boolean isPrimitive() {
        return this instanceof JsonPrimitive;
    }

    default JsonPrimitive asPrimitive() {
        return (JsonPrimitive) this;
    }

    default boolean isNull() {
        return this instanceof JsonNull;
    }
}