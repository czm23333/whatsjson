package io.github.czm23333.whatsjson.json;

import java.util.ArrayList;
import java.util.Collection;

public class JsonArray extends ArrayList<JsonElement> implements JsonElement {
    public JsonArray() {
        super();
    }

    public JsonArray(int initialCapacity) {
        super(initialCapacity);
    }

    public JsonArray(Collection<? extends JsonElement> c) {
        super(c);
    }
}