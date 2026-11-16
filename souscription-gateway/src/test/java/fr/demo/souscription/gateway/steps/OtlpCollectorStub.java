package fr.demo.souscription.gateway.steps;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import fr.demo.souscription.gateway.client.DownstreamWireMocks;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Local OTLP collector of the Cucumber gate (W6-T04).
 *
 * <p>The packaged gateway runs with the OpenTelemetry SDK on and exports over OTLP
 * {@code http/protobuf} to this WireMock double, which answers every export with 200 and keeps the
 * request bodies. {@link #spans()} decodes them with a minimal protobuf reader (only the span
 * fields the gate asserts on), so the gate needs neither a real collector nor a protobuf runtime.
 */
final class OtlpCollectorStub {

    /** OTLP {@code SpanKind}. */
    static final int KIND_SERVER = 2;

    static final int KIND_CLIENT = 3;

    /** OTLP {@code Status.StatusCode}. */
    static final int STATUS_ERROR = 2;

    private static WireMockServer server;

    private OtlpCollectorStub() {
    }

    /** One exported span, reduced to the fields the gate asserts on. */
    record ExportedSpan(String traceId, String spanId, String parentSpanId, String name, int kind,
                        int statusCode) {
    }

    /** Starts the collector once per JVM and returns its base URI. */
    static synchronized String start() {
        if (server == null) {
            server = new WireMockServer(WireMockConfiguration.options()
                    .bindAddress(DownstreamWireMocks.LOOPBACK).dynamicPort());
            server.start();
            server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/x-protobuf")));
        }
        return "http://" + DownstreamWireMocks.LOOPBACK + ":" + server.port();
    }

    static synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    /** Every span received so far. */
    static synchronized List<ExportedSpan> spans() {
        List<ExportedSpan> spans = new ArrayList<>();
        if (server == null) {
            return spans;
        }
        for (ServeEvent event : server.getAllServeEvents()) {
            byte[] body = event.getRequest().getBody();
            if (body != null) {
                // ExportTraceServiceRequest.resource_spans(1) > ScopeSpans(2) > Span(2)
                for (ByteBuffer resource : fields(ByteBuffer.wrap(body), 1)) {
                    for (ByteBuffer scope : fields(resource, 2)) {
                        for (ByteBuffer span : fields(scope, 2)) {
                            spans.add(decodeSpan(span));
                        }
                    }
                }
            }
        }
        return spans;
    }

    private static ExportedSpan decodeSpan(ByteBuffer span) {
        String traceId = "";
        String spanId = "";
        String parentSpanId = "";
        String name = "";
        int kind = 0;
        int statusCode = 0;
        ByteBuffer in = span.duplicate();
        while (in.hasRemaining()) {
            long key = varint(in);
            int field = (int) (key >>> 3);
            int wireType = (int) (key & 7);
            if (wireType == 2) {
                ByteBuffer value = slice(in);
                switch (field) {
                    case 1 -> traceId = hex(value);
                    case 2 -> spanId = hex(value);
                    case 4 -> parentSpanId = hex(value);
                    case 5 -> name = utf8(value);
                    case 15 -> statusCode = statusCode(value);
                    default -> { }
                }
            } else if (wireType == 0) {
                long value = varint(in);
                if (field == 6) {
                    kind = (int) value;
                }
            } else {
                skip(in, wireType);
            }
        }
        return new ExportedSpan(traceId, spanId, parentSpanId, name, kind, statusCode);
    }

    /** {@code Status.code} is field 3. */
    private static int statusCode(ByteBuffer status) {
        ByteBuffer in = status.duplicate();
        while (in.hasRemaining()) {
            long key = varint(in);
            int wireType = (int) (key & 7);
            if (wireType == 0) {
                long value = varint(in);
                if ((key >>> 3) == 3) {
                    return (int) value;
                }
            } else if (wireType == 2) {
                slice(in);
            } else {
                skip(in, wireType);
            }
        }
        return 0;
    }

    /** All length-delimited occurrences of one field of a message. */
    private static List<ByteBuffer> fields(ByteBuffer message, int wanted) {
        List<ByteBuffer> found = new ArrayList<>();
        ByteBuffer in = message.duplicate();
        while (in.hasRemaining()) {
            long key = varint(in);
            int wireType = (int) (key & 7);
            if (wireType == 2) {
                ByteBuffer value = slice(in);
                if ((key >>> 3) == wanted) {
                    found.add(value);
                }
            } else if (wireType == 0) {
                varint(in);
            } else {
                skip(in, wireType);
            }
        }
        return found;
    }

    private static ByteBuffer slice(ByteBuffer in) {
        int length = (int) varint(in);
        ByteBuffer value = in.slice(in.position(), length);
        in.position(in.position() + length);
        return value;
    }

    private static void skip(ByteBuffer in, int wireType) {
        switch (wireType) {
            case 1 -> in.position(in.position() + 8);
            case 5 -> in.position(in.position() + 4);
            default -> throw new IllegalStateException("unsupported protobuf wire type " + wireType);
        }
    }

    private static long varint(ByteBuffer in) {
        long value = 0;
        int shift = 0;
        byte b;
        do {
            b = in.get();
            value |= (long) (b & 0x7f) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }

    private static String hex(ByteBuffer value) {
        byte[] bytes = new byte[value.remaining()];
        value.duplicate().get(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String utf8(ByteBuffer value) {
        byte[] bytes = new byte[value.remaining()];
        value.duplicate().get(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
