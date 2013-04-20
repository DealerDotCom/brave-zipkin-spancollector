package com.github.kristofa.brave.zipkin;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.Validate;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TIOStreamTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.Some;
import scala.collection.immutable.$colon$colon;
import scala.collection.immutable.List;
import scala.collection.immutable.List$;

import com.github.kristofa.brave.ClientTracer;
import com.github.kristofa.brave.EndPoint;
import com.github.kristofa.brave.ServerTracer;
import com.github.kristofa.brave.SpanCollector;
import com.twitter.finagle.Service;
import com.twitter.finagle.builder.ClientBuilder;
import com.twitter.finagle.stats.NullStatsReceiver;
import com.twitter.finagle.thrift.ThriftClientFramedCodec;
import com.twitter.finagle.thrift.ThriftClientRequest;
import com.twitter.util.Base64StringEncoder$;
import com.twitter.util.Future;
import com.twitter.zipkin.gen.Annotation;
import com.twitter.zipkin.gen.Endpoint;
import com.twitter.zipkin.gen.LogEntry;
import com.twitter.zipkin.gen.ResultCode;
import com.twitter.zipkin.gen.Span;
import com.twitter.zipkin.gen.ZipkinCollector;

/**
 * Converts brave spans to zipkin spans and sends them to Zipkin collector. Credits for the code that submits spans to Zipkin
 * goes to to Adam Clarricoates because the code used here is based on code from him that can be found <a
 * href="https://github.com/adam-clarricoates/zipkin/tree/master/zipkin-test/src/main/java/com/twitter/zipkin/javaapi"
 * >here</a>.
 * <p>
 * Typically the {@link ZipkinSpanCollector} should be a singleton in your application that can be used by both
 * {@link ClientTracer} as {@link ServerTracer}.
 * 
 * @author kristof
 */
public class ZipkinSpanCollector implements SpanCollector {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZipkinSpanCollector.class);

    private static final int CONNECTION_LIMIT = 1;
    private static final scala.Option<Object> NONE_DURATION = scala.Option.apply(null);

    private final ZipkinCollector.FinagledClient client;
    private final TProtocolFactory protocol;
    private final Map<EndPoint, Endpoint> endPointCache = new ConcurrentHashMap<EndPoint, Endpoint>();

    /**
     * Create a new instance.
     * 
     * @param zipkinCollectorHost Host for zipkin collector.
     * @param zipkinCollectorPort Port for zipkin collector.
     */
    public ZipkinSpanCollector(final String zipkinCollectorHost, final int zipkinCollectorPort) {
        Validate.notEmpty(zipkinCollectorHost);

        protocol = new TBinaryProtocol.Factory();
        final Service<ThriftClientRequest, byte[]> service =
            ClientBuilder.safeBuild(ClientBuilder.get().hosts(zipkinCollectorHost + ":" + zipkinCollectorPort)
                .hostConnectionLimit(CONNECTION_LIMIT).codec(ThriftClientFramedCodec.get()));

        final String serviceName = "";
        client = new ZipkinCollector.FinagledClient(service, protocol, serviceName, new NullStatsReceiver());

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void collect(final com.github.kristofa.brave.Span span) {

        final long startTime = System.currentTimeMillis();
        try {
            final Annotation[] annotations = new Annotation[span.getAnnotations().size()];
            int index = 0;
            for (final com.github.kristofa.brave.Annotation braveAnnotation : span.getAnnotations()) {
                final EndPoint braveEndPoint = braveAnnotation.getEndPoint();
                Endpoint zipkinEndPoint = endPointCache.get(braveEndPoint);

                if (zipkinEndPoint == null) {
                    zipkinEndPoint =
                        createEndpoint(braveEndPoint.getIpv4(), (short)braveEndPoint.getPort(),
                            braveEndPoint.getServiceName());
                    endPointCache.put(braveEndPoint, zipkinEndPoint);
                }

                // timestamp * 1000 is important as brave stores time in milliseconds. zipkin expects microseconds.
                final long zipkinTimestamp = braveAnnotation.getTimeStamp() * 1000;

                if (braveAnnotation.getDuration() == null) {
                    // Client send, Client received, Server send and Server received annotations have same
                    // name in brave as in zipkin. Otherwise we would have to use the zipkin Constants.ClientSend(), ...
                    annotations[index] =
                        createAnnotation(zipkinTimestamp, braveAnnotation.getAnnotationName(), zipkinEndPoint);
                } else {
                    // Annotation with duration.
                    annotations[index] =
                        createAnnotation(zipkinTimestamp, braveAnnotation.getAnnotationName(), zipkinEndPoint,
                            braveAnnotation.getDuration());
                }
                index++;
            }

            final Span zipkinSpan =
                createSpan(span.getSpanId().getTraceId(), span.getName(), span.getSpanId().getSpanId(), span.getSpanId()
                    .getParentSpanId(), list(annotations), list(), true);

            final Future<ResultCode> logSpanResult = logSpan(zipkinSpan);
            final ResultCode resultCode = logSpanResult.get();
            if (resultCode.getValue() != 0) {
                LOGGER.error("Persisting span failed. ResultCode: " + resultCode);
            }
        } finally {
            final long endTime = System.currentTimeMillis();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Converting and persisting span takes (ms): " + (endTime - startTime));
            }
        }

    }

    private Future<ResultCode> logSpan(final Span span) {
        final LogEntry logEntry = new LogEntry.Immutable("zipkin", encodeSpan((Span.Immutable)span));
        List<LogEntry> entries = List$.MODULE$.empty();
        entries = new $colon$colon(logEntry, entries);
        return client.log(entries);
    }

    private String encodeSpan(final Span.Immutable thriftSpan) {
        return Base64StringEncoder$.MODULE$.encode(spanToBytes(thriftSpan));
    }

    private byte[] spanToBytes(final Span.Immutable thriftSpan) {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final TProtocol proto = protocol.getProtocol(new TIOStreamTransport(buf));
        thriftSpan.write(proto);
        return buf.toByteArray();
    }

    private static Endpoint createEndpoint(final int ipv4, final short port, final String serviceName) {
        return new Endpoint.Immutable(ipv4, port, serviceName);
    }

    private static Annotation createAnnotation(final long timestamp, final String value, final Endpoint endpoint) {
        return new Annotation.Immutable(timestamp, value, new Some<Endpoint>(endpoint), NONE_DURATION);
    }

    private static Annotation createAnnotation(final long timestamp, final String value, final Endpoint endpoint,
        final Integer duration) {
        return new Annotation.Immutable(timestamp, value, new Some<Endpoint>(endpoint), new Some<Object>(duration));
    }

    private static Span createSpan(final long traceId, final String name, final long id, final Object parentId,
        final List annotations, final List binaryAnnotations, final boolean debug) {
        return new Span.Immutable(traceId, name, id, scala.Option.apply(parentId), annotations, binaryAnnotations, debug);
    }

    private static <T> List<T> list(final T... ts) {
        List<T> result = List$.MODULE$.empty();
        for (int i = ts.length; i > 0; i--) {
            result = new $colon$colon(ts[i - 1], result);
        }
        return result;
    }
}
