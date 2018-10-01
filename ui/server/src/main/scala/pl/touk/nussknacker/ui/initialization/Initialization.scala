package pl.touk.nussknacker.ui.initialization

import _root_.db.migration.DefaultJdbcProfile
import cats.data.EitherT
import cats.instances.list._
import cats.syntax.traverse._
import com.typesafe.scalalogging.LazyLogging
import db.util.DBIOActionInstances._
import pl.touk.nussknacker.engine.api.deployment.{CustomProcess, ProcessDeploymentData}
import pl.touk.nussknacker.engine.migration.ProcessMigrations
import pl.touk.nussknacker.ui.EspError
import pl.touk.nussknacker.ui.db.entity.EnvironmentsEntity.EnvironmentsEntityData
import pl.touk.nussknacker.ui.db.entity.ProcessEntity.ProcessingType
import pl.touk.nussknacker.ui.db.entity.ProcessEntity.ProcessingType.ProcessingType
import pl.touk.nussknacker.ui.db.entity.ProcessVersionEntity.ProcessVersionEntityData
import pl.touk.nussknacker.ui.db.{DbConfig, EspTables}
import pl.touk.nussknacker.ui.process.migrate.ProcessModelMigrator
import pl.touk.nussknacker.ui.process.repository.ProcessRepository.ProcessDetails
import pl.touk.nussknacker.ui.process.repository.WriteProcessRepository.UpdateProcessAction
import pl.touk.nussknacker.ui.process.repository._
import pl.touk.nussknacker.ui.security.api.{LoggedUser, Permission}
import slick.dbio.DBIOAction

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}


object Initialization {
  private val toukCategory = "Default"

  implicit val toukUser = LoggedUser("Nussknacker", Map(toukCategory->Set(Permission.Write, Permission.Admin)))

  def init(migrations: Map[ProcessingType, ProcessMigrations],
           db: DbConfig,
           environment: String,
           customProcesses: Option[Map[String, String]]) : Unit = {

    val transactionalRepository = new DbWriteProcessRepository[DB](db, migrations.mapValues(_.version)) {
      override def run[R]: (DB[R]) => DB[R] = identity
    }
    val transactionalFetchingRepository = new DBFetchingProcessRepository[DB](db) {
      override def run[R]: (DB[R]) => DB[R] = identity
    }

    val operations : List[InitialOperation] = List(
      new EnvironmentInsert(environment, db),
      new AutomaticMigration(migrations, transactionalRepository, transactionalFetchingRepository)
    ) ++ customProcesses.map(new TechnicalProcessUpdate(_, transactionalRepository, transactionalFetchingRepository))

    runOperationsTransactionally(db, operations)
  }

  private def runOperationsTransactionally(db: DbConfig, operations: List[InitialOperation]) = {

    import db.driver.api._
    val result = operations.map(_.runOperation).sequence
    val runFuture = db.run(result.transactionally)

    Await.result(runFuture, 10 seconds)
  }
}

trait InitialOperation extends LazyLogging {

  def runOperation(implicit ec: ExecutionContext, lu: LoggedUser) : DB[Unit]


}

class EnvironmentInsert(environmentName: String, dbConfig: DbConfig) extends InitialOperation {
  override def runOperation(implicit ec: ExecutionContext, lu: LoggedUser): DB[Unit] = {
    //`insertOrUpdate` in Slick v.3.2.0-M1 seems not to work
    import DefaultJdbcProfile.profile.api._
    val uppsertEnvironmentAction = for {
      alreadyExists <- EspTables.environmentsTable.filter(_.name === environmentName).exists.result
      _ <- if (alreadyExists) {
        DBIO.successful(())
      } else {
        EspTables.environmentsTable += EnvironmentsEntityData(environmentName)
      }
    } yield ()
    uppsertEnvironmentAction
  }
}

class TechnicalProcessUpdate(customProcesses: Map[String, String], repository: DbWriteProcessRepository[DB], fetchingProcessRepository: DBFetchingProcessRepository[DB])
  extends InitialOperation  {

  def runOperation(implicit ec: ExecutionContext, lu: LoggedUser): DB[Unit] = {
    val results: DB[List[Unit]] = customProcesses
      .map { case (processId, processClass) =>
        val deploymentData = CustomProcess(processClass)
        logger.info(s"Saving custom process $processId")
        saveOrUpdate(
          processId = processId,
          category = "Technical",
          deploymentData = deploymentData,
          processingType = ProcessingType.Streaming,
          isSubprocess = false
        )
      }.toList.sequence
    results.map(_ => ())
  }

  private def saveOrUpdate(processId: String, category: String, deploymentData: ProcessDeploymentData,
                           processingType: ProcessingType, isSubprocess: Boolean)(implicit ec: ExecutionContext, lu: LoggedUser): DB[Unit] = {
    (for {
      latestVersion <- EitherT.right[EspError](fetchingProcessRepository.fetchLatestProcessVersion(processId))
      _ <- EitherT {
        latestVersion match {
          case None => repository.saveNewProcess(
            processId = processId,
            category = category,
            processDeploymentData = deploymentData,
            processingType = processingType,
            isSubprocess = isSubprocess
          )
          case Some(version) if version.user == Initialization.toukUser.id =>
            repository.updateProcess(UpdateProcessAction(processId, deploymentData, "External update")).map(_.right.map(_ => ()))
          case _ => logger.info(s"Process $processId not updated. DB version is: \n${latestVersion.flatMap(_.json).getOrElse("")}\n " +
            s" and version from file is: \n$deploymentData")
            DBIOAction.successful(Right(()))
        }
      }
      _ <- EitherT(repository.updateCategory(processId, category))
    } yield ()).value.flatMap {
      case Left(error) => DBIOAction.failed(new RuntimeException(s"Failed to migrate $processId: $error"))
      case Right(()) => DBIOAction.successful(())
    }
  }
}

class AutomaticMigration(migrations: Map[ProcessingType, ProcessMigrations],
                         repository: DbWriteProcessRepository[DB], fetchingProcessRepository: DBFetchingProcessRepository[DB]) extends InitialOperation {

  private val migrator = new ProcessModelMigrator(migrations)

  def runOperation(implicit ec: ExecutionContext, lu: LoggedUser): DB[Unit] = {
    val results : DB[List[Unit]] = for {
      processes <- fetchingProcessRepository.fetchProcessesDetails()
      subprocesses <- fetchingProcessRepository.fetchSubProcessesDetails()
      allToMigrate = processes ++ subprocesses
      migrated <- allToMigrate.map(migrateOne).sequence
    } yield migrated
    results.map(_ => ())
  }

  private def migrateOne(processDetails: ProcessDetails)(implicit ec: ExecutionContext, lu: LoggedUser) : DB[Unit] = {
    migrator.migrateProcess(processDetails).map(_.toUpdateAction) match {
      case Some(action) => repository.updateProcess(action).flatMap {
        case Left(error) => DBIOAction.failed(new RuntimeException(s"Failed to migrate ${processDetails.id}: $error"))
        case Right(_) => DBIOAction.successful(())
      }
      case None => DBIOAction.successful(())
    }
  }
}

