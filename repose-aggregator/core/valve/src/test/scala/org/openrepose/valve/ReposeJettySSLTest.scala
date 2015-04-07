/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
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
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package org.openrepose.valve

import java.io.File
import java.nio.file.Files
import javax.net.ssl.{SSLContext, SSLHandshakeException}

import org.apache.http.client.methods.HttpGet
import org.apache.http.conn.ssl.{NoopHostnameVerifier, SSLConnectionSocketFactory, TrustSelfSignedStrategy}
import org.apache.http.impl.client.HttpClients
import org.apache.http.ssl.SSLContexts
import org.junit.runner.RunWith
import org.openrepose.core.container.config.{SslCipherConfiguration, SslConfiguration, SslProtocolConfiguration}
import org.openrepose.core.spring.CoreSpringProvider
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FunSpec, Matchers}

@RunWith(classOf[JUnitRunner])
class ReposeJettySSLTest extends FunSpec with Matchers with BeforeAndAfter {

  val configDir: String = {
    val tempDir = Files.createTempDirectory("reposeSSLTesting")

    //Have to copy over the keystore
    val keystore = new File(tempDir.toFile, "keystore.jks")
    keystore.deleteOnExit()
    Files.copy(getClass.getResourceAsStream("/valveTesting/sslTesting/keystore.jks"), keystore.toPath)

    tempDir.toFile.deleteOnExit() //TODO: this isn't working, need to clean up myself
    tempDir.toString
  }

  //Acquire the protocols and ciphers this JVM supports
  val (
    defaultEnabledProtocols: List[String],
    defaultEnabledCiphers: List[String],
    allProtocols: List[String],
    allCiphers: List[String]
    ) = {
    val sslContext = SSLContext.getDefault
    val sslEngine = sslContext.createSSLEngine()
    (
      sslEngine.getEnabledProtocols.toList,
      sslEngine.getEnabledCipherSuites.toList,
      sslEngine.getSupportedProtocols.toList,
      sslEngine.getSupportedCipherSuites.toList
      )
  }

  CoreSpringProvider.getInstance().initializeCoreContext(configDir, false)

  val httpPort = 10234
  val httpsPort = 10235

  //Create an SSL configuration for the jaxb thingies
  def sslConfig(includedProtocols: List[String] = List.empty[String],
                excludedProtocols: List[String] = List.empty[String],
                includedCiphers: List[String] = List.empty[String],
                excludedCiphers: List[String] = List.empty[String],
                tlsRenegotiation: Boolean = true): SslConfiguration = {
    val s = new SslConfiguration()
    s.setKeyPassword("buttsbuttsbutts")
    s.setKeystoreFilename("keystore.jks")
    s.setKeystorePassword("buttsbuttsbutts")

    implicit val listToSslCipherConfig: List[String] => SslCipherConfiguration = { list =>
      import scala.collection.JavaConverters._
      val cfg = new SslCipherConfiguration()
      cfg.getCipher.addAll(list.asJava)
      cfg
    }
    implicit val listToSslProtocolConfig: List[String] => SslProtocolConfiguration = { list =>
      import scala.collection.JavaConverters._
      val cfg = new SslProtocolConfiguration()
      cfg.getProtocol.addAll(list.asJava)
      cfg
    }

    s.setExcludedCiphers(excludedCiphers)
    s.setIncludedCiphers(includedCiphers)
    s.setExcludedProtocols(excludedProtocols)
    s.setIncludedProtocols(includedProtocols)
    s.setTlsRenegotiationAllowed(tlsRenegotiation)

    s
  }

  //TODO: clean up this part
  println(s"Default enabled protocols: $defaultEnabledProtocols")
  println(s"Default enabled ciphers: $defaultEnabledCiphers")
  println()
  println(s"All protocols: s$allProtocols")
  println(s"All ciphers: s$allCiphers")
  println()

  //For each one of these, create a jetty server, talk to it, and make sure the SSL stuff is doing what is described

  def requestUsingProtocol(protocol: String): Unit = {
    //Protocol names come from here:
    //http://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#SSLContext

    //Explicitly using httpclient 4.4.1 because I can find an example how to exclude protocols:
    // http://stackoverflow.com/a/26439487/423218
    val sslContext = SSLContexts.custom().loadTrustMaterial(TrustSelfSignedStrategy.INSTANCE).build()

    val sf = new SSLConnectionSocketFactory(
      sslContext,
      Array(protocol),
      null,
      NoopHostnameVerifier.INSTANCE
    )

    val client = HttpClients.custom().setSSLSocketFactory(sf).build()

    //Do a get, and it should fail to negotiate SSL
    val get = new HttpGet(s"https://localhost:$httpsPort")
    val response = client.execute(get)
  }

  it("creates a jetty server excluding a list of protocols") {
    val repose = new ReposeJettyServer(
      "cluster",
      "node",
      None,
      Some(httpsPort),
      Some(sslConfig(excludedProtocols = List("TLSv1")))
    )
    repose.start()

    intercept[SSLHandshakeException] {
      requestUsingProtocol("TLSv1")
    }
  }
  it("creates a jetty server including only a list of protocols") {
    pending
  }
  it("creates a jetty server excluding a list of ciphers") {
    pending
  }
  it("creates a jetty server including only a list of ciphers") {
    pending
  }
  it("creates a jetty server that does not allow TLS renegotiation") {
    pending
  }
  it("creates a jetty server that does allow TLS renegotiation") {
    pending
  }

}
