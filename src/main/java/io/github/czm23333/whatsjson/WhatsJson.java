package io.github.czm23333.whatsjson;

import io.github.czm23333.whatsjson.exception.IllegalSyntaxException;
import io.github.czm23333.whatsjson.json.*;
import org.apache.commons.text.StringEscapeUtils;

import java.nio.CharBuffer;
import java.util.*;
import java.util.concurrent.*;

public class WhatsJson {
    private final static int MIN_SLICE_SIZE = 500;

    private final static int threadCnt = Runtime.getRuntime().availableProcessors() * 2 + 1;
    private final ExecutorService threadPool = new ThreadPoolExecutor(threadCnt, threadCnt, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(threadCnt * 2), new ThreadPoolExecutor.CallerRunsPolicy());

    private static ArrayList<CharBuffer> checkAndSpilt(String json) {
        int sliceSize = Math.max(json.length() / threadCnt, MIN_SLICE_SIZE);
        ArrayList<CharBuffer> slices = new ArrayList<>();
        int cur = 0;

        ArrayDeque<Integer> stack = new ArrayDeque<>();
        boolean inStr = false;
        for (int i = 0, l = json.length(); i < l; ++i) {
            char c = json.charAt(i);
            switch (c) {
                case '{':
                    stack.push(1);
                    break;
                case '}':
                    if (stack.getFirst() == 1) stack.pop();
                    else throw new IllegalSyntaxException("Curly brackets don't match.");
                    break;
                case '[':
                    stack.push(2);
                    break;
                case ']':
                    if (stack.getFirst() == 2) stack.pop();
                    else throw new IllegalSyntaxException("Square brackets don't match.");
                    break;
                case '"':
                    inStr = !inStr;
                    break;
                case '\\':
                    if (inStr) ++i;
                    break;
                case ',':
                    if (i - cur + 1 >= sliceSize) {
                        slices.add(CharBuffer.wrap(json, cur, i + 1));
                        cur = i + 1;
                    }
                    break;
                default:
                    break;
            }
        }
        if (inStr) throw new IllegalSyntaxException("Quotes don't match.");
        if (cur < json.length()) slices.add(CharBuffer.wrap(json, cur, json.length()));

        return slices;
    }

    public JsonElement fromJson(String json) {
        ArrayList<CharBuffer> slices = checkAndSpilt(json);
        LinkedList<Parser> parsers = new LinkedList<>();
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

        ArrayList<Future<RuntimeException>> futures = new ArrayList<>();
        while (parsers.size() > 1) {
            futures.clear();
            for (ListIterator<Parser> iterator = parsers.listIterator(); iterator.hasNext(); ) {
                Parser cur = iterator.next();
                if (iterator.hasNext()) {
                    Parser parserToMerge = iterator.next();
                    futures.add(threadPool.submit(() -> cur.merge(parserToMerge)));
                    iterator.remove();
                }
            }
            futures.forEach(future -> {
                try {
                    RuntimeException tmp = future.get();
                    if (tmp != null) throw tmp;
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        ArrayList<Parser.JsonPart> parts = parsers.getFirst().parts;
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
        }
        else throw new IllegalArgumentException("Unknown element type.");
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
                        }
                        else {
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
                        }
                        else {
                            fail = new IllegalSyntaxException("Non-element part in JSON array.");
                            return fail;
                        }
                    }
                } else parts.add(otherPart);
            }

            other.parts.clear();

            return fail;
        }

        private void tryParseElement(String str) {
            if (str.isEmpty()) return;
            JsonElement element;
            if (str.equals("true")) element = JsonPrimitive.TRUE;
            else if (str.equals("false")) element = JsonPrimitive.FALSE;
            else if (str.equals("null")) element = JsonNull.INSTANCE;
            else {
                try {
                    element = new JsonPrimitive(Integer.parseInt(str));
                } catch (NumberFormatException e1) {
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
            StringBuilder elementTemp = new StringBuilder();
            CharBuffer sliceCur = slice.slice();
            while (!sliceCur.isEmpty()) {
                char c = sliceCur.get();
                if (inStr) {
                    switch (c) {
                        case '"' -> {
                            inStr = false;
                            formMemberOrInsert(new JsonElementPart(
                                    new JsonPrimitive(StringEscapeUtils.unescapeJson(elementTemp.toString()))));
                            elementTemp = new StringBuilder();
                        }
                        case '\\' -> {
                            elementTemp.append(c);
                            if (sliceCur.isEmpty()) {
                                fail = new IllegalSyntaxException("Missing escape char.");
                                return fail;
                            }
                            elementTemp.append(sliceCur.get());
                        }
                        case '\n' -> {
                            fail = new IllegalSyntaxException("Illegal new line in a string.");
                            return fail;
                        }
                        default -> elementTemp.append(c);
                    }
                } else {
                    switch (c) {
                        case '{' -> {
                            String between = elementTemp.toString().strip();
                            if (between.isEmpty()) parts.add(new ObjectBeginPart());
                            else {
                                fail = new IllegalSyntaxException(
                                        "Unknown value " + between + " before a curly bracket.");
                                return fail;
                            }

                            elementTemp = new StringBuilder();
                        }
                        case '}' -> {
                            tryParseElement(elementTemp.toString().strip());

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
                                }
                                else {
                                    fail = new IllegalSyntaxException("Non-member part in JSON object.");
                                    return fail;
                                }
                            }

                            elementTemp = new StringBuilder();
                        }
                        case '"' -> {
                            String between = elementTemp.toString().strip();
                            if (between.isEmpty()) inStr = true;
                            else {
                                fail = new IllegalSyntaxException("Unknown value " + between + " before a quote.");
                                return fail;
                            }

                            elementTemp = new StringBuilder();
                        }
                        case '[' -> {
                            String between = elementTemp.toString().strip();
                            if (between.isEmpty()) parts.add(new ArrayBeginPart());
                            else {
                                fail = new IllegalSyntaxException(
                                        "Unknown value " + between + " before a square bracket.");
                                return fail;
                            }

                            elementTemp = new StringBuilder();
                        }
                        case ']' -> {
                            tryParseElement(elementTemp.toString().strip());

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
                                }
                                else {
                                    fail = new IllegalSyntaxException("Non-element part in JSON array.");
                                    return fail;
                                }
                            }

                            elementTemp = new StringBuilder();
                        }
                        case ',' -> {
                            tryParseElement(elementTemp.toString().strip());
                            elementTemp = new StringBuilder();
                        }
                        case ':' -> {
                            String between = elementTemp.toString().strip();
                            if (between.isEmpty()) {
                                if ((!parts.isEmpty()) && parts.get(parts.size() - 1) instanceof JsonElementPart elementPart && elementPart.element.isPrimitive() && elementPart.element.asPrimitive().isString()) {
                                    parts.remove(parts.size() - 1);
                                    parts.add(new DeclareMemberPart(elementPart.element.asPrimitive().asString()));
                                } else {
                                    fail = new IllegalSyntaxException("Unexpected colon.");
                                    return fail;
                                }
                            }
                            else {
                                fail = new IllegalSyntaxException("Unknown value " + between + " before a colon.");
                                return fail;
                            }

                            elementTemp = new StringBuilder();
                        }
                        default -> elementTemp.append(c);
                    }
                }
            }

            if (inStr) fail = new IllegalSyntaxException("Quotes don't match.");
            else tryParseElement(elementTemp.toString().strip());

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
        }

        public static class OpenObjectEndPart extends OpenEndPart {
            public final JsonObject object;

            public OpenObjectEndPart(JsonObject object) {
                this.object = object;
            }
        }

        public static class ArrayBeginPart extends JsonPart {
        }

        public static class OpenArrayEndPart extends OpenEndPart {
            public final JsonArray array;

            public OpenArrayEndPart(JsonArray array) {
                this.array = array;
            }
        }
    }
}