package scalapenos.experiments.streams

import java.io.File

import scala.concurrent.duration._
import scala.concurrent._
import scala.util._

import akka._
import akka.actor.ActorSystem

import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling._

import Uri._

import akka.stream._
import akka.stream.scaladsl._

object LoadBalancedClientApp extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  val servers = List(
    Server("localhost", 5000),
    Server("localhost", 5001))

  val done = sendRequests(5, servers)

  Await.result(done, 1.minute)
  Await.ready(Http().shutdownAllConnectionPools(), 1.second)
  Await.result(system.terminate(), 1.seconds)

  println(s"Done.")

  case class Server(host: String, port: Int)

  private def sendRequests(nrOfRequests: Int, servers: Seq[Server]): Future[Done] =
    requests(nrOfRequests)
      .via(loadBalancedFlow(servers))
      .runForeach {
        case (response, id) ⇒ {
          val responseAsString = Await.result(Unmarshal(response.get.entity).to[String], 1.second)
          println(s"${id}: Response = ${responseAsString}")
        }
      }

  private def requests(nr: Int): Source[(HttpRequest, Int), NotUsed] = {
    val reqs = (1 to nr).map { id ⇒
      HttpRequest(uri = "/twitter") → id
    }

    Source(reqs)
  }

  private def loadBalancedFlow(servers: Seq[Server]): Flow[(HttpRequest, Int), (Try[HttpResponse], Int), NotUsed] = {
    val workers = servers.map { server ⇒
      Flow[(HttpRequest, Int)]
        .map {
          case in @ (request, id) ⇒ {
            println(s"${id}: Sending request to ${server.host}:${server.port}")
            in
          }
        }
        .via(Http().cachedHostConnectionPool[Int](host = server.host, port = server.port).mapMaterializedValue(_ ⇒ NotUsed))
    }

    balancer(workers)
  }

  private def balancer[In, Out](workers: Seq[Flow[In, Out, Any]]): Flow[In, Out, NotUsed] = {
    import GraphDSL.Implicits._

    Flow.fromGraph(GraphDSL.create() { implicit b ⇒
      val balancer = b.add(Balance[In](workers.size, waitForAllDownstreams = true))
      val merge = b.add(Merge[Out](workers.size))

      workers.foreach { worker ⇒
        balancer ~> worker ~> merge
      }

      FlowShape(balancer.in, merge.out)
    })
  }

}
