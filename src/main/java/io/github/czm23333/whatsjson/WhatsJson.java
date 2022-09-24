package io.github.czm23333.whatsjson;

import io.github.czm23333.whatsjson.exception.IllegalSyntaxException;
import io.github.czm23333.whatsjson.json.*;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.commons.text.StringEscapeUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class WhatsJson {
    private final static int MIN_SLICE_SIZE = 5000;

    private final static int threadCnt = Runtime.getRuntime().availableProcessors() * 2;
    private final ExecutorService threadPool = new ThreadPoolExecutor(threadCnt, threadCnt, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(threadCnt * 2), new ThreadPoolExecutor.CallerRunsPolicy());

    private static Triple<Boolean, Integer, Boolean> scan(ByteBuffer bytes, boolean inStr) {
        int lastComma = -1;
        while (bytes.hasRemaining()) {
            byte c = bytes.get();
            if (inStr) switch (c) {
                case '"' -> inStr = false;
                case '\\' -> {
                    if (!bytes.hasRemaining()) return Triple.of(inStr, lastComma, true);
                    bytes.get();
                }
            }
            else switch (c) {
                case '"' -> inStr = true;
                case ',' -> lastComma = bytes.position() - 1;
            }
        }
        return Triple.of(inStr, lastComma, false);
    }

    private List<ByteBuffer> checkAndSpilt(byte[] json) {
        int sliceSize = Math.max(json.length / threadCnt, MIN_SLICE_SIZE);
        int sliceCnt = json.length / sliceSize + (json.length % sliceSize == 0 ? 0 : 1);
        if (sliceCnt == 1) return Collections.singletonList(ByteBuffer.wrap(json));

        Future<Triple<Boolean, Integer, Boolean>>[] futures = new Future[1 + (sliceCnt - 2) * 2];
        {
            ByteBuffer temp = ByteBuffer.wrap(json, 0, sliceSize);
            futures[0] = threadPool.submit(() -> scan(temp, false));
        }
        for (int i = 2; i < sliceCnt; ++i) {
            ByteBuffer temp1 = ByteBuffer.wrap(json, sliceSize * (i - 1), sliceSize);
            ByteBuffer temp2 = temp1.duplicate();
            futures[(i - 1) * 2 - 1] = threadPool.submit(() -> scan(temp1, true));
            futures[(i - 1) * 2] = threadPool.submit(() -> scan(temp2, false));
        }

        ArrayList<ByteBuffer> slices = new ArrayList<>();
        boolean inStr = false;
        int cur = 0;
        try {
            Triple<Boolean, Integer, Boolean> temp = futures[0].get();
            inStr = temp.getLeft();
            if (temp.getMiddle() != -1) {
                slices.add(ByteBuffer.wrap(json, cur, temp.getMiddle() - cur + 1));
                cur = temp.getMiddle() + 1;
            }
            for (int i = 2; i < sliceCnt; ++i) {
                if (temp.getRight() && json[sliceSize * (sliceCnt - 1)] == '"') inStr = !inStr;
                temp = (inStr ? futures[(i - 1) * 2 - 1] : futures[(i - 1) * 2]).get();
                inStr = temp.getLeft();
                if (temp.getMiddle() != -1) {
                    slices.add(ByteBuffer.wrap(json, cur, temp.getMiddle() - cur + 1));
                    cur = temp.getMiddle() + 1;
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }

        if (cur < json.length) slices.add(ByteBuffer.wrap(json, cur, json.length - cur));

        return slices;
    }

    public JsonElement fromJson(byte[] json) {
        List<ByteBuffer> slices = checkAndSpilt(json);
        Parser.JsonPart[] partBuffer = new Parser.JsonPart[json.length];
        byte[] byteBuffer = new byte[json.length];
        Parser[] parsers = new Parser[slices.size()];
        {
            int i = 0;
            for (ByteBuffer slice : slices) {
                parsers[i] = new Parser(slice, partBuffer, byteBuffer, slice.position());
                ++i;
            }
        }
        Future<RuntimeException>[] futures = new Future[parsers.length];
        for (int i = 0; i < parsers.length; ++i) futures[i] = threadPool.submit(parsers[i]);
        for (Future<RuntimeException> future : futures) {
            try {
                RuntimeException tmp = future.get();
                if (tmp != null) throw tmp;
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        Parser finalParser = null;
        for (Parser parser : parsers) {
            if (finalParser == null) finalParser = parser;
            else {
                RuntimeException temp = finalParser.merge(parser);
                if (temp != null) throw temp;
            }
        }

        if (Objects.requireNonNull(finalParser).partCnt != 1 ||
                !(finalParser.parts[0] instanceof Parser.JsonElementPart elementPart))
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
        public final JsonPart[] parts;
        private final int offset;
        private final ByteBuffer slice;
        public RuntimeException fail = null;
        private final byte[] buffer;
        public int partCnt = 0;
        private boolean inStr = false;
        private int byteCnt = 0;

        public Parser(ByteBuffer slice, JsonPart[] partsBuffer, byte[] byteBuffer, int offset) {
            this.slice = slice;
            this.parts = partsBuffer;
            this.buffer = byteBuffer;
            this.offset = offset;
        }

        private void formMemberOrInsert(JsonElementPart part) {
            if (partCnt > 0 && parts[offset + partCnt - 1] instanceof DeclareMemberPart declareMemberPart)
                parts[offset + partCnt - 1] = new MemberPart(declareMemberPart.name, part.element);
            else parts[offset + partCnt++] = part;
        }

        public RuntimeException merge(Parser other) {
            fail = null;

            for (int i = 0; i < other.partCnt; ++i) {
                JsonPart otherPart = other.parts[other.offset + i];
                if (otherPart instanceof OpenObjectEndPart objectEndPart) {
                    if (endObject(objectEndPart.object) != null) return fail;
                } else if (otherPart instanceof OpenArrayEndPart arrayEndPart) {
                    if (endArray(arrayEndPart.array) != null) return fail;
                } else parts[offset + partCnt++] = otherPart;
            }

            other.partCnt = 0;

            return fail;
        }

        private void tryParseElement() {
            if (byteCnt == 0) return;
            JsonElement element = null;
            if (byteCnt == 4 && buffer[offset] == 't' && buffer[offset + 1] == 'r' && buffer[offset + 2] == 'u' &&
                    buffer[offset + 3] == 'e') element = JsonPrimitive.TRUE;
            else if (byteCnt == 5 && buffer[offset] == 'f' && buffer[offset + 1] == 'a' && buffer[offset + 2] == 'l' &&
                    buffer[offset + 3] == 's' && buffer[offset + 4] == 'e') element = JsonPrimitive.FALSE;
            else if (byteCnt == 4 && buffer[offset] == 'n' && buffer[offset + 1] == 'u' && buffer[offset + 2] == 'l' &&
                    buffer[offset + 3] == 'l') element = JsonNull.INSTANCE;
            else {
                if ((buffer[offset] == '+' || buffer[offset] == '-') && byteCnt > 1) {
                    boolean flag = true;
                    long value = 0;
                    for (int i = 1; i < byteCnt; ++i) {
                        if ('0' <= buffer[offset + i] && buffer[offset + i] <= '9') {
                            value *= 10;
                            value += buffer[offset + i] - '0';
                        } else {
                            flag = false;
                            break;
                        }
                    }
                    if (flag) {
                        if (buffer[offset] == '-') value = -value;
                        element = new JsonPrimitive(value);
                    }
                } else if ('0' <= buffer[offset] && buffer[offset] <= '9') {
                    boolean flag = true;
                    long value = 0;
                    for (int i = 0; i < byteCnt; ++i) {
                        if ('0' <= buffer[offset + i] && buffer[offset + i] <= '9') {
                            value *= 10;
                            value += buffer[offset + i] - '0';
                        } else {
                            flag = false;
                            break;
                        }
                    }
                    if (flag) element = new JsonPrimitive(value);
                }
                if (element == null) {
                    String str = new String(buffer, offset, byteCnt, StandardCharsets.UTF_8);
                    try {
                        element = new JsonPrimitive(Double.parseDouble(str));
                    } catch (NumberFormatException e2) {
                        throw new IllegalSyntaxException("Unknown value " + str + '.');
                    }
                }
            }

            formMemberOrInsert(new JsonElementPart(element));
        }

        private RuntimeException parseEscape() {
            if (!slice.hasRemaining()) {
                fail = new IllegalSyntaxException("Missing escape char.");
                return fail;
            }
            byte tc = slice.get();
            switch (tc) {
                case 'n' -> buffer[offset + byteCnt++] = '\n';
                case 'b' -> buffer[offset + byteCnt++] = '\b';
                case 'r' -> buffer[offset + byteCnt++] = '\r';
                case 't' -> buffer[offset + byteCnt++] = '\t';
                case 'f' -> buffer[offset + byteCnt++] = '\f';
                case '\'', '"', '\\' -> buffer[offset + byteCnt++] = tc;
                case 'u' -> {
                    byte temp = tc;
                    while (temp == 'u') {
                        if (!slice.hasRemaining()) {
                            fail = new IllegalSyntaxException("Illegal unicode escape.");
                            return fail;
                        }
                        slice.mark();
                        temp = slice.get();
                    }
                    if (temp == '+') {
                        if (!slice.hasRemaining()) {
                            fail = new IllegalSyntaxException("Illegal unicode escape.");
                            return fail;
                        }
                    } else slice.reset();

                    if (slice.remaining() < 4) {
                        fail = new IllegalSyntaxException("Illegal unicode escape.");
                        return fail;
                    }
                    int unicodeValue = 0;
                    for (int i = 1; i <= 4; ++i) {
                        temp = slice.get();
                        unicodeValue *= 16;
                        int digit = Character.digit(temp, 16);
                        if (digit == -1) {
                            fail = new IllegalSyntaxException("Illegal unicode escape.");
                            return fail;
                        }
                        unicodeValue += digit;
                    }

                    ByteBuffer encoded = StandardCharsets.UTF_8.encode(String.valueOf((char) unicodeValue));
                    int cntEncoded = encoded.remaining();
                    encoded.get(buffer, offset + byteCnt, encoded.remaining());
                    byteCnt += cntEncoded;
                }
                case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                    int octalValue = tc - '0';
                    boolean flag = true;
                    if (slice.hasRemaining()) {
                        slice.mark();
                        byte temp = slice.get();
                        if ('0' <= temp && temp <= '7') {
                            octalValue *= 8;
                            octalValue += temp - '7';
                        } else {
                            slice.reset();
                            flag = false;
                        }
                    }
                    flag &= tc <= '3';
                    if (flag && slice.hasRemaining()) {
                        slice.mark();
                        byte temp = slice.get();
                        if ('0' <= temp && temp <= '7') {
                            octalValue *= 8;
                            octalValue += temp - '7';
                        } else slice.reset();
                    }

                    ByteBuffer encoded = StandardCharsets.UTF_8.encode(String.valueOf((char) octalValue));
                    int cntEncoded = encoded.remaining();
                    encoded.get(buffer, offset + byteCnt, encoded.remaining());
                    byteCnt += cntEncoded;
                }
            }
            return null;
        }

        private RuntimeException endObject(JsonObject obj) {
            while (true) {
                if (partCnt == 0) {
                    parts[offset + partCnt++] = new OpenObjectEndPart(obj);
                    break;
                }
                JsonPart part = parts[offset + partCnt - 1];
                if (part instanceof ObjectBeginPart) {
                    --partCnt;
                    formMemberOrInsert(new JsonElementPart(obj));
                    break;
                } else if (part instanceof MemberPart memberPart) {
                    --partCnt;
                    obj.put(memberPart.name, memberPart.value);
                } else if (part instanceof OpenEndPart) {
                    parts[offset + partCnt++] = new OpenObjectEndPart(obj);
                    break;
                } else {
                    fail = new IllegalSyntaxException("Non-member part in JSON object.");
                    return fail;
                }
            }
            return null;
        }

        private RuntimeException endArray(JsonArray arr) {
            while (true) {
                if (partCnt == 0) {
                    parts[offset + partCnt++] = new OpenArrayEndPart(arr);
                    break;
                }
                JsonPart part = parts[offset + partCnt - 1];
                if (part instanceof ArrayBeginPart) {
                    --partCnt;
                    Collections.reverse(arr);
                    formMemberOrInsert(new JsonElementPart(arr));
                    break;
                } else if (part instanceof JsonElementPart elementPart) {
                    --partCnt;
                    arr.add(elementPart.element);
                } else if (part instanceof OpenEndPart) {
                    parts[offset + partCnt++] = new OpenArrayEndPart(arr);
                    break;
                } else {
                    fail = new IllegalSyntaxException("Non-element part in JSON array.");
                    return fail;
                }
            }
            return null;
        }

        @Override
        public RuntimeException call() {
            while (slice.hasRemaining()) {
                byte c = slice.get();
                if (inStr) {
                    switch (c) {
                        case '"' -> {
                            inStr = false;
                            formMemberOrInsert(new JsonElementPart(
                                    new JsonPrimitive(new String(buffer, offset, byteCnt, StandardCharsets.UTF_8))));
                            byteCnt = 0;
                        }
                        case '\\' -> {
                            if (parseEscape() != null) return fail;
                        }
                        case '\n' -> {
                            fail = new IllegalSyntaxException("Illegal new line in a string.");
                            return fail;
                        }
                        default -> buffer[offset + byteCnt++] = c;
                    }
                } else {
                    switch (c) {
                        case '{' -> {
                            while (byteCnt > 0 && Character.isWhitespace(buffer[offset + byteCnt - 1])) --byteCnt;
                            if (byteCnt == 0) parts[offset + partCnt++] = ObjectBeginPart.INSTANCE;
                            else {
                                fail = new IllegalSyntaxException(
                                        "Unknown value " + new String(buffer, offset, byteCnt) +
                                                " before a curly bracket.");
                                return fail;
                            }
                        }
                        case '}' -> {
                            while (byteCnt > 0 && Character.isWhitespace(buffer[offset + byteCnt - 1])) --byteCnt;
                            tryParseElement();

                            if (endObject(new JsonObject()) != null) return fail;
                            else byteCnt = 0;
                        }
                        case '"' -> {
                            while (byteCnt > 0 && Character.isWhitespace(buffer[offset + byteCnt - 1])) --byteCnt;
                            if (byteCnt == 0) inStr = true;
                            else {
                                fail = new IllegalSyntaxException(
                                        "Unknown value " + new String(buffer, offset, byteCnt) + " before a quote.");
                                return fail;
                            }
                        }
                        case '[' -> {
                            while (byteCnt > 0 && Character.isWhitespace(buffer[offset + byteCnt - 1])) --byteCnt;
                            if (byteCnt == 0) parts[offset + partCnt++] = ArrayBeginPart.INSTANCE;
                            else {
                                fail = new IllegalSyntaxException(
                                        "Unknown value " + new String(buffer, offset, byteCnt) +
                                                " before a square bracket.");
                                return fail;
                            }
                        }
                        case ']' -> {
                            while (byteCnt > 0 && Character.isWhitespace(buffer[offset + byteCnt - 1])) --byteCnt;
                            tryParseElement();

                            if (endArray(new JsonArray()) != null) return fail;
                            else byteCnt = 0;
                        }
                        case ',' -> {
                            while (byteCnt > 0 && Character.isWhitespace(buffer[offset + byteCnt - 1])) --byteCnt;
                            tryParseElement();
                            byteCnt = 0;
                        }
                        case ':' -> {
                            while (byteCnt > 0 && Character.isWhitespace(buffer[offset + byteCnt - 1])) --byteCnt;
                            if (byteCnt == 0) {
                                if (partCnt > 0 && parts[offset + partCnt - 1] instanceof JsonElementPart elementPart &&
                                        elementPart.element.isPrimitive() &&
                                        elementPart.element.asPrimitive().isString())
                                    parts[offset + partCnt - 1] = new DeclareMemberPart(
                                            elementPart.element.asPrimitive().asString());
                                else {
                                    fail = new IllegalSyntaxException("Unexpected colon.");
                                    return fail;
                                }
                            } else {
                                fail = new IllegalSyntaxException(
                                        "Unknown value " + new String(buffer, offset, byteCnt) + " before a colon.");
                                return fail;
                            }
                        }
                        default -> {
                            if (byteCnt != 0 || !Character.isWhitespace(c)) buffer[offset + byteCnt++] = c;
                        }
                    }
                }
            }

            if (inStr) fail = new IllegalSyntaxException("Quotes don't match.");
            else {
                while (byteCnt > 0 && Character.isWhitespace(buffer[offset + byteCnt - 1])) --byteCnt;
                tryParseElement();
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
