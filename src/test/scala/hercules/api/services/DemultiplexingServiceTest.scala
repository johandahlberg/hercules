
// TODO Temporarily disabled. /JD 20150507
//package hercules.api.services
//
//import akka.actor.ActorSystem
//import akka.contrib.pattern.ClusterClient.SendToAll
//import akka.testkit.{ TestKit, TestProbe }
//import akka.util.Timeout
//import hercules.actors.masters.MasterStateProtocol
//import hercules.actors.masters.state.MasterState
//import hercules.protocols.HerculesMainProtocol._
//import hercules.test.utils.ProcessingUnitPlaceholder
//import org.scalatest.{ BeforeAndAfterAll, FlatSpecLike, Matchers }
//import scala.concurrent.duration._
//import scala.concurrent.ExecutionContext
//import spray.testkit.ScalatestRouteTest
//import spray.http.StatusCodes._
//import spray.routing.Directives
//import spray.http.StatusCodes
//
//class DemultiplexingServiceTest
//    extends FlatSpecLike
//    with Matchers
//    with BeforeAndAfterAll
//    with ScalatestRouteTest {
//
//  import MasterStateProtocol._
//
//  val unprocessedUnit = "unprocessedUnit"
//  val inProcessingUnitId = "testUnitInProcessing"
//  val testFailedUnitId = "testId"
//  val secondFailedUnit = "testId2"
//
//  val messagesNotYetProcessed = Set(unprocessedUnit)
//  val messagesInProcessing = Set(inProcessingUnitId)
//  val failedMessages = Set(testFailedUnitId, secondFailedUnit)
//
//  val masterState = MasterState(
//    messagesNotYetProcessed.map { (id: String) => FoundProcessingUnitMessage(ProcessingUnitPlaceholder(id)) },
//    messagesInProcessing.map { (id: String) => StartDemultiplexingProcessingUnitMessage(ProcessingUnitPlaceholder(id)) },
//    failedMessages.map { (id: String) => FailedDemultiplexingProcessingUnitMessage(ProcessingUnitPlaceholder(id), "Testing failure") }
//  )
//
//  val probe = MockBackend(
//    system = this.system,
//    masterState = masterState)
//
//  val timeout = Timeout(5.seconds)
//  val service = new DemultiplexingService {
//    def actorRefFactory = system
//    implicit val to = timeout
//    implicit val clusterClient = probe.ref
//  }
//
//  override def afterAll(): Unit = {
//    system.shutdown()
//    Thread.sleep(1000)
//  }
//
//  "A DELETE request to /demultiplex/[id]/forget" should " return an Accepted status code" in {
//    Delete(s"/demultiplex/$unprocessedUnit/forget") ~> service.route ~> check {
//      status should be(OK)
//    }
//  }
//
//  it should "trigger a ForgetDemultiplexingProcessingUnitMessage to master" in {
//    probe.expectMsg(3.seconds,
//      SendToAll(
//        "/user/master/active",
//        ForgetDemultiplexingProcessingUnitMessage(unprocessedUnit)))
//  }
//
//  // NOTE: The next two tests are dependent on a ugly hack in the mock backend which rejects
//  // messages with id testUnitInProcessing
//  it should "return a Bad Request status code if asked to forget a Processing Unit already being processed" in {
//    Delete(s"/demultiplex/$inProcessingUnitId/forget") ~> service.route ~> check {
//      status should be(BadRequest)
//    }
//  }
//
//  it should "trigger another ForgetDemultiplexingProcessingUnitMessage to master" in {
//    probe.expectMsg(3.seconds,
//      SendToAll(
//        "/user/master/active",
//        ForgetDemultiplexingProcessingUnitMessage("testUnitInProcessing")))
//  }
//
//  /*
//    "A PUT requests to /demultiplex/[id]/stop" should "return a NotImplemented status code" in {
//      Put("/demultiplex/testId/stop") ~> service.route ~> check {
//        status should be(NotImplemented)
//      }
//    }
//    it should "trigger a StopDemultiplexingProcessingUnitMessage to master" in {
//      probe.expectMsg(3.seconds,
//        SendToAll(
//          "/user/master/active",
//          StopDemultiplexingProcessingUnitMessage("testId")))
//    }
//  */
//
//  "A DELETE request to /demultiplex/[id]/remove on an existing unit" should "return a OK status code" in {
//    Delete(s"/demultiplex/$testFailedUnitId/remove") ~> service.route ~> check {
//      status should be(OK)
//    }
//  }
//  it should "trigger a RequestMasterState to master, followed by a RemoveFromFailedMessages message to master" in {
//    probe.expectMsgAllOf(5.seconds,
//      SendToAll(
//        "/user/master/active",
//        RequestMasterState(Some("testId"))),
//      SendToAll(
//        "/user/master/active",
//        RemoveFromFailedMessages(
//          Some(
//            FailedDemultiplexingProcessingUnitMessage(
//              ProcessingUnitPlaceholder(testFailedUnitId),
//              "Testing failure")))))
//  }
//
//  "A DELETE request to /demultiplex/[id]/remove on a non-existing unit" should "return a NotFound status code" in {
//    Delete("/demultiplex/testIdMissing/remove") ~> service.route ~> check {
//      status should be(NotFound)
//    }
//  }
//  it should "trigger a RequestMasterState to master, but not followed by a any more messages" in {
//    probe.expectMsg(3.seconds,
//      SendToAll(
//        "/user/master/active",
//        RequestMasterState(Some("testIdMissing"))))
//    probe.expectNoMsg(3.seconds)
//  }
//
//  "A PUT requests to /demultiplex/[id]/restart on an existing unit" should "return an ACCEPTED status code" in {
//    Put(s"/demultiplex/$secondFailedUnit/restart") ~> service.route ~> check {
//      status should be(Accepted)
//    }
//  }
//  it should "trigger a RestartDemultiplexingProcessingUnitMessage to master" in {
//    probe.expectMsg(3.seconds,
//      SendToAll(
//        "/user/master/active",
//        RestartDemultiplexingProcessingUnitMessage(secondFailedUnit)))
//  }
//
//  "A PUT requests to /demultiplex/[id]/restart on a non-existing unit" should "return an NotFound status code" in {
//    Put("/demultiplex/testIdMissing/restart") ~> service.route ~> check {
//      status should be(NotFound)
//    }
//  }
//  it should "trigger a RestartDemultiplexingProcessingUnitMessage to master" in {
//    probe.expectMsg(3.seconds,
//      SendToAll(
//        "/user/master/active",
//        RestartDemultiplexingProcessingUnitMessage("testIdMissing")))
//  }
//
//}