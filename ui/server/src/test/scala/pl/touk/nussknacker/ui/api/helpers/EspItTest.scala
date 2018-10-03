package pl.touk.nussknacker.ui.api.helpers


import akka.actor.ActorRef
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import argonaut.{Json, PrettyParams}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import org.scalatest._
import pl.touk.nussknacker.engine.graph.EspProcess
import pl.touk.nussknacker.ui.api._
import pl.touk.nussknacker.ui.api.helpers.TestFactory._
import pl.touk.nussknacker.ui.db.entity.ProcessEntity.ProcessingType
import pl.touk.nussknacker.ui.process.deployment.ManagementActor
import pl.touk.nussknacker.ui.process.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.ui.process.{JobStatusService, NewProcessPreparer, ProcessToSave, ProcessTypesForCategories}
import pl.touk.nussknacker.ui.processreport.ProcessCounter
import pl.touk.nussknacker.ui.sample.SampleProcess
import pl.touk.nussknacker.ui.security.api.Permission
import cats.syntax.semigroup._
import cats.instances.all._
import pl.touk.nussknacker.engine.management.FlinkModelData
import pl.touk.nussknacker.ui.process.uiconfig.defaults.{DefaultValueExtractorChain, ParamDefaultValueConfig}

trait EspItTest extends LazyLogging with WithDbTesting with TestPermissions { self: ScalatestRouteTest with Suite with BeforeAndAfterEach with Matchers =>

  val env = "test"
  val attachmentsPath = "/tmp/attachments" + System.currentTimeMillis()

  val processRepository = newProcessRepository(db)
  val processAuthorizer = new AuthorizeProcess(processRepository)

  val writeProcessRepository = newWriteProcessRepository(db)
  val subprocessRepository = newSubprocessRepository(db)
  val deploymentProcessRepository = newDeploymentProcessRepository(db)
  val processActivityRepository = newProcessActivityRepository(db)

  val typesForCategories = new ProcessTypesForCategories(ConfigFactory.load())

  val existingProcessingType = ProcessingType.Streaming

  val processManager = new MockProcessManager
  def createManagementActorRef = ManagementActor(env,
    Map(ProcessingType.Streaming -> processManager), processRepository, deploymentProcessRepository, TestFactory.sampleResolver)

  val managementActor: ActorRef = createManagementActorRef
  val jobStatusService = new JobStatusService(managementActor)
  val newProcessPreparer = new NewProcessPreparer(Map(ProcessingType.Streaming -> ProcessTestData.processDefinition))
  val processesRoute = new ProcessesResources(
    repository = processRepository,
    writeRepository = writeProcessRepository,
    jobStatusService = jobStatusService,
    processActivityRepository = processActivityRepository,
    processValidation = processValidation,
    typesForCategories = typesForCategories,
    newProcessPreparer = newProcessPreparer,
    processAuthorizer = processAuthorizer
  )
  val processesExportResources = new ProcessesExportResources(processRepository, processActivityRepository)
  val definitionResources = new DefinitionResources(
    Map(existingProcessingType ->  FlinkModelData(ConfigFactory.load())), subprocessRepository,
    DefaultValueExtractorChain(ParamDefaultValueConfig(Map.empty)), Map.empty, Map.empty
  )

  val processesRouteWithAllPermissions = withAllPermissions(processesRoute)

  val deployRoute = new ManagementResources(
    processCounter = new ProcessCounter(TestFactory.sampleSubprocessRepository),
    managementActor = managementActor,
    testResultsMaxSizeInBytes = 500 * 1024 * 1000,
    processAuthorizer = processAuthorizer
  )
  val attachmentService = new ProcessAttachmentService(attachmentsPath, processActivityRepository)
  val processActivityRoute = new ProcessActivityResource(processActivityRepository)
  val attachmentsRoute = new AttachmentResources(attachmentService)

  def saveProcess(processId: String, process: EspProcess)(testCode: => Assertion): Assertion = {
    Post(s"/processes/$processId/$testCategoryName?isSubprocess=false") ~> processesRouteWithAllPermissions ~> check {
      status shouldBe StatusCodes.Created
      updateProcess(processId, process)(testCode)
    }
  }

  def saveProcess(process: DisplayableProcess)(testCode: => Assertion): Assertion = {
    val processId = process.id
    Post(s"/processes/$processId/$testCategoryName?isSubprocess=false") ~> processesRouteWithAllPermissions ~> check {
      status shouldBe StatusCodes.Created
      updateProcess(process)(testCode)
    }
  }

  def saveSubProcess(process: DisplayableProcess)(testCode: => Assertion): Assertion = {
    val processId = process.id
    Post(s"/processes/$processId/$testCategoryName?isSubprocess=true") ~> processesRouteWithAllPermissions ~> check {
      status shouldBe StatusCodes.Created
      updateProcess(process)(testCode)
    }
  }

  def updateProcess(process: DisplayableProcess)(testCode: => Assertion): Assertion = {
    val processId = process.id
    Put(s"/processes/$processId", TestFactory.posting.toEntityAsProcessToSave(process)) ~> processesRouteWithAllPermissions ~> check {
      testCode
    }
  }

  def updateProcess(process: ProcessToSave)(testCode: => Assertion): Assertion = {
    val processId = process.process.id
    Put(s"/processes/$processId", TestFactory.posting.toEntity(process)) ~> processesRouteWithAllPermissions ~> check {
      testCode
    }
  }

  def updateProcess(processId: String, process: EspProcess)(testCode: => Assertion): Assertion = {
    Put(s"/processes/$processId", TestFactory.posting.toEntityAsProcessToSave(process)) ~> processesRouteWithAllPermissions ~> check {
      testCode
    }
  }

  def saveProcessAndAssertSuccess(processId: String, process: EspProcess): Assertion = {
    saveProcess(processId, process) {
      status shouldEqual StatusCodes.OK
    }
  }

  def updateProcessAndAssertSuccess(processId: String, process: EspProcess): Assertion = {
    updateProcess(processId, process) {
      status shouldEqual StatusCodes.OK
    }
  }


  def deployProcess(id: String): RouteTestResult = {
    Post(s"/processManagement/deploy/$id") ~> withPermissions(deployRoute, testPermissionDeploy |+| testPermissionRead)
  }

  def cancelProcess(id: String ) = {
    Post(s"/processManagement/cancel/$id") ~> withPermissions(deployRoute, testPermissionDeploy |+| testPermissionRead)
  }

  def getSampleProcess = {
    Get(s"/processes/${SampleProcess.process.id}") ~> withPermissions(processesRoute, testPermissionRead)
  }

  def getProcess(processId: String): RouteTestResult = {
    Get(s"/processes/$processId") ~> withPermissions(processesRoute, testPermissionRead)
  }

  def getProcesses = {
    Get(s"/processes") ~> withPermissions(processesRoute, testPermissionRead)
  }

  def getProcessDefinitionData(processingType: ProcessingType.ProcessingType, subprocessVersions: Json) = {
    Post(s"/processDefinitionData/$processingType?isSubprocess=false", toEntity(subprocessVersions)) ~> withPermissions(definitionResources, testPermissionRead)
  }

  def getProcessDefinitionServices() = {
    Get("/processDefinitionData/services") ~> withPermissions(definitionResources, testPermissionRead)
  }

  private def toEntity(json: Json) = {
    val jsonString = json.pretty(PrettyParams.spaces2.copy(dropNullKeys = true, preserveOrder = true))
    HttpEntity(ContentTypes.`application/json`, jsonString)
  }

}
