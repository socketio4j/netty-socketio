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
package com.socketio4j.socketio.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socketio4j.socketio.Configuration;
import com.socketio4j.socketio.SocketConfig;
import com.socketio4j.socketio.SocketIOClient;
import com.socketio4j.socketio.SocketIOServer;
import com.socketio4j.socketio.Transport;
import com.socketio4j.socketio.listener.ExceptionListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.channel.ChannelHandlerContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;




public class HttpTransportTest {

  private static final String TEST_ORIGIN = "http://localhost:3000";

  private SocketIOServer server;

  private final ObjectMapper mapper = new ObjectMapper();

  private final Pattern responseJsonMatcher = Pattern.compile("([0-9]+)(\\{.*})?");

  private final Pattern multiResponsePattern = Pattern.compile("((?<type>[0-9])(?<id>[0-9]*)(?<body>.+)\\x{1E})*(?<lasttype>[0-9])(?<lastid>[0-9]*)(?<lastbody>.+)");

  private final String packetSeparator = new String(new byte[] { 0x1e });

  private final Logger logger = LoggerFactory.getLogger(HttpTransportTest.class);

  @BeforeEach
  public void createTestServer() {
    final int port = findFreePort();
    final Configuration config = new Configuration();
    config.setRandomSession(true);
    config.setTransports(Transport.POLLING);
    config.setPort(port);
    config.setOrigin(TEST_ORIGIN);
    config.setExceptionListener(new ExceptionListener() {
      @Override
      public void onEventException(Exception e, List<Object> args, SocketIOClient client) {
        logger.error("eventException", e);
      }

      @Override
      public void onDisconnectException(Exception e, SocketIOClient client) {
        logger.error("disconnectException", e);
      }

      @Override
      public void onConnectException(Exception e, SocketIOClient client) {
        logger.error("connectException", e);
      }

      @Override
      public void onPingException(Exception e, SocketIOClient client) {
        logger.error("pingException", e);
      }

      @Override
      public void onPongException(Exception e, SocketIOClient client) {
        logger.error("pongException", e);
      }

      @Override
      public boolean exceptionCaught(ChannelHandlerContext ctx, Throwable e) throws Exception {
        return false;
      }

      @Override
      public void onAuthException(Throwable e, SocketIOClient client) {
        logger.error("authException", e);
      }
    });

    final SocketConfig socketConfig = new SocketConfig();
    socketConfig.setReuseAddress(true);
    config.setSocketConfig(socketConfig);

    this.server = new SocketIOServer(config);
    this.server.start();
  }

  @AfterEach
  public void cleanupTestServer() {
    this.server.stop();
  }

  /**
   * Creates a test server URI with the specified query parameters.
   * This method demonstrates how query parameters are passed to the Socket.IO server.
   * The query string will be parsed by netty-socketio and stored in HandshakeData.urlParams
   * for structured access during the handshake process.
   * @param query the query string (e.g., "EIO=4&transport=polling&t=Oqd9eWh")
   * @return URI with the specified query parameters
   * @throws URISyntaxException if the URI is malformed
   */
  private URI createTestServerUri(final String query) throws URISyntaxException {
    return new URI("http", null, "localhost",  server.getConfiguration().getPort(), server.getConfiguration().getContext() + "/",
        query, null);
  }

  /**
   * Makes a Socket.IO HTTP request to the test server.
   * This method demonstrates the complete handshake process including:
   * - Engine.IO version specification (EIO=4)
   * - Transport type specification (transport=polling)
   * - Session ID handling (sid parameter)
   * - Query parameter parsing by netty-socketio
   * The query parameters in the request URI will be parsed and stored in HandshakeData.urlParams,
   * providing structured access to authentication tokens, user IDs, and other metadata.
   * 
   * @param sessionId the session ID for existing connections, or null for new connections
   * @param bodyForPost the POST body for sending data, or null for GET requests
   * @return the server response as a string
   * @throws URISyntaxException if the URI is malformed
   * @throws IOException if the HTTP request fails
   * @throws InterruptedException if the request is interrupted
   */
  private String makeSocketIoRequest(final String sessionId, final String bodyForPost)
      throws URISyntaxException, IOException, InterruptedException {
    final URI uri;
      if (sessionId == null) uri = createTestServerUri("EIO=4&transport=polling&t=Oqd9eWh");
      else uri = createTestServerUri("EIO=4&transport=polling&t=Oqd9eWh" + "&sid=" + sessionId);

      URLConnection con = uri.toURL().openConnection();
      HttpURLConnection http = getHttpURLConnection(bodyForPost, (HttpURLConnection) con);

      try (BufferedReader reader = new BufferedReader(new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
      return reader.lines().collect(Collectors.joining("\n"));
    }
  }

    private static @NotNull HttpURLConnection getHttpURLConnection(String bodyForPost, HttpURLConnection http) throws IOException {
        if (bodyForPost != null) {
          http.setRequestMethod("POST"); // PUT is another valid option
          http.setDoOutput(true);
          byte[] out = bodyForPost.getBytes(StandardCharsets.UTF_8);
          http.setFixedLengthStreamingMode(out.length);
          http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
          http.connect();
          try (OutputStream os = http.getOutputStream()) {
            os.write(out);
          }
        } else {
          http.connect();
        }
        return http;
    }

    private void postMessage(final String sessionId, final String body)
      throws URISyntaxException, IOException, InterruptedException {
    final String responseStr = makeSocketIoRequest(sessionId, body);
    assertEquals(responseStr, "ok");
  }

  private String[] pollForListOfResponses(final String sessionId)
      throws URISyntaxException, IOException, InterruptedException {
    final String responseStr = makeSocketIoRequest(sessionId, null);
    return responseStr.split(packetSeparator);
  }

  private String connectForSessionId(final String sessionId)
      throws URISyntaxException, IOException, InterruptedException {
    final String firstMessage = pollForListOfResponses(sessionId)[0];
    final Matcher jsonMatcher = responseJsonMatcher.matcher(firstMessage);
    assertTrue(jsonMatcher.find());
    assertEquals("0", jsonMatcher.group(1));
    final JsonNode node = mapper.readTree(jsonMatcher.group(2));
    return node.get("sid").asText();
  }

  @Test
  public void testConnect() throws URISyntaxException, IOException, InterruptedException {
    final String sessionId = connectForSessionId(null);
    assertNotNull(sessionId);
  }

  @Test
  public void testMultipleMessages() throws URISyntaxException, IOException, InterruptedException {
    server.addEventListener("hello", String.class, (client, data, ackSender) ->
        ackSender.sendAckData(data));
    final String sessionId = connectForSessionId(null);
    // Socket.IO v3/v4 wire protocol v5 requires an explicit CONNECT before events are accepted.
    postMessage(sessionId, "40");
    assertTrue(pollForListOfResponses(sessionId)[0].startsWith("40"));
    final ArrayList<String> events = new ArrayList<>();
    events.add("420[\"hello\", \"world\"]");
    events.add("421[\"hello\", \"socketio\"]");
    events.add("422[\"hello\", \"socketio\"]");
    postMessage(sessionId, String.join(packetSeparator, events));
    final String[] responses = pollForListOfResponses(sessionId);
    assertEquals(3, responses.length);
  }

  @Test
  public void testV4EventBeforeConnectIsNotDeliveredAndClosesSession()
      throws URISyntaxException, IOException, InterruptedException {
    final AtomicInteger namespaceConnections = new AtomicInteger();
    final AtomicInteger deliveredEvents = new AtomicInteger();
    server.addConnectListener(client -> namespaceConnections.incrementAndGet());
    server.addEventListener("hello", String.class,
        (client, data, ackSender) -> deliveredEvents.incrementAndGet());

    final String sessionId = connectForSessionId(null);

    // Socket.IO v3/v4 wire protocol v5 requires a namespace CONNECT ("40") before an EVENT.
    postMessage(sessionId, "42[\"hello\",\"must-not-be-delivered\"]");

    assertEquals(0, namespaceConnections.get(),
        "An EIO4 handshake alone must not connect the default namespace");
    assertEquals(0, deliveredEvents.get(),
        "Events sent before the namespace CONNECT packet must not reach application listeners");
    assertTrue(server.getAllClients().isEmpty(),
        "The unconnected session must not be visible as a default-namespace client");

    HttpURLConnection subsequentPoll = (HttpURLConnection) createTestServerUri(
        "EIO=4&transport=polling&sid=" + sessionId).toURL().openConnection();
    subsequentPoll.setReadTimeout(2_000);
    try {
      int responseCode = subsequentPoll.getResponseCode();
      if (responseCode == 200) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(subsequentPoll.getInputStream(), StandardCharsets.UTF_8))) {
          assertEquals("1", reader.lines().collect(Collectors.joining("\n")),
              "A poll that raced with the invalid event must receive Engine.IO CLOSE");
        }
      } else {
        assertEquals(400, responseCode,
            "A poll that starts after teardown must reject the closed EIO4 session");
      }
    } catch (SocketTimeoutException timeout) {
      throw new AssertionError("An EIO4 session that sends an event before CONNECT must close immediately", timeout);
    }
  }

  @Test
  public void testHttpPollingResponseHeaders() throws URISyntaxException, IOException, InterruptedException {
    final URI uri = createTestServerUri("EIO=4&transport=polling&t=Oqd9eWh");
    HttpURLConnection http = (HttpURLConnection) uri.toURL().openConnection();
    http.connect();

    assertEquals(200, http.getResponseCode(), "HTTP Status code should be 200 OK");
    String contentType = http.getHeaderField("Content-Type");
    assertNotNull(contentType, "Content-Type header must be set");
    assertTrue(contentType.contains("text/plain"), "Content-Type should contain text/plain");

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
      String response = reader.lines().collect(Collectors.joining("\n"));
      assertNotNull(response);
      assertTrue(response.startsWith("0{"), "Handshake response should start with Engine.IO OPEN packet '0{'");
    }
  }

  @Test
  public void testUnknownPollingSessionErrorIncludesCorsHeaders() throws URISyntaxException, IOException {
    final URI uri = createTestServerUri("EIO=4&transport=polling&sid=" + UUID.randomUUID());
    HttpURLConnection http = (HttpURLConnection) uri.toURL().openConnection();
    http.setRequestProperty("Origin", TEST_ORIGIN);
    http.connect();

    assertEquals(400, http.getResponseCode(), "Unknown polling session must be rejected");
    assertEquals(TEST_ORIGIN, http.getHeaderField("Access-Control-Allow-Origin"),
        "Polling errors must retain configured CORS behavior");
    assertEquals("true", http.getHeaderField("Access-Control-Allow-Credentials"));

    InputStream errorStream = http.getErrorStream();
    assertNotNull(errorStream, "HTTP error response must contain a body");
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
      JsonNode error = mapper.readTree(reader.lines().collect(Collectors.joining("\n")));
      assertEquals(1, error.get("code").asInt(), "Unknown sessions must use Engine.IO error code 1");
      assertEquals("Session ID unknown", error.get("message").asText());
    }
  }

  @Test
  public void testV4HandshakeAdvertisesRequiredMaxPayload() throws URISyntaxException, IOException {
    final URI uri = createTestServerUri("EIO=4&transport=polling");
    HttpURLConnection http = (HttpURLConnection) uri.toURL().openConnection();
    http.connect();

    assertEquals(200, http.getResponseCode());
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
      JsonNode handshake = mapper.readTree(reader.lines().collect(Collectors.joining("\n")).substring(1));
      assertEquals(server.getConfiguration().getMaxHttpContentLength(), handshake.get("maxPayload").asInt());
      assertNotNull(handshake.get("sid"));
      assertNotNull(handshake.get("upgrades"));
      assertNotNull(handshake.get("pingInterval"));
      assertNotNull(handshake.get("pingTimeout"));
    }
  }

  @Test
  public void testInvalidEngineIOVersionAndUnknownSessionAreBadRequest() throws IOException, URISyntaxException {
    HttpURLConnection missingVersion = (HttpURLConnection) createTestServerUri("transport=polling").toURL().openConnection();
    missingVersion.connect();
    assertEquals(400, missingVersion.getResponseCode());

    HttpURLConnection unsupportedVersion = (HttpURLConnection) createTestServerUri("EIO=5&transport=polling").toURL().openConnection();
    unsupportedVersion.connect();
    assertEquals(400, unsupportedVersion.getResponseCode());

    HttpURLConnection invalidTransportCase = (HttpURLConnection) createTestServerUri("EIO=4&transport=POLLING").toURL().openConnection();
    invalidTransportCase.connect();
    assertEquals(400, invalidTransportCase.getResponseCode());

    HttpURLConnection unknownSession = (HttpURLConnection) createTestServerUri(
        "EIO=4&transport=polling&sid=00000000-0000-0000-0000-000000000000").toURL().openConnection();
    unknownSession.connect();
    assertEquals(400, unknownSession.getResponseCode());
  }

  @Test
  public void testInitialPollingHandshakeRequiresGet() throws Exception {
    for (String method : new String[] { "POST", "PUT" }) {
      HttpURLConnection request = (HttpURLConnection) createTestServerUri("EIO=4&transport=polling").toURL().openConnection();
      request.setRequestMethod(method);
      request.setDoOutput(true);
      try (OutputStream output = request.getOutputStream()) {
        output.write(new byte[0]);
      }
      assertEquals(400, request.getResponseCode(), method + " must not create an Engine.IO session");
    }
  }

  @Test
  public void testV4PreflightIsStatelessAndBinaryPollingResponsesAreText() throws Exception {
    HttpURLConnection options = (HttpURLConnection) createTestServerUri("EIO=4&transport=polling").toURL().openConnection();
    options.setRequestMethod("OPTIONS");
    options.connect();
    assertEquals(200, options.getResponseCode());
    assertEquals(null, options.getHeaderField("Set-Cookie"));

    server.addConnectListener(client -> client.sendEvent("blob", new byte[] { 1, 2, 3 }));
    String sessionId = connectForSessionId(null);
    postMessage(sessionId, "40");

    HttpURLConnection poll = (HttpURLConnection) createTestServerUri(
        "EIO=4&transport=polling&sid=" + sessionId).toURL().openConnection();
    poll.connect();
    assertEquals(200, poll.getResponseCode());
    assertTrue(poll.getHeaderField("Content-Type").contains("text/plain"));
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(poll.getInputStream(), StandardCharsets.UTF_8))) {
      assertTrue(reader.lines().collect(Collectors.joining("\n")).contains("bAQID"));
    }
  }

  @Test
  public void testV4RejectsRawBinaryPollingPost() throws Exception {
    String sessionId = connectForSessionId(null);
    HttpURLConnection post = (HttpURLConnection) createTestServerUri(
        "EIO=4&transport=polling&sid=" + sessionId).toURL().openConnection();
    post.setRequestMethod("POST");
    post.setDoOutput(true);
    post.setRequestProperty("Content-Type", "application/octet-stream");
    try (OutputStream output = post.getOutputStream()) {
      output.write(new byte[] { 4, 1, 2, 3 });
    }

    assertEquals(400, post.getResponseCode());
  }

  @Test
  public void testV4RejectsMalformedPollingPayloadAndClosesSession() throws Exception {
    String sessionId = connectForSessionId(null);
    HttpURLConnection malformedPost = (HttpURLConnection) createTestServerUri(
        "EIO=4&transport=polling&sid=" + sessionId).toURL().openConnection();
    malformedPost.setRequestMethod("POST");
    malformedPost.setDoOutput(true);
    try (OutputStream output = malformedPost.getOutputStream()) {
      output.write("abc".getBytes(StandardCharsets.UTF_8));
    }
    assertEquals(400, malformedPost.getResponseCode());

    HttpURLConnection subsequentPoll = (HttpURLConnection) createTestServerUri(
        "EIO=4&transport=polling&sid=" + sessionId).toURL().openConnection();
    subsequentPoll.connect();
    assertEquals(400, subsequentPoll.getResponseCode());
  }

  /**
   * Returns a free port number on localhost.
   * <p>
   * Heavily inspired from org.eclipse.jdt.launching.SocketUtil (to avoid a dependency to JDT just because of this).
   * Slightly improved with close() missing in JDT. And throws exception instead of returning -1.
   *
   * @return a free port number on localhost
   * @throws IllegalStateException if unable to find a free port
   */
  private static int findFreePort() {
      try (ServerSocket socket = new ServerSocket(0)) {
          socket.setReuseAddress(true);
          return socket.getLocalPort();
      } catch (IOException error) {
          throw new IllegalStateException("Could not allocate a free TCP/IP port", error);
      }
  }

}
