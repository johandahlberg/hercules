package hercules.actors.masters.state

import hercules.actors.masters.MasterStateProtocol._
import hercules.protocols.HerculesMainProtocol.{ ProcessingMessage, ProcessingUnitMessage, ProcessingUnitNameMessage }
import spray.json.{ JsValue, JsObject, JsArray, DefaultJsonProtocol }

/**
 * Created by johda411 on 2015-04-21.
 */
case class MasterState(
    val messagesNotYetProcessed: Set[ProcessingMessage] = Set(),
    val messagesInProcessing: Set[ProcessingMessage] = Set(),
    val failedMessages: Set[ProcessingMessage] = Set()) {

  /**
   * Manipulate the state of the message sets depending on what message has been
   * sent
   * @param x the SetStateMessage describing the message to add or remove
   * @return the MasterState after the manipulation
   */
  def manipulateState(x: SetStateMessage): MasterState = {
    def add[A](l: Set[A], e: A): Set[A] = l + e
    def sub[A](l: Set[A], e: A): Set[A] = l - e

    def manipulateStateList[A](lst: Set[A], elem: Option[A], op: (Set[A], A) => Set[A]): Set[A] =
      if (elem.nonEmpty) op(lst, elem.get)
      else lst

    x match {
      case AddToMessageNotYetProcessed(message) =>
        this.copy(messagesNotYetProcessed = manipulateStateList(messagesNotYetProcessed, message, add))

      case RemoveFromMessageNotYetProcessed(message) =>
        this.copy(messagesNotYetProcessed = manipulateStateList(messagesNotYetProcessed, message, sub))

      case AddToMessagesInProcessing(message) =>
        this.copy(messagesInProcessing = manipulateStateList(messagesInProcessing, message, add))

      case RemoveFromMessagesInProcessing(message) =>
        this.copy(messagesInProcessing = manipulateStateList(messagesInProcessing, message, sub))

      case AddToFailedMessages(message) =>
        this.copy(failedMessages = manipulateStateList(failedMessages, message, add))

      case RemoveFromFailedMessages(message) =>
        this.copy(failedMessages = manipulateStateList(failedMessages, message, sub))
    }
  }

  /**
   * Gets the master state as is, or filtered for the unitName option.
   * @param unitName set to None to get state for all units
   * @return a MasteState filtered for unitName (if present)
   */
  def findStateOfUnit(unitName: Option[String]): MasterState = {
    // Predicate to filter out messages based on the unit id contained
    // within. Always return true if the supplied unitName option is empty.
    def pred[A <: ProcessingMessage](x: A): Boolean = {
      unitName.isEmpty || (x match {
        case msg: ProcessingUnitMessage =>
          msg.unit.name == unitName.get
        case msg: ProcessingUnitNameMessage =>
          msg.unitName == unitName.get
        case _ => false
      })
    }

    MasterState(
      messagesNotYetProcessed =
        messagesNotYetProcessed.filter(pred),
      messagesInProcessing =
        messagesInProcessing.filter(pred),
      failedMessages =
        failedMessages.filter(pred)
    )
  }

  def toJson: JsValue = {
    def mapToJson(set: Set[ProcessingMessage]): JsArray =
      JsArray(set.map(_.toJson).toVector)

    JsObject(
      "messagesNotYetProcessed" -> mapToJson(messagesNotYetProcessed),
      "messagesInProcessing" -> mapToJson(messagesInProcessing),
      "failedMessages" -> mapToJson(failedMessages))
  }

}
