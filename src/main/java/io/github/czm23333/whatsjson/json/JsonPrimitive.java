package io.github.czm23333.whatsjson.json;

public class JsonPrimitive implements JsonElement {
    private final Object value;
    public static final JsonPrimitive TRUE = new JsonPrimitive(true);
    public static final JsonPrimitive FALSE = new JsonPrimitive(false);

    public JsonPrimitive(Number num) {
        value = num;
    }

    public JsonPrimitive(boolean bool) {
        value = bool;
    }

    public JsonPrimitive(String str) {
        value = str;
    }

    public boolean isString() {
        return value instanceof String;
    }

    public String asString() {
        return (String) value;
    }

    public boolean isBoolean() {
        return value instanceof Boolean;
    }

    public boolean asBoolean() {
        return (Boolean) value;
    }

    public boolean isNumber() {
        return value instanceof Number;
    }

    public Number asNumber() {
        return (Number) value;
    }
}