package io.github.czm23333.whatsjson;

import io.github.czm23333.whatsjson.exception.IllegalSyntaxException;
import io.github.czm23333.whatsjson.json.*;
import org.apache.commons.text.StringEscapeUtils;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

public class WhatsJson {
    private final static int MIN_SLICE_SIZE = 500;

    private final static int threadCnt = Runtime.getRuntime().availableProcessors() * 2 + 1;
    private final ExecutorService threadPool = new ThreadPoolExecutor(threadCnt, threadCnt, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(threadCnt * 2), new ThreadPoolExecutor.CallerRunsPolicy());

    private static ArrayList<CharBuffer> checkAndSpilt(String json) {
        char[] jsonChars = json.toCharArray();
        int nonSpaceCount = 0;
        for (char c : jsonChars) if (!Character.isWhitespace(c)) ++nonSpaceCount;
        int sliceSize = Math.max(nonSpaceCount / threadCnt, MIN_SLICE_SIZE);
        ArrayList<CharBuffer> slices = new ArrayList<>();
        int cur = 0;
        boolean inStr = false;
        nonSpaceCount = 0;
        for (int i = 0, l = jsonChars.length; i < l; ++i) {
            char c = jsonChars[i];
            if (!Character.isWhitespace(c)) ++nonSpaceCount;
            if (inStr) {
                switch (c) {
                    case '"' -> inStr = false;
                    case '\\' -> ++i;
                }
            } else {
                switch (c) {
                    case '"':
                        inStr = true;
                        break;
                    case ',':
                        if (nonSpaceCount >= sliceSize) {
                            slices.add(CharBuffer.wrap(jsonChars, cur, i - cur + 1));
                            cur = i + 1;
                            nonSpaceCount = 0;
                        }
                        break;
                }
            }
        }
        if (inStr) throw new IllegalSyntaxException("Quotes don't match.");
        if (cur < jsonChars.length) slices.add(CharBuffer.wrap(jsonChars, cur, jsonChars.length - cur));

        return slices;
    }

    public JsonElement fromJson(String json) {
        ArrayList<CharBuffer> slices = checkAndSpilt(json);
        ArrayList<Parser> parsers = new ArrayList<>();
        for (CharBuffer slice : slices) parsers.add(new Parser(slice));
        try {
            threadPool.invokeAll(parsers).forEach(future -> {
                try {
                    RuntimeException tmp = future.get();
                    if (tmp != null) throw tmp;
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Parser finalParser = null;
        for (Parser parser : parsers) {
            if (finalParser == null) finalParser = parser;
            else {
                RuntimeException temp = finalParser.merge(parser);
                if (temp != null) throw temp;
            }
        }

        ArrayList<Parser.JsonPart> parts = Objects.requireNonNull(finalParser).parts;
        if (parts.size() != 1 || !(parts.get(0) instanceof Parser.JsonElementPart elementPart))
            throw new IllegalSyntaxException("Incomplete json.");

        return elementPart.element;
    }

    public String toJson(JsonElement element) {
        if (element.isObject()) return toJson(element.asObject());
        else if (element.isArray()) return toJson(element.asArray());
        else if (element.isNull()) return "null";
        else if (element.isPrimitive()) {
            JsonPrimitive primitive = element.asPrimitive();
            if (primitive.isString()) return '"' + StringEscapeUtils.escapeJson(primitive.asString()) + '"';
            else if (primitive.isNumber()) return primitive.asNumber().toString();
            else if (primitive.isBoolean()) return primitive.asBoolean() ? "true" : "false";
            else throw new IllegalArgumentException("Unknown primitive type.");
        } else throw new IllegalArgumentException("Unknown element type.");
    }

    public String toJson(JsonArray arr) {
        StringBuilder tmp = new StringBuilder();
        tmp.append('[');
        boolean addComma = false;
        for (JsonElement element : arr) {
            if (addComma) tmp.append(',');
            else addComma = true;
            tmp.append(toJson(element));
        }
        tmp.append(']');
        return tmp.toString();
    }

    public String toJson(JsonObject obj) {
        StringBuilder tmp = new StringBuilder();
        tmp.append('{');
        boolean addComma = false;
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            if (addComma) tmp.append(',');
            else addComma = true;
            String name = entry.getKey();
            JsonElement value = entry.getValue();
            tmp.append('"');
            tmp.append(name);
            tmp.append('"');
            tmp.append(':');
            tmp.append(toJson(value));
        }
        tmp.append('}');
        return tmp.toString();
    }

    public void shutdown() {
        threadPool.shutdown();
    }

    private static class Parser implements Callable<RuntimeException> {
        public final ArrayList<JsonPart> parts = new ArrayList<>();
        private final CharBuffer slice;
        public RuntimeException fail = null;

        public Parser(CharBuffer slice) {
            this.slice = slice;
        }

        private void formMemberOrInsert(JsonElementPart part) {
            if ((!parts.isEmpty()) && parts.get(parts.size() - 1) instanceof DeclareMemberPart declareMemberPart) {
                parts.remove(parts.size() - 1);
                parts.add(new MemberPart(declareMemberPart.name, part.element));
            } else parts.add(part);
        }

        public RuntimeException merge(Parser other) {
            fail = null;

            for (JsonPart otherPart : other.parts) {
                if (otherPart instanceof OpenObjectEndPart objectEndPart) {
                    JsonObject obj = objectEndPart.object;
                    while (true) {
                        if (parts.isEmpty()) {
                            parts.add(new OpenObjectEndPart(obj));
                            break;
                        }
                        JsonPart part = parts.get(parts.size() - 1);
                        if (part instanceof ObjectBeginPart) {
                            parts.remove(parts.size() - 1);
                            formMemberOrInsert(new JsonElementPart(obj));
                            break;
                        } else if (part instanceof MemberPart memberPart) {
                            parts.remove(parts.size() - 1);
                            obj.put(memberPart.name, memberPart.value);
                        } else if (part instanceof OpenEndPart) {
                            parts.add(new OpenObjectEndPart(obj));
                            break;
                        } else {
                            fail = new IllegalSyntaxException("Non-member part in JSON object.");
                            return fail;
                        }
                    }
                } else if (otherPart instanceof OpenArrayEndPart arrayEndPart) {
                    JsonArray arr = arrayEndPart.array;
                    while (true) {
                        if (parts.isEmpty()) {
                            parts.add(new OpenArrayEndPart(arr));
                            break;
                        }
                        JsonPart part = parts.get(parts.size() - 1);
                        if (part instanceof ArrayBeginPart) {
                            parts.remove(parts.size() - 1);
                            Collections.reverse(arr);
                            formMemberOrInsert(new JsonElementPart(arr));
                            break;
                        } else if (part instanceof JsonElementPart elementPart) {
                            parts.remove(parts.size() - 1);
                            arr.add(elementPart.element);
                        } else if (part instanceof OpenEndPart) {
                            parts.add(new OpenArrayEndPart(arr));
                            break;
                        } else {
                            fail = new IllegalSyntaxException("Non-element part in JSON array.");
                            return fail;
                        }
                    }
                } else parts.add(otherPart);
            }

            other.parts.clear();

            return fail;
        }

        private void tryParseElement(char[] buffer, int cnt) {
            if (cnt == 0) return;
            JsonElement element = null;
            if (cnt == 4 && buffer[0] == 't' && buffer[1] == 'r' && buffer[2] == 'u' && buffer[3] == 'e')
                element = JsonPrimitive.TRUE;
            else if (cnt == 5 && buffer[0] == 'f' && buffer[1] == 'a' && buffer[2] == 'l' && buffer[3] == 's' &&
                    buffer[4] == 'e') element = JsonPrimitive.FALSE;
            else if (cnt == 4 && buffer[0] == 'n' && buffer[1] == 'u' && buffer[2] == 'l' && buffer[3] == 'l')
                element = JsonNull.INSTANCE;
            else {
                if ((buffer[0] == '+' || buffer[0] == '-') && cnt > 1) {
                    boolean flag = true;
                    long value = 0;
                    for (int i = 1; i < cnt; ++i) {
                        if ('0' <= buffer[i] && buffer[i] <= '9') {
                            value *= 10;
                            value += buffer[i] - '0';
                        } else {
                            flag = false;
                            break;
                        }
                    }
                    if (flag) {
                        if (buffer[0] == '-') value = -value;
                        element = new JsonPrimitive(value);
                    }
                } else if ('0' <= buffer[0] && buffer[0] <= '9') {
                    boolean flag = true;
                    long value = 0;
                    for (int i = 0; i < cnt; ++i) {
                        if ('0' <= buffer[i] && buffer[i] <= '9') {
                            value *= 10;
                            value += buffer[i] - '0';
                        } else {
                            flag = false;
                            break;
                        }
                    }
                    if (flag) element = new JsonPrimitive(value);
                }
                if (element == null) {
                    String str = new String(buffer, 0, cnt);
                    try {
                        element = new JsonPrimitive(Double.parseDouble(str));
                    } catch (NumberFormatException e2) {
                        throw new IllegalSyntaxException("Unknown value " + str + '.');
                    }
                }
            }

            formMemberOrInsert(new JsonElementPart(element));
        }

        @Override
        public RuntimeException call() {
            fail = null;

            boolean inStr = false;
            CharBuffer sliceCur = slice.slice();
            char[] buffer = new char[sliceCur.length()];
            int cntCur = 0;
            while (!sliceCur.isEmpty()) {
                char c = sliceCur.get();
                if (inStr) {
                    switch (c) {
                        case '"' -> {
                            inStr = false;
                            formMemberOrInsert(new JsonElementPart(new JsonPrimitive(new String(buffer, 0, cntCur))));
                            cntCur = 0;
                        }
                        case '\\' -> {
                            if (sliceCur.isEmpty()) {
                                fail = new IllegalSyntaxException("Missing escape char.");
                                return fail;
                            }
                            char tc = sliceCur.get();
                            switch (tc) {
                                case 'n' -> buffer[cntCur++] = '\n';
                                case 'b' -> buffer[cntCur++] = '\b';
                                case 'r' -> buffer[cntCur++] = '\r';
                                case 't' -> buffer[cntCur++] = '\t';
                                case 'f' -> buffer[cntCur++] = '\f';
                                case '\'', '"', '\\' -> buffer[cntCur++] = tc;
                                case 'u' -> {
                                    char temp = tc;
                                    while (temp == 'u') {
                                        if (sliceCur.isEmpty()) {
                                            fail = new IllegalSyntaxException("Illegal unicode escape.");
                                            return fail;
                                        }
                                        sliceCur.mark();
                                        temp = sliceCur.get();
                                    }
                                    if (temp == '+') {
                                        if (sliceCur.isEmpty()) {
                                            fail = new IllegalSyntaxException("Illegal unicode escape.");
                                            return fail;
                                        }
                                    } else sliceCur.reset();

                                    if (sliceCur.remaining() < 4) {
                                        fail = new IllegalSyntaxException("Illegal unicode escape.");
                                        return fail;
                                    }
                                    int unicodeValue = 0;
                                    for (int i = 1; i <= 4; ++i) {
                                        temp = sliceCur.get();
                                        unicodeValue *= 16;
                                        int digit = Character.digit(temp, 16);
                                        if (digit == -1) {
                                            fail = new IllegalSyntaxException("Illegal unicode escape.");
                                            return fail;
                                        }
                                        unicodeValue += digit;
                                    }

                                    buffer[cntCur++] = (char) unicodeValue;
                                }
                                case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                                    int octalValue = tc - '0';
                                    boolean flag = true;
                                    if (!sliceCur.isEmpty()) {
                                        sliceCur.mark();
                                        char temp = sliceCur.get();
                                        if ('0' <= temp && temp <= '7') {
                                            octalValue *= 8;
                                            octalValue += temp - '7';
                                        } else {
                                            sliceCur.reset();
                                            flag = false;
                                        }
                                    }
                                    flag &= tc <= '3';
                                    if (flag && !sliceCur.isEmpty()) {
                                        sliceCur.mark();
                                        char temp = sliceCur.get();
                                        if ('0' <= temp && temp <= '7') {
                                            octalValue *= 8;
                                            octalValue += temp - '7';
                                        } else sliceCur.reset();
                                    }

                                    buffer[cntCur++] = (char) octalValue;
                                }
                            }
                        }
                        case '\n' -> {
                            fail = new IllegalSyntaxException("Illegal new line in a string.");
                            return fail;
                        }
                        default -> buffer[cntCur++] = c;
                    }
                } else {
                    switch (c) {
                        case '{' -> {
                            while (cntCur > 0 && Character.isWhitespace(buffer[cntCur - 1])) --cntCur;
                            if (cntCur == 0) parts.add(ObjectBeginPart.INSTANCE);
                            else {
                                fail = new IllegalSyntaxException(
                                        "Unknown value " + new String(buffer, 0, cntCur) + " before a curly bracket.");
                                return fail;
                            }
                        }
                        case '}' -> {
                            while (cntCur > 0 && Character.isWhitespace(buffer[cntCur - 1])) --cntCur;
                            tryParseElement(buffer, cntCur);

                            JsonObject obj = new JsonObject();
                            while (true) {
                                if (parts.isEmpty()) {
                                    parts.add(new OpenObjectEndPart(obj));
                                    break;
                                }
                                JsonPart part = parts.get(parts.size() - 1);
                                if (part instanceof ObjectBeginPart) {
                                    parts.remove(parts.size() - 1);
                                    formMemberOrInsert(new JsonElementPart(obj));
                                    break;
                                } else if (part instanceof MemberPart memberPart) {
                                    parts.remove(parts.size() - 1);
                                    obj.put(memberPart.name, memberPart.value);
                                } else if (part instanceof OpenEndPart) {
                                    parts.add(new OpenObjectEndPart(obj));
                                    break;
                                } else {
                                    fail = new IllegalSyntaxException("Non-member part in JSON object.");
                                    return fail;
                                }
                            }

                            cntCur = 0;
                        }
                        case '"' -> {
                            while (cntCur > 0 && Character.isWhitespace(buffer[cntCur - 1])) --cntCur;
                            if (cntCur == 0) inStr = true;
                            else {
                                fail = new IllegalSyntaxException(
                                        "Unknown value " + new String(buffer, 0, cntCur) + " before a quote.");
                                return fail;
                            }
                        }
                        case '[' -> {
                            while (cntCur > 0 && Character.isWhitespace(buffer[cntCur - 1])) --cntCur;
                            if (cntCur == 0) parts.add(ArrayBeginPart.INSTANCE);
                            else {
                                fail = new IllegalSyntaxException(
                                        "Unknown value " + new String(buffer, 0, cntCur) + " before a square bracket.");
                                return fail;
                            }
                        }
                        case ']' -> {
                            while (cntCur > 0 && Character.isWhitespace(buffer[cntCur - 1])) --cntCur;
                            tryParseElement(buffer, cntCur);

                            JsonArray arr = new JsonArray();
                            while (true) {
                                if (parts.isEmpty()) {
                                    parts.add(new OpenArrayEndPart(arr));
                                    break;
                                }
                                JsonPart part = parts.get(parts.size() - 1);
                                if (part instanceof ArrayBeginPart) {
                                    parts.remove(parts.size() - 1);
                                    Collections.reverse(arr);
                                    formMemberOrInsert(new JsonElementPart(arr));
                                    break;
                                } else if (part instanceof JsonElementPart elementPart) {
                                    parts.remove(parts.size() - 1);
                                    arr.add(elementPart.element);
                                } else if (part instanceof OpenEndPart) {
                                    parts.add(new OpenArrayEndPart(arr));
                                    break;
                                } else {
                                    fail = new IllegalSyntaxException("Non-element part in JSON array.");
                                    return fail;
                                }
                            }

                            cntCur = 0;
                        }
                        case ',' -> {
                            while (cntCur > 0 && Character.isWhitespace(buffer[cntCur - 1])) --cntCur;
                            tryParseElement(buffer, cntCur);
                            cntCur = 0;
                        }
                        case ':' -> {
                            while (cntCur > 0 && Character.isWhitespace(buffer[cntCur - 1])) --cntCur;
                            if (cntCur == 0) {
                                if ((!parts.isEmpty()) &&
                                        parts.get(parts.size() - 1) instanceof JsonElementPart elementPart &&
                                        elementPart.element.isPrimitive() &&
                                        elementPart.element.asPrimitive().isString()) {
                                    parts.remove(parts.size() - 1);
                                    parts.add(new DeclareMemberPart(elementPart.element.asPrimitive().asString()));
                                } else {
                                    fail = new IllegalSyntaxException("Unexpected colon.");
                                    return fail;
                                }
                            } else {
                                fail = new IllegalSyntaxException(
                                        "Unknown value " + new String(buffer, 0, cntCur) + " before a colon.");
                                return fail;
                            }
                        }
                        default -> {
                            if (cntCur != 0 || !Character.isWhitespace(c)) buffer[cntCur++] = c;
                        }
                    }
                }
            }

            if (inStr) fail = new IllegalSyntaxException("Quotes don't match.");
            else {
                while (cntCur > 0 && Character.isWhitespace(buffer[cntCur - 1])) --cntCur;
                tryParseElement(buffer, cntCur);
            }

            return fail;
        }

        public static abstract class JsonPart {
        }

        public static class JsonElementPart extends JsonPart {
            public final JsonElement element;

            public JsonElementPart(JsonElement element) {
                this.element = element;
            }
        }

        public static class MemberPart extends JsonPart {
            public final String name;
            public final JsonElement value;

            public MemberPart(String name, JsonElement value) {
                this.name = name;
                this.value = value;
            }
        }

        public static class DeclareMemberPart extends JsonPart {
            public final String name;

            public DeclareMemberPart(String name) {
                this.name = name;
            }
        }

        public static abstract class OpenEndPart extends JsonPart {
        }

        public static class ObjectBeginPart extends JsonPart {
            public static final ObjectBeginPart INSTANCE = new ObjectBeginPart();

            private ObjectBeginPart() {}
        }

        public static class OpenObjectEndPart extends OpenEndPart {
            public final JsonObject object;

            public OpenObjectEndPart(JsonObject object) {
                this.object = object;
            }
        }

        public static class ArrayBeginPart extends JsonPart {
            public static final ArrayBeginPart INSTANCE = new ArrayBeginPart();

            private ArrayBeginPart() {}
        }

        public static class OpenArrayEndPart extends OpenEndPart {
            public final JsonArray array;

            public OpenArrayEndPart(JsonArray array) {
                this.array = array;
            }
        }
    }
}