package hercules.actors.masters

import scala.concurrent.duration.DurationInt
import com.typesafe.config.ConfigFactory
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.contrib.pattern.ClusterReceptionistExtension
import akka.contrib.pattern.ClusterSingletonManager
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.pattern.pipe
import akka.persistence.PersistentActor
import akka.persistence.SaveSnapshotFailure
import akka.persistence.SaveSnapshotSuccess
import akka.persistence.SnapshotOffer
import akka.util.Timeout
import hercules.config.masters.MasterActorConfig
import hercules.entities.{ ProcessingUnit, ProcessingUnitPlaceholder }
import hercules.protocols.HerculesMainProtocol._
import hercules.actors.masters.MasterStateProtocol._

object SisyphusMasterActor {

  /**
   * Initiate all the stuff needed to start a SisyphusMasterActor
   * including initiating the system.
   */
  def startSisyphusMasterActor(): Unit = {

    val generalConfig = ConfigFactory.load()
    val conf = generalConfig.getConfig("master").withFallback(generalConfig)

    val masterConfig = new MasterActorConfig(conf.getInt("snapshot.interval"))

    val system = ActorSystem("ClusterSystem", conf)

    system.actorOf(
      ClusterSingletonManager.props(
        SisyphusMasterActor.props(masterConfig),
        "active",
        PoisonPill,
        Some("master")),
      "master")

    sys.addShutdownHook(system.shutdown())
  }

  /**
   * Create a new SisyphusMasterActor
   */
  def props(config: MasterActorConfig): Props = Props(new SisyphusMasterActor(config))

  /**
   * Filter out the messages with conform to type A from a list of
   * messages.
   * @param messageSeq
   * @return All messages of type A
   */
  def findMessagesOfType[A <: ProcessingUnitMessage](messageSeq: Set[ProcessingUnitMessage]): Set[A] = {
    messageSeq.filter(p => p.isInstanceOf[A]).map(p => p.asInstanceOf[A])
  }
}

/**
 * Defines the logic for running the Sisyphus workflow
 *
 * The other actors in the system will register to the master,
 * and request work from it. If the master has work for the actor it will
 * send it.
 */
class SisyphusMasterActor(config: MasterActorConfig) extends PersistentActor with HerculesMasterActor {

  // Use this as a message to save snapshot of the current actor state.
  case object TakeASnapshot

  override def persistenceId = "SisyphusMasterActor"

  // The master will register it self to the cluster receptionist.
  ClusterReceptionistExtension(context.system).registerService(self)

  import context.dispatcher

  //@TODO Make request new work period configurable.
  // Make sure that the system is snapshoted every now and then
  val saveStateSnapshot =
    context.system.scheduler.schedule(
      config.snapshotInterval.seconds,
      config.snapshotInterval.seconds,
      self,
      TakeASnapshot)

  // Make sure that the scheduled event stops if the actors does.
  override def postStop() = {
    saveStateSnapshot.cancel()
  }

  // Replay messages and snapshots to restore actor state
  override def receiveRecover: Receive = {
    case x: SetStateMessage =>
      state = state.manipulateState(x)
    case SnapshotOffer(_, snapshot: MasterState) =>
      state = snapshot
  }

  override def receiveCommand: Receive = LoggingReceive {

    // @TODO Remove this debug statement
    case message: StringMessage => {
      sender ! StringMessage(message.s.reverse)
    }

    // Only messages handled by this method will manipulate the state
    // of the actor, and therefore they need to be persisted
    case message: SetStateMessage => {
      persist(message)(m => state = state.manipulateState(m))
    }

    case RequestMasterState(unit) => {
      if (unit.isDefined)
        sender ! state.findStateOfUnit(unit)
      else
        sender ! state
    }

    case TakeASnapshot => {
      saveSnapshot(state)
    }

    case SaveSnapshotFailure(metadata, reason) => {
      log.error(s"Failed to save a snapshot - metadata: $metadata reason: $reason")
      notice.warning(s"Failed to save a snapshot - metadata: $metadata reason: $reason")
    }

    case SaveSnapshotSuccess(_) => {
      log.debug("Yeah! We save that that snapshot!")
    }

    case message: FoundProcessingUnitMessage => {
      self ! AddToMessageNotYetProcessed(Some(message))
    }

    case message: DemultiplexingMessage => receiveDemultiplexingMessage(message)

    // A ProcessingUnitWatcherActor is asking for something to do
    case RequestProcessingUnitMessage => {
      import context.dispatcher
      implicit val timeout = Timeout(5 seconds)

      val unitsToProcess = SisyphusMasterActor.findMessagesOfType[ForgetProcessingUnitMessage](state.messagesNotYetProcessed)
      for (unitMessage <- unitsToProcess) {
        log.debug("Sending ProcessingUnit messages...")
        (sender ? unitMessage).map {
          case Acknowledge => {
            log.debug(s"$unitMessage was executed successfully by ProcessingUnitWatcher. Removing from work queue.")
            RemoveFromMessageNotYetProcessed(Some(unitMessage))
          }
          case Reject =>
            log.debug(s"$unitMessage was not accepted by ProcessingUnitWatcher. Keep it in the work queue.")
        } pipeTo (self)
      }
    }
  }

  //@TODO Make sure that this warns if the entire setup if DemultiplexingMessage
  // is not handled. That's the point of having a sealed trait!
  // JD 20140929
  def receiveDemultiplexingMessage(demuxMessage: DemultiplexingMessage): Unit = {

    demuxMessage match {

      case FinishedDemultiplexingProcessingUnitMessage(unit) => {
        //@TODO Later more behaviour downstream of demultiplexing should
        // be added here!
        log.debug("Noted that " + unit.name + " has finished " +
          " demultiplexing. I'll remove it from the messagesInProcessing set.")
        self ! RemoveFromMessagesInProcessing(state.findStateOfUnit(Some(unit.name)).messagesInProcessing.headOption)
      }

      case message: FailedDemultiplexingProcessingUnitMessage => {
        log.warning("Noted that " + message.unit.name + " has failed " +
          " demultiplexing. Will move it into the list of failed jobs.")
        self ! RemoveFromMessagesInProcessing(state.findStateOfUnit(Some(message.unit.name)).messagesInProcessing.headOption)
        self ! AddToFailedMessages(Some(message))
      }

      // Forget that the demultiplexing of a unit has taken place
      case ForgetDemultiplexingProcessingUnitMessage(id) => {

        val unitState = state.findStateOfUnit(Some(id))
        if (SisyphusMasterActor.findMessagesOfType[StartDemultiplexingProcessingUnitMessage](
          unitState.messagesInProcessing
        ).nonEmpty) {
          Reject(Some(s"ProcessingUnit $id is being processed"))
        } else {
          // If the unit is queued for processing, remove it before forgetting
          if (unitState.messagesNotYetProcessed.nonEmpty) {
            self ! RemoveFromMessageNotYetProcessed(
              SisyphusMasterActor.findMessagesOfType[FoundProcessingUnitMessage](
                unitState.messagesNotYetProcessed)
                .headOption)
          }
          // If the unit has failed processing, remove it before forgetting
          if (unitState.failedMessages.nonEmpty) {
            self ! RemoveFromFailedMessages(
              SisyphusMasterActor.findMessagesOfType[FailedDemultiplexingProcessingUnitMessage](
                unitState.failedMessages)
                .headOption)
          }
          // Add the request to forget the unit to the MessagesNotYetProcessed queue
          self ! AddToMessageNotYetProcessed(
            Some(
              ForgetProcessingUnitMessage(
                ProcessingUnitPlaceholder(id))))

          // Acknowledge that we handled the message, although it could still be rejected 
          // by a ProcessingUnitWatcherActor further down the line
          sender ! Acknowledge
        }
      }

      case RequestDemultiplexingProcessingUnitMessage => {

        log.debug("Processing RequestDemultiplexingProcessingUnitMessage!")

        val unitsReadyForDemultiplexing = SisyphusMasterActor.
          findMessagesOfType[FoundProcessingUnitMessage](state.messagesNotYetProcessed)

        import context.dispatcher
        implicit val timeout = Timeout(5 seconds)

        for (unitMessage <- unitsReadyForDemultiplexing) {
          log.debug("Sending...")
          val startMsg = StartDemultiplexingProcessingUnitMessage(unitMessage.unit)
          (sender ? startMsg).map {
            case Acknowledge => {
              log.debug(s"$unitMessage was accepted by demultiplexer removing from work queue.")
              // ugh...
              self ! AddToMessagesInProcessing(Some(startMsg))
              RemoveFromMessageNotYetProcessed(Some(unitMessage))
            }
            case Reject =>
              log.debug(s"$unitMessage was not accepted by demultiplexer. Keep it in the work queue.")
          } pipeTo (self)
        }
      }

      // Refer to change state messages.
      case message: RestartDemultiplexingProcessingUnitMessage => {
        val matchingMessage =
          SisyphusMasterActor.findMessagesOfType[FailedDemultiplexingProcessingUnitMessage](
            state.findStateOfUnit(
              Some(message.unitName)
            ).failedMessages)
            .headOption
        if (matchingMessage.nonEmpty) {
          log.debug(
            "For a message to restart " + message.unitName +
              " moving it into the messages to process list.")
          notice.info("Restarting demultiplexing for processingunit: " + message.unitName)
          self ! AddToMessageNotYetProcessed(Some(FoundProcessingUnitMessage(matchingMessage.get.unit)))
          self ! RemoveFromFailedMessages(matchingMessage)
          sender ! Acknowledge
        } else {
          log.warning("Couldn't find unit " + message.unitName + " requested to restart.")
          notice.info("Could not find processingunit: " + message.unitName + " to restart demultiplexing for")
          sender ! Reject(Some("Couldn't find unit " + message.unitName + " requested to restart."))
        }

      }

    }

  }

}

