/*
 * Copyright 2013-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.logstash.logback.composite;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.ServiceConfigurationError;

import net.logstash.logback.decorate.JsonFactoryDecorator;
import net.logstash.logback.decorate.JsonGeneratorDecorator;
import net.logstash.logback.decorate.NullJsonFactoryDecorator;
import net.logstash.logback.decorate.NullJsonGeneratorDecorator;
import net.logstash.logback.util.ProxyOutputStream;
import net.logstash.logback.util.SimpleObjectJsonGeneratorDelegate;
import net.logstash.logback.util.ThreadLocalHolder;

import ch.qos.logback.access.spi.IAccessEvent;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.spi.ContextAware;
import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.DeferredProcessingAware;
import ch.qos.logback.core.spi.LifeCycle;
import ch.qos.logback.core.util.CloseUtil;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactory.Feature;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Formats logstash Events as JSON using {@link JsonProvider}s.
 *
 * <p>The {@link AbstractCompositeJsonFormatter} starts the JSON object ('{'),
 * then delegates writing the contents of the object to the {@link JsonProvider}s,
 * and then ends the JSON object ('}').
 *
 * <p>Jackson {@link JsonGenerator} are initially created with a "disconnected" output stream so they can be
 * reused multiple times with different target output stream.
 * 
 * <p>{@link JsonGenerator} instances are *not* reused after they threw an exception. This is to prevent
 * reusing an instance whose internal state may be unpredictable.
 * 
 * @param <Event> type of event ({@link ILoggingEvent} or {@link IAccessEvent}).
 * 
 * @author brenuart
 */
public abstract class AbstractCompositeJsonFormatter<Event extends DeferredProcessingAware>
        extends ContextAwareBase implements LifeCycle {

    /**
     * Used to create the necessary {@link JsonGenerator}s for generating JSON.
     */
    private JsonFactory jsonFactory;

    /**
     * Decorates the {@link #jsonFactory}.
     * Allows customization of the {@link #jsonFactory}.
     */
    private JsonFactoryDecorator jsonFactoryDecorator;

    /**
     * Decorates the generators generated by the {@link #jsonFactory}.
     * Allows customization of the generators.
     */
    private JsonGeneratorDecorator jsonGeneratorDecorator;

    /**
     * The providers that are used to populate the output JSON object.
     */
    private JsonProviders<Event> jsonProviders = new JsonProviders<>();

    private JsonEncoding encoding = JsonEncoding.UTF8;

    private boolean findAndRegisterJacksonModules = true;

    private volatile boolean started;

    private ThreadLocalHolder<JsonFormatter> threadLocalJsonFormatter;


    public AbstractCompositeJsonFormatter(ContextAware declaredOrigin) {
        super(declaredOrigin);
    }

    @Override
    public void start() {
        if (isStarted()) {
            return;
        }
        if (jsonFactoryDecorator == null) {
            jsonFactoryDecorator = new NullJsonFactoryDecorator();
        }
        if (jsonGeneratorDecorator == null) {
            jsonGeneratorDecorator = new NullJsonGeneratorDecorator();
        }
        if (jsonProviders.getProviders().isEmpty()) {
            addError("No providers configured");
        }
        jsonFactory = createJsonFactory();
        jsonProviders.setContext(context);
        jsonProviders.setJsonFactory(jsonFactory);
        jsonProviders.start();
        
        threadLocalJsonFormatter = new ThreadLocalHolder<>(this::createJsonFormatter);
        started = true;
    }

    @Override
    public void stop() {
        if (isStarted()) {
            threadLocalJsonFormatter.close();
            jsonProviders.stop();
            jsonFactory = null;
            started = false;
        }
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    
    /**
     * Write an event in the given output stream.
     * 
     * @param event the event to write
     * @param outputStream the output stream to write the event into
     * @throws IOException thrown upon failure to write the event
     */
    public void writeEvent(Event event, OutputStream outputStream) throws IOException {
        Objects.requireNonNull(outputStream);
        if (!isStarted()) {
            throw new IllegalStateException("Formatter is not started");
        }
        
        try (JsonFormatter formatter = this.threadLocalJsonFormatter.acquire()) {
            formatter.writeEvent(outputStream, event);
        }
    }
    
    
    /**
     * Create a reusable {@link JsonFormatter} bound to a {@link DisconnectedOutputStream}.
     * 
     * @return {@link JsonFormatter} writing JSON content in the output stream
     */
    private JsonFormatter createJsonFormatter() {
        try {
            DisconnectedOutputStream outputStream = new DisconnectedOutputStream();
            JsonGenerator generator = createGenerator(outputStream);
            return new JsonFormatter(outputStream, generator);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize Jackson JSON layer", e);
        }
        
    }
    
    private class JsonFormatter implements ThreadLocalHolder.Lifecycle, Closeable {
        private final JsonGenerator generator;
        private final DisconnectedOutputStream stream;
        private boolean recyclable = true;
        
        JsonFormatter(DisconnectedOutputStream outputStream, JsonGenerator generator) {
            this.stream = Objects.requireNonNull(outputStream);
            this.generator = Objects.requireNonNull(generator);
        }
        
        public void writeEvent(OutputStream outputStream, Event event) throws IOException {
            try {
                this.stream.connect(outputStream);
                writeEventToGenerator(generator, event);
                
            } catch (IOException | RuntimeException e) {
                this.recyclable = false;
                throw e;
                
            } finally {
                this.stream.disconnect();
            }
        }
        
        @Override
        public boolean recycle() {
            return this.recyclable;
        }
        
        @Override
        public void dispose() {
            CloseUtil.closeQuietly(this.generator);
            
            // Note:
            //   The stream is disconnected at this point.
            //   Closing the JsonGenerator may throw additional exception if it is flagged as not recyclable,
            //   meaning it already threw a exception earlier during the writeEvent() method. The generator
            //   is disposed here and won't be reused anymore - we can safely ignore these new exceptions
            //   here.
        }
        
        @Override
        public void close() throws IOException {
            AbstractCompositeJsonFormatter.this.threadLocalJsonFormatter.release();
        }
    }
    
    private static class DisconnectedOutputStream extends ProxyOutputStream {
        DisconnectedOutputStream() {
            super(null);
        }
        
        public void connect(OutputStream out) {
            this.delegate = out;
        }
        
        public void disconnect() {
            this.delegate = null;
        }
    }
    
    private JsonFactory createJsonFactory() {
        ObjectMapper objectMapper = new ObjectMapper()
                /*
                 * Assume empty beans are ok.
                 */
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        if (findAndRegisterJacksonModules) {
            try {
                objectMapper.findAndRegisterModules();
            } catch (ServiceConfigurationError serviceConfigurationError) {
                addError("Error occurred while dynamically loading jackson modules", serviceConfigurationError);
            }
        }

        return decorateFactory(objectMapper.getFactory());
    }

    private JsonFactory decorateFactory(JsonFactory factory) {
        JsonFactory factoryToDecorate = factory
                /*
                 * When generators are flushed, don't flush the underlying outputStream.
                 *
                 * This allows some streaming optimizations when using an encoder.
                 *
                 * The encoder generally determines when the stream should be flushed
                 * by an 'immediateFlush' property.
                 *
                 * The 'immediateFlush' property of the encoder can be set to false
                 * when the appender performs the flushes at appropriate times
                 * (such as the end of a batch in the AbstractLogstashTcpSocketAppender).
                 *
                 * Set this prior to decorating, because some generators require
                 * FLUSH_PASSED_TO_STREAM to work properly (e.g. YAML)
                 */
                .disable(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM);

        return this.jsonFactoryDecorator.decorate(factoryToDecorate)
                /*
                 * Jackson buffer recycling works by maintaining a pool of buffers per thread. This
                 * feature works best when one JsonGenerator is created per thread, typically in J2EE
                 * environments.
                 * 
                 * Each JsonFormatter uses its own instance of JsonGenerator and is reused multiple times
                 * possibly on different threads. The memory buffers allocated by the JsonGenerator do
                 * not belong to a particular thread - hence the recycling feature should be disabled.
                 */
                .disable(Feature.USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING);
    }
    
    protected void writeEventToGenerator(JsonGenerator generator, Event event) throws IOException {
        generator.writeStartObject();
        jsonProviders.writeTo(generator, event);
        generator.writeEndObject();
        generator.flush();
    }

    protected void prepareForDeferredProcessing(Event event) {
        event.prepareForDeferredProcessing();
        jsonProviders.prepareForDeferredProcessing(event);
    }

    private JsonGenerator createGenerator(OutputStream outputStream) throws IOException {
        return decorateGenerator(jsonFactory.createGenerator(outputStream, encoding));
    }

    private JsonGenerator decorateGenerator(JsonGenerator generator) {
        JsonGenerator decorated = jsonGeneratorDecorator.decorate(generator)
                /*
                 * Don't let the json generator close the underlying outputStream and let the
                 * encoder managed it.
                 */
                .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

        try {
            decorated = decorated
                /*
                 * JsonGenerator are reused to serialize multiple log events.
                 * Change the default root value separator to an empty string instead of a single space.
                 */
                .setRootValueSeparator(new SerializedString(CoreConstants.EMPTY_STRING));
        } catch (UnsupportedOperationException e) {
            /*
             * Ignore.
             * Some generators do not support setting the rootValueSeparator.
             */
        }

        return new SimpleObjectJsonGeneratorDelegate(decorated);
    }
    
    public JsonFactory getJsonFactory() {
        return jsonFactory;
    }

    public JsonFactoryDecorator getJsonFactoryDecorator() {
        return jsonFactoryDecorator;
    }

    public void setJsonFactoryDecorator(JsonFactoryDecorator jsonFactoryDecorator) {
        this.jsonFactoryDecorator = jsonFactoryDecorator;
    }

    public JsonGeneratorDecorator getJsonGeneratorDecorator() {
        return jsonGeneratorDecorator;
    }

    public void setJsonGeneratorDecorator(JsonGeneratorDecorator jsonGeneratorDecorator) {
        this.jsonGeneratorDecorator = jsonGeneratorDecorator;
    }

    public JsonProviders<Event> getProviders() {
        return jsonProviders;
    }

    public String getEncoding() {
        return encoding.getJavaName();
    }

    public void setEncoding(String encodingName) {
        for (JsonEncoding encoding: JsonEncoding.values()) {
            if (encoding.getJavaName().equalsIgnoreCase(encodingName) || encoding.name().equalsIgnoreCase(encodingName)) {
                this.encoding = encoding;
                return;
            }
        }
        throw new IllegalArgumentException("Unknown encoding " + encodingName);
    }

    public void setProviders(JsonProviders<Event> jsonProviders) {
        this.jsonProviders = Objects.requireNonNull(jsonProviders);
    }

    public boolean isFindAndRegisterJacksonModules() {
        return findAndRegisterJacksonModules;
    }

    public void setFindAndRegisterJacksonModules(boolean findAndRegisterJacksonModules) {
        this.findAndRegisterJacksonModules = findAndRegisterJacksonModules;
    }
}
