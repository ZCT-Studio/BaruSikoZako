// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.zstd;

import io.github.zct_studio.barusikozako.backend.Consistency;
import io.github.zct_studio.barusikozako.backend.Entry;
import io.github.zct_studio.barusikozako.backend.Repository;
import io.github.zct_studio.barusikozako.backend.Snapshot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

final class Manifest {
    static final long FORMAT = 1L;
    static final long CHUNK_FORMAT = 2L;
    private static final String FIXED_CHUNKS = "sha256-fixed-65536";
    private static final String TRIGGER_METADATA = "trigger-metadata";
    private static final String CAPTURE_CONSISTENCY = "capture-consistency";
    private static final int LINE_LIMIT = 64 * 1024;
    private static final JsonFactory JSON = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private Manifest() {}

    static void header(OutputStream out, Snapshot snapshot) throws IOException {
        header(out, snapshot, FORMAT);
    }

    static void header(OutputStream out, Snapshot snapshot, long format) throws IOException {
        writeLine(out, json -> {
            json.writeStringProperty("backend", "zstd");
            json.writeNumberProperty("format", format);
            json.writeStringProperty("id", snapshot.id().toString());
            json.writeStringProperty("createdAt", snapshot.createdAt().toString());
            json.writeStringProperty("source", snapshot.source());
            json.writeStringProperty("consistency", snapshot.consistency().name().toLowerCase(java.util.Locale.ROOT));
            json.writeName("requiredFeatures");
            json.writeStartArray();
            if (format == CHUNK_FORMAT) json.writeString(FIXED_CHUNKS);
            if (snapshot.trigger().isPresent()) json.writeString(TRIGGER_METADATA);
            json.writeString(CAPTURE_CONSISTENCY);
            json.writeEndArray();
            if (snapshot.trigger().isPresent()) writeTrigger(json, snapshot.trigger().orElseThrow());
        });
    }

    private static void writeTrigger(JsonGenerator json, Snapshot.Trigger trigger) {
        json.writeStringProperty("triggerSource", trigger.source());
        json.writeStringProperty("triggerReason", trigger.reason());
        json.writeName("triggerMetadata");
        json.writeStartObject();
        new java.util.TreeMap<>(trigger.metadata()).forEach(json::writeStringProperty);
        json.writeEndObject();
    }

    static Snapshot readHeader(InputStream in, UUID id) throws IOException {
        return readHeader(in, id, FORMAT);
    }

    static Snapshot readHeader(InputStream in, UUID id, long format) throws IOException {
        var fields = readLine(in);
        if (fields == null || !"zstd".equals(fields.remove("backend")) ||
            !Long.valueOf(format).equals(fields.remove("format")) || !id.toString().equals(fields.remove("id")))
            throw new IOException("unsupported manifest header");
        var features = fields.remove("requiredFeatures");
        if (!(features instanceof List<?> declared) || declared.size() != new java.util.HashSet<>(declared).size() ||
            !Set.of(FIXED_CHUNKS, TRIGGER_METADATA, CAPTURE_CONSISTENCY).containsAll(declared))
            throw new IOException("unsupported manifest feature");
        boolean fixed = declared.contains(FIXED_CHUNKS);
        boolean tagged = declared.contains(TRIGGER_METADATA);
        boolean consistency = declared.contains(CAPTURE_CONSISTENCY);
        if (fixed != (format == CHUNK_FORMAT)) throw new IOException("unsupported manifest feature");
        try {
            Optional<Snapshot.Trigger> trigger = tagged
                ? Optional.of(readTrigger(fields)) : Optional.empty();
            var mode = consistency ? Consistency.parse(string(fields.remove("consistency"))) : Consistency.STRICT;
            var snapshot = new Snapshot(id, Instant.parse(string(fields.remove("createdAt"))),
                string(fields.remove("source")), mode, trigger);
            if (!fields.isEmpty()) throw new IOException("unknown manifest header fields");
            return snapshot;
        } catch (RuntimeException e) {
            throw new IOException("invalid snapshot metadata", e);
        }
    }

    private static Snapshot.Trigger readTrigger(Map<String, Object> fields) throws IOException {
        var metadata = fields.remove("triggerMetadata");
        if (!(metadata instanceof Map<?, ?> object)) throw new IOException("invalid trigger metadata");
        var values = new HashMap<String, String>();
        for (var entry : object.entrySet()) values.put(string(entry.getKey()), string(entry.getValue()));
        return new Snapshot.Trigger(string(fields.remove("triggerSource")), string(fields.remove("triggerReason")), values);
    }

    static void item(OutputStream out, Entry entry, String digest) throws IOException {
        writeLine(out, json -> {
            json.writeStringProperty("type", entry.directory() ? "directory" : "file");
            json.writeName("path");
            json.writeStartArray();
            for (var segment : entry.path()) json.writeString(segment);
            json.writeEndArray();
            if (!entry.directory()) {
                json.writeNumberProperty("size", entry.size());
                json.writeStringProperty("sha256", digest);
            }
            if (entry.modifiedAt() != null) json.writeStringProperty("modifiedAt", entry.modifiedAt().toString());
        });
    }

    static Repository.Item readItem(InputStream in) throws IOException {
        var fields = readLine(in);
        if (fields == null) return null;
        var type = string(fields.remove("type"));
        boolean directory = type.equals("directory");
        if (!directory && !type.equals("file")) throw new IOException("unknown manifest item type");
        if (!(fields.remove("path") instanceof List<?> path)) throw new IOException("invalid manifest path");
        var names = new ArrayList<String>(path.size());
        for (var segment : path) names.add(string(segment));
        long size = 0;
        String digest = null;
        if (!directory) {
            if (!(fields.remove("size") instanceof Long length) || length < 0)
                throw new IOException("invalid manifest file length");
            size = length;
            digest = string(fields.remove("sha256"));
            if (!digest.matches("[0-9a-f]{64}")) throw new IOException("invalid manifest digest");
        }
        var date = fields.remove("modifiedAt");
        if (!fields.isEmpty()) throw new IOException("unknown manifest item fields");
        try {
            var modifiedAt = date == null ? null : Instant.parse(string(date));
            return new Repository.Item(new Entry(names, directory, size, modifiedAt), digest);
        } catch (RuntimeException e) {
            throw new IOException("invalid manifest entry", e);
        }
    }

    private static String string(Object value) throws IOException {
        if (value instanceof String s) return s;
        throw new IOException("expected manifest string");
    }

    private static void writeLine(OutputStream out, Fields fields) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var json = JSON.createGenerator(ObjectWriteContext.empty(), bytes)) {
            json.writeStartObject();
            fields.write(json);
            json.writeEndObject();
        } catch (JacksonException e) {
            throw new IOException("cannot encode manifest", e);
        }
        if (bytes.size() > LINE_LIMIT) throw new IOException("manifest line exceeds its byte limit");
        bytes.writeTo(out);
        out.write('\n');
    }

    private static Map<String, Object> readLine(InputStream in) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int next; (next = in.read()) != '\n'; ) {
            if (next == -1) {
                if (bytes.size() == 0) return null;
                throw new IOException("unterminated manifest line");
            }
            if (bytes.size() == LINE_LIMIT || next == '\r') throw new IOException("invalid manifest line boundary");
            bytes.write(next);
        }
        var line = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        if (line.isEmpty() || line.charAt(0) == '\ufeff') throw new IOException("invalid manifest line");
        try (var json = JSON.createParser(ObjectReadContext.empty(), line)) {
            if (json.nextToken() != JsonToken.START_OBJECT) throw new IOException("expected manifest object");
            var fields = new HashMap<String, Object>();
            while (json.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = json.currentName();
                var valueToken = json.nextToken();
                if (valueToken == null) throw new IOException("unterminated manifest value");
                var value = switch (valueToken) {
                    case VALUE_STRING -> json.getString();
                    case VALUE_NUMBER_INT -> {
                        if (json.getString().startsWith("-")) throw new IOException("negative manifest integer");
                        yield json.getLongValue();
                    }
                    case START_ARRAY -> {
                        var list = new ArrayList<String>();
                        JsonToken token;
                        while ((token = json.nextToken()) == JsonToken.VALUE_STRING) list.add(json.getString());
                        if (token != JsonToken.END_ARRAY) throw new IOException("invalid manifest array");
                        yield list;
                    }
                    case START_OBJECT -> {
                        if (!name.equals("triggerMetadata")) throw new IOException("invalid manifest object field");
                        var metadata = new HashMap<String, String>();
                        while (json.nextToken() == JsonToken.PROPERTY_NAME) {
                            var key = json.currentName();
                            if (metadata.size() == 16 || json.nextToken() != JsonToken.VALUE_STRING)
                                throw new IOException("invalid trigger metadata entry");
                            metadata.put(key, json.getString());
                        }
                        if (json.currentToken() != JsonToken.END_OBJECT) throw new IOException("invalid trigger metadata");
                        yield metadata;
                    }
                    default -> throw new IOException("unsupported manifest value");
                };
                fields.put(name, value);
            }
            if (json.currentToken() != JsonToken.END_OBJECT || json.nextToken() != null)
                throw new IOException("invalid manifest object boundary");
            return fields;
        } catch (JacksonException e) {
            throw new IOException("cannot decode manifest", e);
        }
    }

    @FunctionalInterface
    private interface Fields {
        void write(JsonGenerator json);
    }
}
