package scalapenos.experiments.streams

import scala.concurrent._
import scala.concurrent.duration._

import akka.actor._

import akka.http.scaladsl.client._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings._

import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import akka.stream._
import akka.stream.scaladsl._

import spray.json._

object ConsulEntities {
  // trait ConsulEntity {
  //   def index: Long
  // }

  case class Service(name: String, tags: Set[String])
  // case class Services(index: Long, services: Set[Service]) extends ConsulEntity {
  //   def withTag(tag: String) = Services(index, services.filter(_.tags.contains(tag)))
  // }

  case class Services(services: Set[Service]) {
    def withTag(tag: String) = Services(services.filter(_.tags.contains(tag)))
  }

  object Services extends DefaultJsonProtocol {
    implicit val servicesFormat = lift(new RootJsonReader[Services]() {
      def read(value: JsValue) = Services(
        value.asJsObject.fields
        .collect {
          case (name, JsArray(tags)) ⇒ Service(name, tags.map(_.convertTo[String]).toSet)
        }.toSet
      )
    })

    // implicit def um(implicit ec: ExecutionContext, materializer: Materializer): Unmarshaller[HttpResponse, Services] = {
    //   new Unmarshaller[HttpResponse, Services] {
    //     val toJson: FromEntityUnmarshaller[JsValue] = sprayJsValueUnmarshaller

    //     def apply(response: HttpResponse)(implicit ec: ExecutionContext, materializer: Materializer) = {
    //       val index: Long = response.headers.find(_.is("x-consul-index")).map(_.value.toLong).getOrElse(0)

    //       toJson(response.entity)
    //         .map(value ⇒ Services(
    //           index,
    //           value.asJsObject.fields
    //           .collect {
    //             case (name, JsArray(tags)) ⇒ Service(name, tags.map(_.convertTo[String]).toSet)
    //           }.toSet
    //         ))
    //     }
    //   }
    // }
  }
}

object ConsulEndpoints {
  import ConsulEntities._
  import RequestBuilding._

  abstract class ConsulEndpoint[T](path: Uri.Path) {
    def initialRequest(implicit maxWait: Duration): HttpRequest = Get(uri(path, maxWait, None))
    def nextRequest(implicit maxWait: Duration): HttpResponse ⇒ HttpRequest = (response: HttpResponse) ⇒ {
      Get(uri(path, maxWait, consulIndex(response)))
    }

    private def uri(path: Uri.Path, maxWait: Duration, index: Option[Long]): Uri = {
      Uri.from(
        path = path.toString,
        queryString = Some(s"wait=${maxWait.toSeconds}s&index=${index.map(_.toString).getOrElse("0")}")
      )
    }
  }

  object catalog {
    object AllServices extends ConsulEndpoint[Services](Uri.Path("/v1/catalog/services"))
  }

  def consulIndex(response: HttpResponse) = response.headers.find(_.is("x-consul-index")).map(_.value.toLong)
}

object ConsulClientExperiments extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  import ConsulEntities._
  import ConsulEndpoints._
  import catalog._

  implicit val maxWait = 5 seconds
  val settings = ClientConnectionSettings(system).withIdleTimeout(maxWait * 1.2)

  // Challenge: write a Consul watch
  //
  //
  // The hostname below is a VPN-protected Consul server not reachable from the internet.
  // Please use your own Consul server if you want to run this code or use a different long polling API.
  // val poller = LongPolling()
  //   .longPollingSource("consul.nl.wehkamp.prod.blaze.ps", 8500, AllServices.initialRequest, settings)(AllServices.nextRequest)
  //   .log("app", response ⇒ s"""Response: ${response.status} ${consulIndex(response).getOrElse("")}""")
  //   .via(ifChanged[HttpResponse](HttpResponse()) {
  //     case (r1, r2) ⇒ consulIndex(r1).forall(i1 ⇒ consulIndex(r2).exists(i2 ⇒ i2 > i1))
  //   })
  //   .mapAsync(1)(Unmarshal(_).to[Services].map(_.withTag("exposed-by-gateway")))
  //   .via(ifChanged[Services](Services(Set.empty[Service])) {
  //     case (s1, s2) ⇒ s1 != s2
  //   })
  //   .log("app", response ⇒ s"Updated: ${response.services.map(_.name)}")
  //   .runWith(Sink.ignore) // this is an experiment so we're only interested in the side effects, i.e. the log lines.

  consulWatch[Services]("consul.nl.wehkamp.prod.blaze.ps", 8500, AllServices, settings)
    .map(_.withTag("exposed-by-gateway"))
    .log("app", response ⇒ s"Updated: ${response.services.map(_.name)}")
    .runWith(Sink.ignore) // this is an experiment so we're only interested in the side effects, i.e. the log lines.


  // TODO:
  //  - create a trait for Consul entities that contains the consul index so
  //    we can fuse the two mapAsync stages and the two ifChanged stages.

  // format: OFF
  import akka._
  def consulWatch[T](host: String, port: Int,
                     endpoint: ConsulEndpoint[T],
                     connectionSettings: ClientConnectionSettings = ClientConnectionSettings(system))
                    (implicit materializer: Materializer,
                              unmarshaller: FromResponseUnmarshaller[T],
                              maxWait: Duration): Source[T, NotUsed] = { // format: ON
    LongPolling()
      .longPollingSource(host, port, endpoint.initialRequest, settings)(endpoint.nextRequest)
      .log("app", response ⇒ s"""Response: ${response.status} ${consulIndex(response).getOrElse("")}""")
      .via(ifChanged {
        (r1, r2) ⇒ consulIndex(r1).forall(i1 ⇒ consulIndex(r2).exists(i2 ⇒ i2 > i1))
      })
      .mapAsync(1)(Unmarshal(_).to[T])
      .via(ifChanged())
  }

  private def ifChanged[T](hasChanged: (T, T) ⇒ Boolean = (a: T, b: T) ⇒ a != b): Flow[T, T, NotUsed] = {
    Flow[T]
      .map(Option(_))
      .prepend(Source.single(Option.empty[T]))
      .sliding(2)
      .collect {
        case Seq(None, Some(b))                        ⇒ b
        case Seq(Some(a), Some(b)) if hasChanged(a, b) ⇒ b
      }
  }
}
