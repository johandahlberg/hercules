package hercules.actors.notifiers

import akka.actor.{ ActorLogging, Actor }

/**
 * Base to be used by NotificationExecutor actors.
 * Created by johda411 on 2015-03-16.
 */
trait NotificationExecutor extends Actor with ActorLogging {}
