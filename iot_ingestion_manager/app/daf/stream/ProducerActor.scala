/*
 * Copyright 2017 TEAM PER LA TRASFORMAZIONE DIGITALE
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package daf.stream

import java.util.concurrent.TimeUnit

import akka.actor.{ Actor, ActorRef, Props }
import config.KafkaConfig
import daf.stream.avro.EventSerDe
import it.gov.daf.iot.event.{ GenericValue, Event => AvroEvent, EventType => AvroEventType, Location => AvroLocation }
import org.apache.kafka.clients.producer.{ KafkaProducer, ProducerRecord }
import org.apache.kafka.common.serialization.{ ByteArraySerializer, StringSerializer }

import scala.concurrent.Future
import scala.collection.convert.decorateAsJava._

class ProducerActor(val config: KafkaConfig) extends Actor {

  private type Bytes = Array[Byte]

  private val producer = new KafkaProducer[String, Bytes](config.producerProps(), new StringSerializer, new ByteArraySerializer)

  private implicit val executionContext = context.dispatcher

  private def convertEventType(eventType: EventType) = eventType match {
    case MetricEvent      => AvroEventType.METRIC
    case StateChangeEvent => AvroEventType.STATE_CHANGE
    case OtherEvent       => AvroEventType.OTHER
  }

  private def buildEvent(envelope: Envelope, payload: Map[String, Any], attributes: Map[String, Any]) = AvroEvent.newBuilder
    .setVersion(envelope.version)
    .setId(envelope.id)
    .setTimestamp(envelope.timestamp)
    .setCertainty(envelope.certainty)
    .setType(convertEventType(envelope.eventType))
    .setSource(envelope.sender.source)
    .setPayload { buildAttributes(payload) }
    .setAttributes { buildAttributes(attributes) }
    .setSubtype { envelope.subType.orNull }
    .setEventAnnotation { envelope.comment.orNull }
    .setLocation { buildLocation(envelope).orNull }
    .build()

  private def buildAttributes(attributes: Map[String, Any]) = attributes.mapValues { new GenericValue(_) }.toMap[CharSequence, GenericValue].asJava

  private def buildLocation(envelope: Envelope) = envelope.location.map { location =>
    AvroLocation.newBuilder
      .setLatitude(location.latitude)
      .setLongitude(location.longitude)
      .build()
  }

  private def createRecord(topic: String, event: AvroEvent) = new ProducerRecord[String, Bytes](
    topic,
    event.getId.toString,
    EventSerDe.serialize(event)
  )

  private def send(envelope: Envelope, payload: Map[String, Any], attributes: Map[String, Any]) = Future {
    producer.send {
      createRecord(
        topic = envelope.topic,
        event = buildEvent(envelope, payload, attributes)
      )
    }.get(config.timeout.toMillis, TimeUnit.MILLISECONDS)
  }

  private def reply[A](f: ActorRef => A) = f { sender() }

  private def doSend(envelope: Envelope, payload: Map[String, Any], attributes: Map[String, Any], originalSender: ActorRef) = send(envelope, payload, attributes).onSuccess {
    case metadata => originalSender ! ServiceMessageMetadata.fromKafka(envelope.id, metadata)
  }

  def receive = {
    case ServiceMessage(envelope, payload, attributes) => reply { doSend(envelope, payload, attributes, _) }
  }

}

object ProducerActor {

  def props(kafkaConfig: KafkaConfig) = Props { new ProducerActor(kafkaConfig) }

}

