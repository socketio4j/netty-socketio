/**
 * Copyright (c) 2025 The Socketio4j Project
 * Parent project : Copyright (c) 2012-2025 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.socketio4j.socketio;


import com.socketio4j.socketio.handler.SuccessAuthorizationListener;
import com.socketio4j.socketio.listener.DefaultExceptionListener;
import com.socketio4j.socketio.listener.ExceptionListener;
import com.socketio4j.socketio.protocol.JsonSupport;
import com.socketio4j.socketio.store.StoreFactory;
import com.socketio4j.socketio.store.hazelcast.HazelcastStoreFactory;
import com.socketio4j.socketio.store.memory.MemoryStoreFactory;
import com.socketio4j.socketio.store.redis_pubsub.RedisStoreFactory;


import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.util.ResourceLeakDetector;

public class Configuration extends BasicConfiguration {

    private ExceptionListener exceptionListener = new DefaultExceptionListener();

    private SocketConfig socketConfig = new SocketConfig();

    private SocketSslConfig socketSslConfig = new SocketSslConfig();

    private StoreFactory storeFactory = new MemoryStoreFactory();

    private JsonSupport jsonSupport;

    private AuthorizationListener authorizationListener = new SuccessAuthorizationListener();

    private HttpRequestDecoderConfiguration httpRequestDecoderConfiguration = new HttpRequestDecoderConfiguration();

    private ResourceLeakDetector.Level leakDetectionLevel;

    public Configuration() {
        super();
    }

    public Configuration(BasicConfiguration basicConfiguration) {
        super(basicConfiguration);
    }

    /**
     * Defend from further modifications by cloning
     *
     * @param conf - Configuration object to clone
     */
    Configuration(Configuration conf) {
        super(conf);

        if (conf.getJsonSupport() == null) {
            try {
                getClass().getClassLoader().loadClass("com.fasterxml.jackson.databind.ObjectMapper");
                try {
                    Class<?> jjs = getClass().getClassLoader().loadClass("com.socketio4j.socketio.protocol.JacksonJsonSupport");
                    JsonSupport js = (JsonSupport) jjs.getConstructor().newInstance();
                    conf.setJsonSupport(js);
                } catch (Exception e) {
                    throw new IllegalArgumentException(e);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Can't find jackson lib in classpath", e);
            }
        }

        setJsonSupport(new JsonSupportWrapper(conf.getJsonSupport()));

        setSocketSslConfig(conf.getSocketSslConfig());
        setStoreFactory(conf.getStoreFactory());
        setAuthorizationListener(conf.getAuthorizationListener());
        setExceptionListener(conf.getExceptionListener());
        setSocketConfig(conf.getSocketConfig());

        setHttpRequestDecoderConfiguration(conf.getHttpRequestDecoderConfiguration());
        setLeakDetectionLevel(conf.getLeakDetectionLevel());
    }

    public JsonSupport getJsonSupport() {
        return jsonSupport;
    }

    /**
     * Allows to setup custom implementation of
     * JSON serialization/deserialization
     *
     * @param jsonSupport - json mapper
     *
     * @see JsonSupport
     */
    public void setJsonSupport(JsonSupport jsonSupport) {
        this.jsonSupport = jsonSupport;
    }


    /**
     * Data store - used to store session data and implements distributed pubsub.
     * Default is {@code MemoryStoreFactory}
     *
     * @param clientStoreFactory - implements StoreFactory
     *
     * @see MemoryStoreFactory
     * @see RedisStoreFactory
     * @see HazelcastStoreFactory
     */
    public void setStoreFactory(StoreFactory clientStoreFactory) {
        this.storeFactory = clientStoreFactory;
    }

    public StoreFactory getStoreFactory() {
        return storeFactory;
    }

    /**
     * Authorization listener invoked on every handshake.
     * Accepts or denies a client by {@code AuthorizationListener.getAuthorizationResult} method.
     * <b>Accepts</b> all clients by default.
     *
     * @param authorizationListener - authorization listener itself
     *
     * @see com.socketio4j.socketio.AuthorizationListener
     */
    public void setAuthorizationListener(AuthorizationListener authorizationListener) {
        this.authorizationListener = authorizationListener;
    }

    public AuthorizationListener getAuthorizationListener() {
        return authorizationListener;
    }

    /**
     * Exception listener invoked on any exception in
     * SocketIO listener
     *
     * @param exceptionListener - listener
     *
     * @see com.socketio4j.socketio.listener.ExceptionListener
     */
    public void setExceptionListener(ExceptionListener exceptionListener) {
        this.exceptionListener = exceptionListener;
    }

    public ExceptionListener getExceptionListener() {
        return exceptionListener;
    }

    public SocketConfig getSocketConfig() {
        return socketConfig;
    }

    /**
     * TCP socket configuration
     *
     * @param socketConfig - config
     */
    public void setSocketConfig(SocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    public HttpRequestDecoderConfiguration getHttpRequestDecoderConfiguration() {
        return httpRequestDecoderConfiguration;
    }

    public void setHttpRequestDecoderConfiguration(HttpRequestDecoderConfiguration httpRequestDecoderConfiguration) {
        this.httpRequestDecoderConfiguration = httpRequestDecoderConfiguration;
    }

    public HttpDecoderConfig getHttpDecoderConfig() {
        return new HttpDecoderConfig()
                .setMaxInitialLineLength(httpRequestDecoderConfiguration.getMaxInitialLineLength())
                .setMaxHeaderSize(httpRequestDecoderConfiguration.getMaxHeaderSize())
                .setMaxChunkSize(httpRequestDecoderConfiguration.getMaxChunkSize());
    }

    public SocketSslConfig getSocketSslConfig() {
        return socketSslConfig;
    }

    public void setSocketSslConfig(SocketSslConfig socketSslConfig) {
        this.socketSslConfig = socketSslConfig;
    }

    /**
     * Returns the Netty {@link ResourceLeakDetector.Level} configured for the server,
     * or {@code null} if relying on the JVM system property ({@code -Dio.netty.leakDetection.level})
     * or Netty's default ({@link ResourceLeakDetector.Level#SIMPLE}).
     *
     * @return configured leak detection level, or {@code null}
     */
    public ResourceLeakDetector.Level getLeakDetectionLevel() {
        return leakDetectionLevel;
    }

    /**
     * Configures the Netty {@link ResourceLeakDetector.Level}.
     * <p>
     * Available levels:
     * <ul>
     * <li>{@link ResourceLeakDetector.Level#DISABLED} - Disables leak detection. Lowest overhead.</li>
     * <li>{@link ResourceLeakDetector.Level#SIMPLE} - Samples ~1% of buffers, reports if leaks occur. Default at runtime.</li>
     * <li>{@link ResourceLeakDetector.Level#ADVANCED} - Samples ~1% of buffers and captures allocation/access stack traces.
     *     <b>Recommended for staging environments</b> to diagnose buffer leaks with minimal performance impact.</li>
     * <li>{@link ResourceLeakDetector.Level#PARANOID} - Samples 100% of buffers with full stack traces. High overhead;
     *     recommended only for targeted debugging or unit test suites.</li>
     * </ul>
     *
     * @param leakDetectionLevel desired leak detection level, or {@code null} to leave unchanged
     */
    public void setLeakDetectionLevel(ResourceLeakDetector.Level leakDetectionLevel) {
        this.leakDetectionLevel = leakDetectionLevel;
    }
}
