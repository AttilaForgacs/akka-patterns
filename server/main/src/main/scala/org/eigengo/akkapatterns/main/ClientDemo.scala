package org.eigengo.akkapatterns.main

import akka.actor.{Props, ActorSystem}
import akka.util.Timeout
import com.rabbitmq.client.{DefaultConsumer, Channel, Envelope, ConnectionFactory}
import com.rabbitmq.client.AMQP.BasicProperties
import akka.actor.SupervisorStrategy.Stop
import com.github.sstone.amqp._
import com.github.sstone.amqp.RpcClient._
import com.github.sstone.amqp.Amqp._

/**
 * @author janmachacek
 */
object ClientDemo {

  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val timeout = Timeout(100000L)

  def main(args: Array[String]) {
    val actorSystem = ActorSystem("AkkaPatterns")

    // RabbitMQ connection factory
    val connectionFactory = new ConnectionFactory()
    connectionFactory.setHost("localhost")
    connectionFactory.setVirtualHost("/")

    // create a "connection owner" actor, which will try and reconnect automatically if the connection is lost
    val connection = actorSystem.actorOf(Props(new ConnectionOwner(connectionFactory)))

    val madhouse = ClientDemo.getClass.getResource("/madouse.jpg").getPath
    Thread.sleep(1000)

    val count = 16
    val clients = (0 until count).map(_ => ConnectionOwner.createChildActor(connection, Props(new RpcStreamingClient())))
    clients.foreach(_ ! Request(Publish("amq.direct", "image.key", madhouse.getBytes) :: Nil))
    Thread.sleep(100000)
    clients.foreach(_ ! Stop)
  }

  class RpcStreamingClient(channelParams: Option[ChannelParameters] = None) extends ChannelOwner(channelParams) {
    var queue: String = ""
    var consumer: Option[DefaultConsumer] = None

    override def onChannel(channel: Channel) {
      // create a private, exclusive reply queue; its name will be randomly generated by the broker
      queue = declareQueue(channel, QueueParameters("", passive = false, exclusive = true)).getQueue
      consumer = Some(new DefaultConsumer(channel) {
        override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
          self ! Delivery(consumerTag, envelope, properties, body)
        }
      })
      channel.basicConsume(queue, false, consumer.get)
    }

    when(ChannelOwner.Connected) {
      case Event(p: Publish, ChannelOwner.Connected(channel)) => {
        val props = p.properties.getOrElse(new BasicProperties()).builder.replyTo(queue).build()
        channel.basicPublish(p.exchange, p.key, p.mandatory, p.immediate, props, p.body)
        stay()
      }
      case Event(Stop, ChannelOwner.Connected(channel)) =>
        channel.close()
        stop()
      case Event(Request(publish, numberOfResponses), ChannelOwner.Connected(channel)) => {
        publish.foreach(p => {
          val props = p.properties.getOrElse(new BasicProperties()).builder.replyTo(queue).build()
          channel.basicPublish(p.exchange, p.key, p.mandatory, p.immediate, props, p.body)
        })
        stay()
      }
      case Event(delivery@Delivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]), ChannelOwner.Connected(channel)) => {
        channel.basicAck(envelope.getDeliveryTag, false)
        println("|"+ delivery.body.length + "|")
        //val fos = new FileOutputStream("x.jpeg")
        //fos.write(delivery.body)
        //fos.close()
        stay()
      }
      case Event(msg@ReturnedMessage(replyCode, replyText, exchange, routingKey, properties, body), ChannelOwner.Connected(channel)) => {
        stay()
      }
    }
  }
}
