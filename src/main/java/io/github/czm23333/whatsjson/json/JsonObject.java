package io.github.czm23333.whatsjson.json;

import java.util.HashMap;

public class JsonObject extends HashMap<String, JsonElement> implements JsonElement {
    public JsonObject getAsObject(String name) {
        return (JsonObject) get(name);
    }
}