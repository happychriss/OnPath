package com.google.gson.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.stream.MalformedJsonException;
import java.io.EOFException;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;

/**
 * Web stand-in for Gson's Streams (shadows the jar's class in the TeaVM build only, web/engine/build.gradle.kts):
 * the original reads the tree through TypeAdapters, whose static setup names classes the browser lacks
 * (InetAddress, Currency, …). Same results for the tree model the app uses (JsonParser.parseString in
 * search/OnlineSearch.kt): objects, arrays, strings, numbers (lazily parsed, like Gson), booleans, null.
 */
public final class Streams {
    private Streams() {}

    public static JsonElement parse(JsonReader reader) throws JsonParseException {
        boolean isEmpty = true;
        try {
            reader.peek();
            isEmpty = false;
            return read(reader);
        } catch (EOFException e) {
            if (isEmpty) return JsonNull.INSTANCE;
            throw new JsonSyntaxException(e);
        } catch (MalformedJsonException e) {
            throw new JsonSyntaxException(e);
        } catch (IOException e) {
            throw new JsonIOException(e);
        } catch (NumberFormatException e) {
            throw new JsonSyntaxException(e);
        }
    }

    private static JsonElement read(JsonReader in) throws IOException {
        JsonToken t = in.peek();
        switch (t) {
            case BEGIN_ARRAY: {
                JsonArray a = new JsonArray();
                in.beginArray();
                while (in.hasNext()) a.add(read(in));
                in.endArray();
                return a;
            }
            case BEGIN_OBJECT: {
                JsonObject o = new JsonObject();
                in.beginObject();
                while (in.hasNext()) o.add(in.nextName(), read(in));
                in.endObject();
                return o;
            }
            case STRING: return new JsonPrimitive(in.nextString());
            case NUMBER: return new JsonPrimitive(new LazilyParsedNumber(in.nextString()));
            case BOOLEAN: return new JsonPrimitive(in.nextBoolean());
            case NULL: in.nextNull(); return JsonNull.INSTANCE;
            default: throw new IllegalStateException("Unexpected token " + t);
        }
    }

    public static void write(JsonElement e, JsonWriter out) throws IOException {
        if (e == null || e.isJsonNull()) { out.nullValue(); return; }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) out.value(p.getAsNumber());
            else if (p.isBoolean()) out.value(p.getAsBoolean());
            else out.value(p.getAsString());
        } else if (e.isJsonArray()) {
            out.beginArray();
            for (JsonElement x : e.getAsJsonArray()) write(x, out);
            out.endArray();
        } else {
            out.beginObject();
            for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) { out.name(x.getKey()); write(x.getValue(), out); }
            out.endObject();
        }
    }

    public static Writer writerForAppendable(Appendable appendable) {
        if (appendable instanceof Writer) return (Writer) appendable;
        return new Writer() {
            @Override public void write(char[] c, int off, int len) throws IOException { appendable.append(new String(c, off, len)); }
            @Override public void write(String s, int off, int len) throws IOException { appendable.append(s, off, off + len); }
            @Override public void flush() {}
            @Override public void close() {}
        };
    }
}
