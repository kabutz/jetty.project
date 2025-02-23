//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.ssl;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.IOResources;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.HttpServerTestBase;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HelloHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * HttpServer Tester for SSL based ServerConnector
 */
public class ServerConnectorSslServerTest extends HttpServerTestBase
{
    private SSLContext _sslContext;
    private ArrayByteBufferPool.Tracking _trackingBufferPool;
    private HttpConfiguration _httpConfiguration;

    public ServerConnectorSslServerTest()
    {
        _scheme = "https";
    }

    @BeforeEach
    public void init() throws Exception
    {
        String keystorePath = MavenTestingUtils.getTestResourcePath("keystore.p12").toString();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystorePath);
        sslContextFactory.setKeyStorePassword("storepwd");
        _trackingBufferPool = new ArrayByteBufferPool.Tracking();

        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory();
        _httpConfiguration = httpConnectionFactory.getHttpConfiguration();
        ServerConnector connector = new ServerConnector(_server, null, null, _trackingBufferPool, 1, 1, AbstractConnectionFactory.getFactories(sslContextFactory, httpConnectionFactory));

        initServer(connector);

        KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream stream = IOResources.asInputStream(sslContextFactory.getKeyStoreResource()))
        {
            keystore.load(stream, "storepwd".toCharArray());
        }
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keystore);
        _sslContext = SSLContext.getInstance("TLS");
        _sslContext.init(null, trustManagerFactory.getTrustManagers(), null);

        try
        {
            // Client configuration in case we use HttpsURLConnection.
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, SslContextFactory.TRUST_ALL_CERTS, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    public void dispose() throws Exception
    {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("Server leaks: " + _trackingBufferPool.dumpLeaks(), _trackingBufferPool.getLeaks().size(), is(0)));
    }

    @Override
    protected Socket newSocket(String host, int port) throws Exception
    {
        Socket socket = _sslContext.getSocketFactory().createSocket(host, port);
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    @Test
    public void testRequest2FixedFragments() throws Exception
    {
        startServer(new TestHandler());

        byte[] bytes = REQUEST2.getBytes();
        int[] points = new int[]{74, 325};

        // Sort the list
        Arrays.sort(points);

        URI uri = _server.getURI();
        try (Socket client = newSocket(uri.getHost(), uri.getPort()))
        {
            OutputStream os = client.getOutputStream();

            int last = 0;

            // Write out the fragments
            for (int point : points)
            {
                os.write(bytes, last, point - last);
                last = point;
                os.flush();
                Thread.sleep(PAUSE);
            }

            // Write the last fragment
            os.write(bytes, last, bytes.length - last);
            os.flush();
            Thread.sleep(PAUSE);

            // Read the response
            String response = readResponse(client);

            // Check the response
            assertEquals(RESPONSE2, response);
        }
    }

    @Override
    @Test
    public void testInterruptedRequest() throws Exception
    {
        startServer(new HelloHandler());
        Assumptions.assumeFalse(_serverURI.getScheme().equals("https"), "SSLSocket.shutdownOutput() is not supported, but shutdownOutput() is needed by the test");
    }

    @Test
    public void testSecureRequestCustomizer() throws Exception
    {
        startServer(new SecureRequestHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            String request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
            os.write(request.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, containsString("HTTP/1.1 200 OK"));
            assertThat(response, containsString("Hello world"));
            assertThat(response, containsString("scheme='https'"));
            assertThat(response, containsString("isSecure='true'"));
            assertThat(response, containsString("X509Certificate='false'"));

            Matcher matcher = Pattern.compile("cipher_suite='([^']*)'").matcher(response);
            matcher.find();
            assertThat(matcher.group(1), Matchers.allOf(not(is(emptyOrNullString()))), not(is("null")));

            matcher = Pattern.compile("key_size='([^']*)'").matcher(response);
            matcher.find();
            assertThat(matcher.group(1), Matchers.allOf(not(is(emptyOrNullString())), not(is("null"))));

            matcher = Pattern.compile("ssl_session_id='([^']*)'").matcher(response);
            matcher.find();
            assertThat(matcher.group(1), Matchers.allOf(not(is(emptyOrNullString())), not(is("null"))));

            matcher = Pattern.compile("ssl_session='([^']*)'").matcher(response);
            matcher.find();
            assertThat(matcher.group(1), Matchers.allOf(not(is(emptyOrNullString())), not(is("null"))));
        }
    }

    public static class SecureRequestHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(200);
            assertThat(request.getAttributeNameSet(), containsInAnyOrder(
                EndPoint.SslSessionData.ATTRIBUTE,
                SecureRequestCustomizer.X509_ATTRIBUTE));

            StringBuilder out = new StringBuilder();
            EndPoint.SslSessionData data = (EndPoint.SslSessionData)request.getAttribute(EndPoint.SslSessionData.ATTRIBUTE);
            assertNotNull(data);
            SSLSession session = data.sslSession();

            out.append("Hello world").append('\n');
            out.append("scheme='").append(request.getHttpURI().getScheme()).append("'").append('\n');
            out.append("isSecure='").append(request.isSecure()).append("'").append('\n');
            out.append("X509Certificate='").append(data.peerCertificates() != null).append("'").append('\n');
            out.append("cipher_suite='").append(session == null ? "" : session.getCipherSuite()).append("'").append('\n');
            out.append("key_size='").append(data.keySize()).append("'").append('\n');
            out.append("ssl_session_id='").append(data.sslSessionId()).append("'").append('\n');
            out.append("ssl_session='").append(session).append("'").append('\n');

            Content.Sink.write(response, true, out.toString(), callback);
            return true;
        }
    }
}
