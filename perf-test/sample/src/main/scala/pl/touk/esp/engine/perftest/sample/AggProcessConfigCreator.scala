package pl.touk.esp.engine.perftest.sample

import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import org.apache.flink.streaming.util.serialization.KeyedSerializationSchema
import pl.touk.esp.engine.api.VerboselyLoggingExceptionHandler
import pl.touk.esp.engine.api.process.ProcessConfigCreator
import pl.touk.esp.engine.kafka.{KafkaConfig, KafkaSinkFactory, KafkaSourceFactory}
import pl.touk.esp.engine.perftest.sample.model.KeyValue
import pl.touk.esp.engine.util.{CsvSchema, LoggingListener}

class AggProcessConfigCreator extends ProcessConfigCreator {

  import org.apache.flink.streaming.api.scala._

  override def listeners(config: Config) = List(LoggingListener)

  override def sourceFactories(config: Config) = {
    val kafkaConfig = config.as[KafkaConfig]("kafka")
    Map(
      "kafka-keyvalue" -> new KafkaSourceFactory[KeyValue](kafkaConfig, new CsvSchema(KeyValue.apply), Some(_.date.getTime))
    )
  }

  override def sinkFactories(config: Config) = {
    val kafkaConfig = config.as[KafkaConfig]("kafka")
    val intSerializationSchema = new KeyedSerializationSchema[Any] {

      override def serializeValue(element: Any) = {
        element.asInstanceOf[Int].toString.getBytes
      }

      override def serializeKey(element: Any) =
        null

      override def getTargetTopic(element: Any) =
        null

    }

    Map(
      "kafka-int" -> new KafkaSinkFactory(kafkaConfig.kafkaAddress, intSerializationSchema)
    )
  }

  override def services(config: Config) = Map.empty

  override def foldingFunctions(config: Config) =
    Map("sum" -> KeyValue.sum)

  override def exceptionHandler(config: Config) = VerboselyLoggingExceptionHandler

}