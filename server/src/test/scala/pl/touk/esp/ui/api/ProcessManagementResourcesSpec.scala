package pl.touk.esp.ui.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{FlatSpec, Matchers, OptionValues}
import pl.touk.esp.ui.api.helpers.TestFactory._
import pl.touk.esp.ui.api.helpers.{DbTesting, TestFactory}
import pl.touk.esp.ui.sample.SampleProcess
import pl.touk.esp.ui.security.Permission

class ProcessManagementResourcesSpec extends FlatSpec with ScalatestRouteTest
  with Matchers with ScalaFutures with OptionValues {

  val db = DbTesting.db
  implicit override val patienceConfig = PatienceConfig(timeout = scaled(Span(2, Seconds)), interval = scaled(Span(100, Millis)))

  val processRepository = newProcessRepository(db)
  val deploymentProcessRepository = newDeploymentProcessRepository(db)

  val route = new ManagementResources(processRepository, deploymentProcessRepository,
    InMemoryMocks.mockProcessManager).route

  it should "save deployed process" in {

    Post(s"/processManagement/deploy/${SampleProcess.process.id}") ~> withPermissions(route, Permission.Deploy) ~> check {
      status shouldEqual StatusCodes.OK
      whenReady(deploymentProcessRepository.fetchDeployedProcessById(SampleProcess.process.id)) { deployedProcess =>
        deployedProcess.value.id shouldBe SampleProcess.process.id
      }
    }
  }

  it should "not authorize user with write permission to deploy" in {
    Post(s"/processManagement/deploy/${SampleProcess.process.id}") ~> withPermissions(route, Permission.Write) ~> check {
      rejection shouldBe server.AuthorizationFailedRejection
    }
  }
}