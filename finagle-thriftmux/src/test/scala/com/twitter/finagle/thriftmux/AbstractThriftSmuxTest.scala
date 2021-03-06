package com.twitter.finagle.thriftmux

import com.twitter.conversions.time._
import com.twitter.finagle.mux.transport.{IncompatibleNegotiationException, OpportunisticTls}
import com.twitter.finagle.netty4.channel.ChannelSnooper
import com.twitter.finagle.ssl.server.SslServerConfiguration
import com.twitter.finagle.ssl.{KeyCredentials, TrustCredentials}
import com.twitter.finagle.thriftmux.thriftscala._
import com.twitter.finagle.toggle.flag
import com.twitter.finagle._
import com.twitter.finagle.mux.exp.pushsession.MuxPush
import com.twitter.finagle.stats.{InMemoryStatsReceiver, StatsReceiver}
import com.twitter.io.TempFile
import com.twitter.util.{Await, Closable, Future, Try}
import io.netty.channel.ChannelPipeline
import java.net.InetSocketAddress
import org.scalatest.FunSuite

// duplicated in SmuxTest, please update there too
abstract class AbstractThriftSmuxTest extends FunSuite {
  import AbstractThriftSmuxTest._

  protected def serverImpl(): ThriftMux.Server

  protected def clientImpl(): ThriftMux.Client

  private def serve(
    serverLevel: Option[OpportunisticTls.Level]
  ): ListeningServer = (serverLevel match {
    case None =>
      serverImpl()
    case Some(level) =>
      serverImpl()
        .withTransport.tls(mkConfig())
        .withOpportunisticTls(level)
  }).serveIface("localhost:*", concatIface)

  private def newService(
    clientLevel: Option[OpportunisticTls.Level],
    record: ThriftMux.Client => ThriftMux.Client,
    stats: StatsReceiver,
    addr: InetSocketAddress
  ): TestService.FutureIface = record(
    clientLevel match {
      case None =>
        clientImpl()
          .withStatsReceiver(stats)
      case Some(level) =>
        clientImpl()
          .withStatsReceiver(stats)
          .withTransport.tlsWithoutValidation
          .withOpportunisticTls(level)
    }
  ).newIface[TestService.FutureIface](
    Name.bound(Address(addr)),
    "client"
  )

  def smuxTest(testCases: Seq[TlsPair], testFn: (Try[String], String, InMemoryStatsReceiver) => Unit): Unit = {
    for {
      (clientLevel, serverLevel) <- testCases
    } {
      val buffer = new StringBuffer()
      val stats = new InMemoryStatsReceiver

      flag.overrides.let(Mux.param.MuxImpl.TlsHeadersToggleId, 1.0) {
        val server = serve(serverLevel)
        val addr = server.boundAddress.asInstanceOf[InetSocketAddress]

        val client = newService(clientLevel, record(buffer), stats, addr)
        val results = await(client.query("." * 10).liftToTry)
        testFn(results, buffer.toString, stats)

        Await.ready(Closable.all(server).close(), 5.seconds)
      }
    }
  }

  // tests
  test("thriftsmux: can talk to each other with opportunistic tls") {
    smuxTest(compatibleEnabledLevels, { case (results, string, _) =>
      assert(results.get == "." * 20)
      // we check that it's non-empty to ensure that it was correctly installed
      assert(!string.isEmpty)
      // check that the payload isn't in cleartext over the wire
      assert(!string.toString.contains("." * 10))
    })
  }

  test("thriftsmux: can talk to each other when both parties are off") {
    smuxTest(compatibleUndesiredDisabledLevels, { case (results, string, _) =>
      assert(results.get == "." * 20)
      assert(string.isEmpty)
    })
  }

  test("thriftsmux: can talk to each other when one party is off") {
    smuxTest(compatibleDesiredDisabledLevels, { case (results, string, _) =>
      assert(results.get == "." * 20)
      assert(string.isEmpty)
    })
  }

  test("thriftsmux: can't talk to each other with incompatible opportunistic tls") {
    smuxTest(incompatibleLevels, { case (results, string, stats) =>
      intercept[IncompatibleNegotiationException] {
        results.get
      }
      assert(string.isEmpty)

      // TODO: remove the special casing once we get rid of the standard mux client
      // The standard client doesn't have a mechanism for failing service acquisition
      // based on the result of the handshake and adding that would be pretty invasive
      // so we are going to punt on that for now and just wait for the push-based
      // client to become the primary implementation since it does have that ability.
      if (clientImpl().muxer.isInstanceOf[MuxPush.Client]) {
        assert(stats.counters.get(Seq("client", "failures")) == None)
        assert(stats.counters.get(Seq("client", "service_creation", "failures")) == Some(1))
        assert(stats.counters.get(Seq("client", "service_creation", "failures",
          "com.twitter.finagle.mux.transport.IncompatibleNegotiationException")) == Some(1))
      } else if (clientImpl().muxer.isInstanceOf[Mux.Client]) {
        assert(stats.counters.get(Seq("client", "failures")) == Some(1))
        assert(stats.counters.get(Seq("client", "service_creation", "failures")) == None)
      } else {
        fail(s"Unexpected client muxer (${clientImpl().muxer}): update this test")
      }
    })
  }
}

object AbstractThriftSmuxTest {
  type TlsPair = (Option[OpportunisticTls.Level], Option[OpportunisticTls.Level])

  val concatIface = new TestService.FutureIface {
    def query(x: String): Future[String] = Future.value(x.concat(x))
  }

  def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  def mkConfig(): SslServerConfiguration = {
    val certFile = TempFile.fromResourcePath("/ssl/certs/svc-test-server.cert.pem")
    // deleteOnExit is handled by TempFile

    val keyFile = TempFile.fromResourcePath("/ssl/keys/svc-test-server-pkcs8.key.pem")
    // deleteOnExit is handled by TempFile

    SslServerConfiguration(
      keyCredentials = KeyCredentials.CertAndKey(certFile, keyFile),
      trustCredentials = TrustCredentials.Insecure
    )
  }

  def record(buffer: StringBuffer)(client: ThriftMux.Client): ThriftMux.Client = {
    val recordingPrinter: (Stack.Params, ChannelPipeline) => Unit = (params, pipeline) => {
      Mux.Client.tlsEnable(params, pipeline)
      pipeline.addFirst(ChannelSnooper.byteSnooper("whatever") { (string, _) =>
        buffer.append(string)
      })
    }

    client.configured(Mux.param.TurnOnTlsFn(recordingPrinter))
  }

  // test cases
  val compatibleEnabledLevels: Seq[TlsPair] = {
    val canSpeakTls = Seq(OpportunisticTls.Desired, OpportunisticTls.Required)
    for {
      left <- canSpeakTls
      right <- canSpeakTls
    } yield (Some(left), Some(right))
  }

  val compatibleUndesiredDisabledLevels: Seq[TlsPair] = {
    val noSpeakTls = Seq(Some(OpportunisticTls.Off), None)
    for {
      left <- noSpeakTls
      right <- noSpeakTls
    } yield (left, right)
  }

  val compatibleDesiredDisabledLevels: Seq[TlsPair] = {
    val noSpeakTls = Seq(Some(OpportunisticTls.Off), None)
    val leftNoSpeakTls = noSpeakTls.map((_, Some(OpportunisticTls.Desired)))
    leftNoSpeakTls ++ leftNoSpeakTls.map(_.swap)
  }

  val incompatibleLevels: Seq[TlsPair] = {
    val noSpeakTls = Seq(Some(OpportunisticTls.Off), None)
    val leftNoSpeakTls = noSpeakTls.map((_, Some(OpportunisticTls.Required)))
    leftNoSpeakTls ++ leftNoSpeakTls.map(_.swap)
  }
}
