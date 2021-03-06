/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.tcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OpenSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author Violeta Georgieva
 */
public class SslProviderTests {
	private List<String> protocols;
	private SslContext sslContext;
	private HttpServer server;
	private SslContextBuilder serverSslContextBuilder;
	private SslContextBuilder clientSslContextBuilder;
	private DisposableServer disposableServer;

	@Before
	public void setUp() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		serverSslContextBuilder = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		clientSslContextBuilder = SslContextBuilder.forClient()
		                                           .trustManager(InsecureTrustManagerFactory.INSTANCE);
		protocols = new ArrayList<>();
		server = HttpServer.create()
		                   .port(0)
		                   .doOnBind(conf -> {
		                       SslProvider ssl = conf.sslProvider();
		                       if (ssl != null) {
		                           protocols.addAll(ssl.sslContext.applicationProtocolNegotiator().protocols());
		                           sslContext = ssl.sslContext;
		                       }
		                   });
	}

	@After
	public void tearDown() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
	}

	@Test
	public void testProtocolHttp11SslConfiguration() {
		disposableServer =
				server.protocol(HttpProtocol.HTTP11)
				      .secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .bindNow();
		assertTrue(protocols.isEmpty());
		assertTrue(OpenSsl.isAvailable() ? sslContext instanceof OpenSslContext :
		                                   sslContext instanceof JdkSslContext);
	}

	@Test
	public void testSslConfigurationProtocolHttp11_1() {
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .protocol(HttpProtocol.HTTP11)
				      .bindNow();
		assertTrue(protocols.isEmpty());
		assertTrue(OpenSsl.isAvailable() ? sslContext instanceof OpenSslContext :
		                                   sslContext instanceof JdkSslContext);
	}

	@Test
	public void testSslConfigurationProtocolHttp11_2() {
		disposableServer =
				server.protocol(HttpProtocol.H2)
				      .secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .protocol(HttpProtocol.HTTP11)
				      .bindNow();
		assertTrue(protocols.isEmpty());
		assertTrue(OpenSsl.isAvailable() ? sslContext instanceof OpenSslContext :
		                                   sslContext instanceof JdkSslContext);
	}

	@Test
	public void testProtocolH2SslConfiguration() {
		disposableServer =
				server.protocol(HttpProtocol.H2)
				      .secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .bindNow();
		assertEquals(2, protocols.size());
		assertTrue(protocols.contains("h2"));
		assertTrue(io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
		                                       sslContext instanceof OpenSslContext :
		                                       sslContext instanceof JdkSslContext);
	}

	@Test
	public void testSslConfigurationProtocolH2_1() {
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .protocol(HttpProtocol.H2)
				      .bindNow();
		assertEquals(2, protocols.size());
		assertTrue(protocols.contains("h2"));
		assertTrue(io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
		                                       sslContext instanceof OpenSslContext :
		                                       sslContext instanceof JdkSslContext);
	}

	@Test
	public void testSslConfigurationProtocolH2_2() {
		disposableServer =
				server.protocol(HttpProtocol.HTTP11)
				      .secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .protocol(HttpProtocol.H2)
				      .bindNow();
		assertEquals(2, protocols.size());
		assertTrue(protocols.contains("h2"));
		assertTrue(io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
		                                       sslContext instanceof OpenSslContext :
		                                       sslContext instanceof JdkSslContext);
	}

	@Test
	public void testTls13Support() {
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilder.protocols("TLSv1.3")))
				      .handle((req, res) -> res.sendString(Mono.just("testTls13Support")))
				      .bindNow();

		StepVerifier.create(
		        HttpClient.create()
		                  .port(disposableServer.port())
		                  .secure(spec -> spec.sslContext(clientSslContextBuilder.protocols("TLSv1.3")))
		                  .get()
		                  .uri("/")
		                  .responseContent()
		                  .aggregate()
		                  .asString())
		            .expectNext("testTls13Support")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testTls13UnsupportedProtocol_1() {
		doTestTls13UnsupportedProtocol(true, false);
	}

	@Test
	public void testTls13UnsupportedProtocol_2() {
		doTestTls13UnsupportedProtocol(false, true);
	}

	private void doTestTls13UnsupportedProtocol(boolean serverSupport, boolean clientSupport) {
		if (serverSupport) {
			serverSslContextBuilder.protocols("TLSv1.3");
		}
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .handle((req, res) -> res.sendString(Mono.just("testTls13Support")))
				      .bindNow();

		if (clientSupport) {
			clientSslContextBuilder.protocols("TLSv1.3");
		}
		StepVerifier.create(
		        HttpClient.create()
		                  .port(disposableServer.port())
		                  .secure(spec -> spec.sslContext(clientSslContextBuilder))
		                  .get()
		                  .uri("/")
		                  .responseContent()
		                  .aggregate()
		                  .asString())
		            .expectError(SSLHandshakeException.class)
		            .verify(Duration.ofSeconds(30));
	}
}
