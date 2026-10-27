package ru.pukpukov.pwrap.core;

import module java.base;
import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.*;
import org.checkerframework.checker.tainting.qual.*;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.dataflow.qual.AssertMethod;

/** Gson binding, with strict scalar/schema adapters instead of its implicit coercions. */
public final class Json {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Input {}
    
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
    public @interface AllowNull {}
    
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Extensible {}
    
    private static final Gson CODEC = new GsonBuilder()
        .serializeNulls().disableHtmlEscaping().disableJdkUnsafe()
        .registerTypeAdapter(String.class, scalar(JsonToken.STRING, v -> {
            String s = v.getAsString();
            require(s.indexOf(0) < 0, "NUL in string");
            return s;
        }, JsonPrimitive::new).nullSafe())
        .registerTypeAdapter(Boolean.class, scalar(JsonToken.BOOLEAN, JsonElement::getAsBoolean, JsonPrimitive::new))
        .registerTypeAdapter(boolean.class, scalar(JsonToken.BOOLEAN, JsonElement::getAsBoolean, JsonPrimitive::new))
        .registerTypeAdapter(Long.class, scalar(JsonToken.NUMBER, v -> integral(v).longValueExact(), JsonPrimitive::new))
        .registerTypeAdapter(long.class, scalar(JsonToken.NUMBER, v -> integral(v).longValueExact(), JsonPrimitive::new))
        .registerTypeAdapter(Integer.class, scalar(JsonToken.NUMBER, v -> integral(v).intValueExact(), JsonPrimitive::new))
        .registerTypeAdapter(int.class, scalar(JsonToken.NUMBER, v -> integral(v).intValueExact(), JsonPrimitive::new))
        .registerTypeAdapter(Endpoint.Port.class,
        scalar(JsonToken.NUMBER, v -> new Endpoint.Port(integral(v).intValueExact()), p -> new JsonPrimitive(p.value())))
        .registerTypeAdapter(Endpoint.Address.class,
        scalar(JsonToken.STRING, v -> new Endpoint.Address(v.getAsString()), a -> new JsonPrimitive(a.literal())))
        .registerTypeAdapterFactory(new Schema()).create();
    
    private static BigDecimal integral(JsonElement v) {
        require(v.getAsString().matches("-?(0|[1-9][0-9]*)"), "Expected integer");
        return v.getAsBigDecimal();
    }
    
    static <T> TypeAdapter<T> scalar(JsonToken token, Function<JsonElement, T> read, Function<T, JsonPrimitive> write) {
        return new TypeAdapter<>() {
            public T read(JsonReader input) throws IOException {
                require(input.peek() == token, "Expected JSON " + token + " at " + input.getPath());
                return read.apply(JsonParser.parseReader(input));
            }
            public void write(JsonWriter output, T value) throws IOException {
                JsonPrimitive primitive = write.apply(value);
                switch (token) {
                    case STRING -> output.value(primitive.getAsString());
                    case BOOLEAN -> output.value(primitive.getAsBoolean());
                    case NUMBER -> output.value(primitive.getAsNumber());
                    default -> throw new IllegalStateException("Not a scalar token");
                }
            }
        };
    }
    
    /** Unknown fields and unknown enum values must not silently lose permissions. */
    private static final class Schema implements TypeAdapterFactory {
        
        public <T> @Nullable TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            Class<?> raw = type.getRawType();
            if (Set.of(Endpoint.Port.class, Endpoint.Address.class).contains(raw) || raw.isAnnotationPresent(JsonAdapter.class)) return null;
            boolean collection = Collection.class.isAssignableFrom(raw);
            boolean map = Map.class.isAssignableFrom(raw);
            boolean domain = raw.getPackageName().startsWith("ru.pukpukov.pwrap");
            if (!domain && !collection && !map) return null;
            TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
            Set<String> fields = new HashSet<>(), nullable = new HashSet<>(), required = new HashSet<>();
            for (var field : raw.getDeclaredFields()) if (domain && !Modifier.isStatic(field.getModifiers())) {
                SerializedName name = field.getAnnotation(SerializedName.class);
                String key = name == null ? field.getName() : name.value();
                fields.add(key);
                if (field.isAnnotationPresent(AllowNull.class)) nullable.add(key);
                if (raw.isRecord() && !field.getAnnotatedType().isAnnotationPresent(Nullable.class)) required.add(key);
            }
            return new TypeAdapter<T>() {
                public T read(JsonReader input) throws IOException {
                    JsonElement tree = JsonParser.parseReader(input);
                    if (collection) require(tree.isJsonArray(), "Expected JSON array");
                    else if (map) require(tree.isJsonObject(), "Expected JSON object");
                    else if (raw.isEnum()) require(tree.isJsonPrimitive() && tree.getAsJsonPrimitive().isString(), "Expected enum string");
                    else {
                        require(tree.isJsonObject(), "Expected " + raw.getSimpleName() + " object");
                        require(
                            raw.isAnnotationPresent(Extensible.class) || fields.containsAll(tree.getAsJsonObject().keySet()),
                        "Unknown fields for " + raw.getSimpleName() + ": " + tree.getAsJsonObject().keySet());
                    }
                    for (String key : required)
                        require(
                            tree.getAsJsonObject().has(key) && !tree.getAsJsonObject().get(key).isJsonNull(),
                        "Missing non-null field " + raw.getSimpleName() + "." + key);
                    if (raw.isAnnotationPresent(Input.class)) tree.getAsJsonObject().entrySet().forEach(e -> require(
                    !e.getValue().isJsonNull() || nullable.contains(e.getKey()), "Null is not allowed for " + e.getKey()));
                    T result = delegate.fromJsonTree(tree);
                    require(result != null, "Invalid " + raw.getSimpleName() + ": " + tree);
                    if (result instanceof Collection<?> items) require(items.stream().allMatch(v -> v != null), "Null collection member");
                    if (result instanceof Map<?, ?> entries) require(entries.values().stream().allMatch(v -> v != null), "Null map value");
                    if (raw.isEnum()) require(tree.equals(delegate.toJsonTree(result)), "Noncanonical " + raw.getSimpleName() + ": " + tree);
                    return result;
                }
                public void write(JsonWriter output, T value) throws IOException { delegate.write(output, value); }
            }.nullSafe();
        }
    }
    
    public static JsonObject read(Path path) throws IOException { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
    public static <T> @Tainted T read(Path path, Class<T> type) throws IOException { return decode(JsonParser.parseString(Files.readString(path)), type); }
    public static <T> @Tainted T decode(JsonElement value, Class<T> type) { return decode(value, TypeToken.get(type)); }
    
    public static <T> @Tainted T decode(JsonElement value, TypeToken<T> type) {
        try {
            return required(CODEC.fromJson(value, type), "Expected non-null " + type);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid " + type + ": " + error.getMessage(), error);
        }
    }
    
    public static JsonElement encode(@Nullable Object value) { return CODEC.toJsonTree(value); }
    
    public static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            new TreeMap<>(value.getAsJsonObject().asMap()).forEach((k, v) -> sorted.add(k, canonical(v)));
            return sorted;
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            value.getAsJsonArray().forEach(v -> out.add(canonical(v)));
            return out;
        }
        return value;
    }
    
    @AssertMethod(IllegalArgumentException.class)
    public static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    public static <T> @NonNull T required(@Nullable T value, String message) { require(value != null, message); return value; }
    
}