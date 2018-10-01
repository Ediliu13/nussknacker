package pl.touk.nussknacker.engine.process.functional

import java.util.Date

import org.scalatest.{FlatSpec, FunSuite, Matchers}
import pl.touk.nussknacker.engine.api.{MetaData, StreamMetaData}
import pl.touk.nussknacker.engine.build.EspProcessBuilder
import pl.touk.nussknacker.engine.graph.EspProcess
import pl.touk.nussknacker.engine.graph.exceptionhandler.ExceptionHandlerRef
import pl.touk.nussknacker.engine.graph.node.{EndingNode, Sink, Source, SourceNode}
import pl.touk.nussknacker.engine.graph.sink.SinkRef
import pl.touk.nussknacker.engine.graph.source.SourceRef
import pl.touk.nussknacker.engine.process.ProcessTestHelpers.{MockService, SimpleRecord, processInvoker}
import pl.touk.nussknacker.engine.spel

class ProcessSpec extends FunSuite with Matchers {

  import spel.Implicits._

  test("skip null records") {

    val process = EspProcessBuilder.id("proc1")
      .exceptionHandler()
      .source("id", "input")
      .processorEnd("proc2", "logService", "all" -> "#input")


    val data = List(
      SimpleRecord("1", 3, "a", new Date(0)),
      SimpleRecord("1", 5, "b", new Date(1000)),
      null,
      SimpleRecord("1", 12, "d", new Date(4000)),
      SimpleRecord("1", 14, "d", new Date(10000)),
      null,
      SimpleRecord("1", 20, "d", new Date(10000))

    )

    processInvoker.invoke(process, data)

    MockService.data should have size 5

  }

  test("ignore disabled sinks") {
    val processRoot = SourceNode(
      Source("id", SourceRef("input", List.empty)),
      EndingNode(Sink("out", SinkRef("monitor", List.empty), isDisabled = Some(true)))
    )
    val process = EspProcess(MetaData("", StreamMetaData()), ExceptionHandlerRef(List.empty), processRoot)

    val data = List(
      SimpleRecord("1", 3, "a", new Date(0))
    )

    processInvoker.invoke(process, data)

    MockService.data should have size 0
  }

  test("allow global vars in source config") {

    val process = EspProcessBuilder.id("proc1")
      .exceptionHandler()
      .source("id", "intInputWithParam", "param" -> "#processHelper.add(2, 3)")
      .processorEnd("proc2", "logService", "all" -> "#input")


    val data = List()

    processInvoker.invoke(process, data)

    MockService.data shouldBe List(5)


  }
}
