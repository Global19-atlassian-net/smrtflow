package com.pacbio.secondary.smrtlink.actors

import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import com.google.common.annotations.VisibleForTesting
import com.pacbio.secondary.smrtlink.dependency.Singleton
import com.pacbio.secondary.smrtlink.services.PacBioServiceErrors.{
  ResourceNotFoundError,
  UnprocessableEntityError
}
import com.pacbio.common.models.CommonModelImplicits
import com.pacbio.secondary.smrtlink.analysis.constants.FileTypes
import com.pacbio.secondary.smrtlink.analysis.datasets.DataSetMetaTypes
import com.pacbio.secondary.smrtlink.analysis.datasets.DataSetMetaTypes.DataSetMetaType
import com.pacbio.secondary.smrtlink.analysis.datasets.io.{
  DataSetJsonUtils,
  DataSetLoader
}
import CommonMessages.MessageResponse
import com.pacbio.secondary.smrtlink.analysis.jobs.JobModels._
import com.pacbio.secondary.smrtlink.analysis.jobs._
import com.pacbio.secondary.smrtlink.SmrtLinkConstants
import com.pacbio.secondary.smrtlink.app.SmrtLinkConfigProvider
import com.pacbio.secondary.smrtlink.database.TableModels._
import com.pacbio.secondary.smrtlink.models.{
  EngineConfig,
  ServiceDataSetMetadata,
  _
}
import com.typesafe.scalalogging.LazyLogging
import org.joda.time.{DateTime => JodaDateTime}

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.control.NonFatal
import slick.driver.PostgresDriver.api._
import java.sql.SQLException

import akka.actor.ActorRef
import com.pacbio.common.models.CommonModels.{IdAble, IntIdAble, UUIDIdAble}
import com.pacbio.secondary.smrtlink.analysis.configloaders.ConfigLoader
import com.pacbio.secondary.smrtlink.actors.EventManagerActor.UploadTgz
import com.pacbio.secondary.smrtlink.database.DatabaseConfig
import com.pacificbiosciences.pacbiodatasets._
import org.postgresql.util.PSQLException
import spray.json.JsObject

trait DalProvider {
  val db: Singleton[Database]
  val dbConfig: DatabaseConfig
  // this is duplicated for the cake vs provider model
  val dbConfigSingleton: Singleton[DatabaseConfig] = Singleton(() => dbConfig)
}

trait DbConfigLoader extends ConfigLoader {

  /**
    * Database config to be used within the specs
    *
    * There's a bit of duplication here because the interface for flyway requires a PG Datasource
    * while slick requires a Database.forConfig model.
    * Provided, the root key doesn't change (e.g., smrtflow.test-db), this should be
    * too much of a problem.
    *
    */
  lazy final val dbConfig: DatabaseConfig = {
    val dbName = conf.getString("smrtflow.db.properties.databaseName")
    val user = conf.getString("smrtflow.db.properties.user")
    val password = conf.getString("smrtflow.db.properties.password")
    val port = conf.getInt("smrtflow.db.properties.portNumber")
    val server = conf.getString("smrtflow.db.properties.serverName")
    val maxConnections = conf.getInt("smrtflow.db.numThreads")

    DatabaseConfig(dbName, user, password, server, port, maxConnections)
  }
}

trait SmrtLinkDalProvider extends DalProvider with DbConfigLoader {

  override val db: Singleton[Database] =
    Singleton(() => Database.forConfig("smrtflow.db"))
}

@VisibleForTesting
trait SmrtLinkTestDalProvider extends DalProvider with ConfigLoader {

  /**
    * This Single configuration will be used in all the specs.
    *
    * See comments above about duplication.
    */
  lazy final val dbConfig: DatabaseConfig = {
    val dbName = conf.getString("smrtflow.test-db.properties.databaseName")
    val user = conf.getString("smrtflow.test-db.properties.user")
    val password = conf.getString("smrtflow.test-db.properties.password")
    val port = conf.getInt("smrtflow.test-db.properties.portNumber")
    val server = conf.getString("smrtflow.test-db.properties.serverName")
    val maxConnections = conf.getInt("smrtflow.test-db.numThreads")

    DatabaseConfig(dbName, user, password, server, port, maxConnections)
  }

  override val db: Singleton[Database] = Singleton(() => {
    dbConfig.toDatabase
  })
}

/**
  * SQL Datastore backend configuration and db connection
  */
trait DalComponent extends LazyLogging {
  val db: Database

  // https://www.postgresql.org/docs/9.6/static/errcodes-appendix.html
  final private val integrityConstraintViolationSqlStateCodes =
    Set("23000", "230001", "23502", "23503", "23505", "23514", "23P01")

  def isConstraintViolation(t: Throwable): Boolean = {
    t match {
      case se: SQLException =>
        //logger.debug(s"Is Violation constraint error-code:'${se.getErrorCode}' state:'${se.getSQLState}' ${se.getMessage}")
        integrityConstraintViolationSqlStateCodes contains se.getSQLState
      case _ => false
    }
  }
}

// Need to find a central home for these util funcs
trait DaoFutureUtils {
  def failIfNone[T](message: String): (Option[T] => Future[T]) = {
    case Some(value) => Future.successful(value)
    case _ => Future.failed(new ResourceNotFoundError(message))
  }

  def runFuturesSequentially[T, U](items: TraversableOnce[T])(
      fx: T => Future[U]): Future[List[U]] = {
    items.foldLeft(Future.successful[List[U]](Nil)) { (f, item) =>
      f.flatMap { x =>
        fx(item).map(_ :: x)
      }
    } map (_.reverse)
  }
}

trait EventComponent {
  def sendEventToManager[T](message: T): Unit
}

trait ProjectDataStore extends LazyLogging {
  this: DalComponent with SmrtLinkConstants with DaoFutureUtils =>

  def getProjects(limit: Int = 1000): Future[Seq[Project]] =
    db.run(projects.filter(_.isActive).take(limit).result)

  def getProjectByName(name: String): Future[Project] = {
    val q = projects.filter(_.name === name)

    db.run(q.result.headOption)
      .flatMap(failIfNone(s"Unable to find project '$name'"))
  }

  def getProjectById(projId: Int): Future[Option[Project]] =
    db.run(projects.filter(_.id === projId).result.headOption)

  def createProject(projReq: ProjectRequest): Future[Project] = {
    val now = JodaDateTime.now()
    val proj = Project(-99,
                       projReq.name,
                       projReq.description,
                       ProjectState.CREATED,
                       now,
                       now,
                       isActive = true,
                       grantRoleToAll = projReq.grantRoleToAll.flatMap(_.role))
    val insert = projects returning projects.map(_.id) into (
        (p,
         i) => p.copy(id = i)) += proj
    val fullAction =
      insert.flatMap(proj => setMembersAndDatasets(proj, projReq))
    db.run(fullAction.transactionally)
  }

  def setMembersAndDatasets(proj: Project,
                            projReq: ProjectRequest): DBIO[Project] = {
    // skip updating member/dataset lists if the request doesn't include those
    val updates = List(
      projReq.members.map(setProjectMembers(proj.id, _)),
      projReq.datasets.map(setProjectDatasets(proj.id, _))
    ).flatten

    DBIO.sequence(updates).andThen(DBIO.successful(proj))
  }

  def setProjectMembers(projId: Int,
                        members: Seq[ProjectRequestUser]): DBIO[Unit] =
    DBIO.seq(
      projectsUsers.filter(_.projectId === projId).delete,
      projectsUsers ++= members.map(m => ProjectUser(projId, m.login, m.role))
    )

  def setProjectDatasets(projId: Int, ids: Seq[RequestId]): DBIO[Unit] = {
    val now = JodaDateTime.now()
    val dsIds = ids.map(_.id)
    val jobIdsFromDatasets = for {
      (ejds, ds) <- engineJobsDataSets join dsMetaData2 on (_.datasetUUID === _.uuid)
      if ds.id inSet dsIds
    } yield ejds.jobId

    DBIO.seq(
      // move datasets not in the list of ids back to the general project
      dsMetaData2
        .filter(_.projectId === projId)
        .filterNot(_.id inSet dsIds)
        .map(ds => (ds.projectId, ds.updatedAt))
        .update((GENERAL_PROJECT_ID, now)),
      // move datasets that *are* in the list of IDs into this project
      dsMetaData2
        .filter(_.id inSet dsIds)
        .map(ds => (ds.projectId, ds.updatedAt))
        .update((projId, now)),
      // move analyses that use one of the given input datasets into this project
      engineJobs
        .filter(_.id in jobIdsFromDatasets)
        .map(ej => (ej.projectId, ej.updatedAt))
        .update((projId, now))
    )
  }

  def updateProject(projId: Int,
                    projReq: ProjectRequest): Future[Option[Project]] = {
    val now = JodaDateTime.now()

    // TODO(smcclellan): Lots of duplication here. Is there a better way to do this?
    val update = (projReq.state, projReq.grantRoleToAll) match {
      case (Some(state), Some(role)) =>
        projects
          .filter(_.id === projId)
          .map(p =>
            (p.name, p.state, p.grantRoleToAll, p.description, p.updatedAt))
          .update((projReq.name, state, role.role, projReq.description, now))
      case (None, Some(role)) =>
        projects
          .filter(_.id === projId)
          .map(p => (p.name, p.description, p.grantRoleToAll, p.updatedAt))
          .update((projReq.name, projReq.description, role.role, now))
      case (Some(state), None) =>
        projects
          .filter(_.id === projId)
          .map(p => (p.name, p.state, p.description, p.updatedAt))
          .update((projReq.name, state, projReq.description, now))
      case (None, None) =>
        projects
          .filter(_.id === projId)
          .map(p => (p.name, p.description, p.updatedAt))
          .update((projReq.name, projReq.description, now))
    }

    val fullAction = update.andThen(
      projects.filter(_.id === projId).result.headOption.flatMap {
        case Some(proj) => setMembersAndDatasets(proj, projReq).map(Some(_))
        case None => DBIO.successful(None)
      }
    )

    db.run(fullAction.transactionally)
  }

  def deleteProjectById(projId: Int): Future[Option[Project]] = {
    logger.info(s"Setting isActive=false for project-id $projId")
    val now = JodaDateTime.now()
    db.run(
      DBIO
        .seq(
          projects
            .filter(_.id === projId)
            .map(j => (j.isActive, j.updatedAt))
            .update(false, now),
          // move the datasets from this project into the general project
          dsMetaData2
            .filter(_.projectId === projId)
            .map(ds => (ds.isActive, ds.projectId, ds.updatedAt))
            .update((false, GENERAL_PROJECT_ID, now)),
          engineJobs
            .filter(_.projectId === projId)
            .map(j => (j.isActive, j.updatedAt))
            .update(false, now)
        )
        .andThen(
          projects.filter(_.id === projId).result.headOption
        ))
  }

  def getProjectUsers(projId: Int): Future[Seq[ProjectUser]] =
    db.run(projectsUsers.filter(_.projectId === projId).result)

  def getDatasetsByProject(projId: Int): Future[Seq[DataSetMetaDataSet]] =
    db.run(dsMetaData2.filter(_.projectId === projId).result)

  /**
    * Returns a query that will only contain active projects for which the given user is granted a role.
    *
    * The query is for ordered pairs of (Project, Option[ProjectUser])
    *
    * The user may be granted a role individually by the projectsUsers table, by the project.grantRoleToAll field, or
    * both
    */
  private def userProjectsQuery(
      login: String): Query[(ProjectsT, Rep[Option[ProjectsUsersT]]),
                            (Project, Option[ProjectUser]),
                            Seq] =
    for {
      (p, pu) <- projects.filter(_.isActive) joinLeft projectsUsers.filter(
        _.login === login) on (_.id === _.projectId)
      if pu.isDefined || p.grantRoleToAll.isDefined
    } yield (p, pu)

  /**
    * Given a project and optional projectUser (as might be returned from the query produced by {{{userProjectsQuery}}}),
    * return the granted role with the highest permissions. E.g., if the project grants CAN_VIEW to all users, and the
    * specific user is granted CAN_EDIT, this will return CAN_EDIT.
    *
    * Throws an {{{UnsupportedOperationException}}} if no role is found.
    */
  private def maxRole(
      project: Project,
      projectUser: Option[ProjectUser]): ProjectUserRole.ProjectUserRole =
    try {
      (project.grantRoleToAll.toSet ++ projectUser.map(_.role).toSet).max
    } catch {
      case e: UnsupportedOperationException =>
        throw new IllegalArgumentException(
          s"Project ${project.id} does not grant a role to all users, and no user-specific role found")
    }

  def getUserProjects(login: String): Future[Seq[UserProjectResponse]] = {
    val userProjects = userProjectsQuery(login).result
      .map(_.map(j => UserProjectResponse(maxRole(j._1, j._2), j._1)))

    db.run(userProjects)
  }

  def getUserProjectsDatasets(
      login: String): Future[Seq[ProjectDatasetResponse]] = {
    val userJoin = for {
      (p, pu) <- userProjectsQuery(login)
      d <- dsMetaData2 if p.id === d.projectId
    } yield (p, d, pu)

    val userDatasets = userJoin.result
      .map(_.map(j => ProjectDatasetResponse(j._1, j._2, maxRole(j._1, j._3))))

    db.run(userDatasets)
  }

  def userHasProjectRole(
      login: String,
      projectId: Int,
      roles: Set[ProjectUserRole.ProjectUserRole]): Future[Boolean] = {
    val hasRole = projects
      .filter(_.id === projectId)
      .result
      .flatMap(_.headOption match {
        // If project exists and grants required role to all users, return true
        case Some(p) if p.grantRoleToAll.exists(roles.contains) =>
          DBIO.successful(true)
        // If project exists, but does not grant required role to all users, check user-specific roles
        case Some(p) =>
          projectsUsers
            .filter(pu => pu.login === login && pu.projectId === projectId)
            .result
            .map(_.headOption match {
              // If user has required role, return true
              case Some(pu) if roles.contains(pu.role) => true
              // User does not have required role, return false
              case _ => false
            })
        // Project does not exist, throw ResourceNotFoundError
        case _ =>
          DBIO.failed(
            new ResourceNotFoundError(
              s"No project with found with id $projectId"))
      })

    db.run(hasRole)
  }
}

/**
  * SQL Driven JobEngine datastore Backend
  */
trait JobDataStore extends LazyLogging with DaoFutureUtils {
  this: DalComponent with EventComponent =>

  import CommonModelImplicits._

  private val NO_WORK = NoAvailableWorkError("No Available work to run.")

  val DEFAULT_MAX_DATASET_LIMIT = 5000

  val resolver: JobResourceResolver

  /**
    * Raw Insert of an Engine Job into the system. If the job is in the CREATED state it will be
    * eligible to be run.
    *
    *
    * @param job Engine Job instance
    * @return
    */
  def insertJob(job: EngineJob): Future[EngineJob] = {
    val action = (engineJobs returning engineJobs.map(_.id) into (
        (j,
         i) => j.copy(id = i))) += job
    db.run(action.transactionally)
  }

  def insertEntryPoint(ep: EngineJobEntryPoint): Future[EngineJobEntryPoint] =
    db.run(engineJobsDataSets += ep).map(_ => ep)

  def getJobById(ix: IdAble): Future[EngineJob] =
    db.run(qEngineJobById(ix).result.headOption)
      .flatMap(failIfNone(s"Failed to find Job ${ix.toIdString}"))

  def qEngineJobById(id: IdAble) = {
    id match {
      case IntIdAble(i) => engineJobs.filter(_.id === i)
      case UUIDIdAble(uuid) => engineJobs.filter(_.uuid === uuid)
    }
  }

  val qEngineMultiJobs = engineJobs.filter(_.isMultiJob === true)

  def qEngineMultiJobById(id: IdAble) = {
    id match {
      case IntIdAble(i) => qEngineMultiJobs.filter(_.id === i)
      case UUIDIdAble(uuid) => qEngineMultiJobs.filter(_.uuid === uuid)
    }
  }

  def getMultiJobById(ix: IdAble): Future[EngineJob] =
    db.run(qEngineMultiJobById(ix).result.headOption)
      .flatMap(failIfNone(s"Failed to find Multi-Job ${ix.toIdString}"))

  def getMultiJobChildren(multiJobId: IdAble): Future[Seq[EngineJob]] = {

    val q = multiJobId match {
      case IntIdAble(i) => engineJobs.filter(_.parentMultiJobId === i)
      case UUIDIdAble(u) =>
        for {
          job <- qEngineMultiJobById(multiJobId)
          jobs <- engineJobs.filter(_.parentMultiJobId === job.id).sortBy(_.id)
        } yield jobs
    }
    db.run(q.result)
  }

  /**
    * Get next runnable job.
    *
    * @param isQuick Only select quick job types.
    * @return
    */
  def getNextRunnableEngineCoreJob(isQuick: Boolean = false)
    : Future[Either[NoAvailableWorkError, EngineJob]] = {

    //logger.debug(s"Checking for next runnable isQuick? $isQuick EngineJobs")

    val quickJobTypeIds = JobTypeIds.ALL
      .filter(_.isQuick)
      .map(_.id)
      .toSet

    val q0 = qGetEngineJobByState(AnalysisJobStates.CREATED)
      .filter(_.isMultiJob === false)
    val q = if (isQuick) q0.filter(_.jobTypeId inSet quickJobTypeIds) else q0
    val q1 = q.sortBy(_.id).take(1)

    // This needs to be thought out a bit more. The entire engine job table needs to be locked in a worker-queue model
    // This is using a head call that fail in the caught and recover block
    val fx = for {
      job <- q1.result.head
      _ <- qEngineJobById(job.id)
        .map(j => (j.state, j.updatedAt))
        .update((AnalysisJobStates.SUBMITTED, JodaDateTime.now()))
      _ <- jobEvents += JobEvent(
        UUID.randomUUID(),
        job.id,
        AnalysisJobStates.SUBMITTED,
        s"Updating state to ${AnalysisJobStates.SUBMITTED} (from get-next-job)",
        JodaDateTime.now())
      job <- qEngineJobById(job.id).result.head
    } yield job

    db.run(fx.transactionally)
      .map { engineJob =>
        logger.info(
          s"Found runnable job id:${engineJob.id} type:${engineJob.jobTypeId} in state ${engineJob.state} isQuick:$isQuick")
        Right(engineJob)
      }
      .recover {
        case NonFatal(_) =>
          //logger.debug(s"No available work")
          Left(NO_WORK)
      }
  }

  /**
    * Jobs that are in SUBMITTED, RUNNING state are eligible "runnable" jobs. These jobs will
    * have their state (potentially) updated in an idempotent model.
    *
    * @return
    */
  def getNextRunnableEngineMultiJobs(): Future[Seq[EngineJob]] = {
    val runnableStates: Set[AnalysisJobStates.JobStates] =
      Set(AnalysisJobStates.SUBMITTED, AnalysisJobStates.RUNNING)
    val q =
      qGetEngineJobsByStates(runnableStates).filter(_.isMultiJob === true)
    db.run(q.result)
  }

  /**
    * Get all the Job Events associated with a specific job
    */
  def getJobEventsByJobId(jobId: Int): Future[Seq[JobEvent]] =
    db.run(jobEvents.filter(_.jobId === jobId).result)

  /**
    * Update the State of a Job
    *
    * @param jobId Job Id
    * @param state Job State to be updated to
    * @param message Job progress message message (will be used in the JobEvent)
    * @param errorMessage Optional Error Message. If the state is FAILED, this should be explicitly set to propagate the
    *                     error message.
    * @return
    */
  def updateJobState(
      jobId: IdAble,
      state: AnalysisJobStates.JobStates,
      message: String,
      errorMessage: Option[String] = None): Future[EngineJob] = {

    logger.info(s"Updating job state of job-id ${jobId.toIdString} to $state")
    val now = JodaDateTime.now()

    // The error handling of this .head call needs to be improved
    val xs = for {
      job <- qEngineJobById(jobId).result.head
      _ <- DBIO.seq(
        qEngineJobById(jobId)
          .map(j => (j.state, j.updatedAt, j.errorMessage))
          .update(state, now, errorMessage),
        jobEvents += JobEvent(UUID.randomUUID(), job.id, state, message, now)
      )
      updatedJob <- qEngineJobById(jobId).result.headOption
    } yield updatedJob

    val f: Future[EngineJob] = db
      .run(xs.transactionally)
      .flatMap(failIfNone(s"Failed to find Job ${jobId.toIdString}"))

    f.onSuccess {
      case job: EngineJob =>
        sendEventToManager[JobCompletedMessage](JobCompletedMessage(job))
    }

    f
  }

  def deleteMultiJob(jobId: IdAble): Future[MessageResponse] = {
    logger.info(s"Attempting to delete job ${jobId.toIdString}")

    val q = for {
      job <- qEngineMultiJobById(jobId).result.head
      _ <- jobEvents.filter(_.jobId === job.id).delete
      _ <- qEngineJobById(jobId).delete
    } yield MessageResponse(s"Successfully deleted ${jobId.toIdString}")

    db.run(q.transactionally)
  }

  def updateMultiJob(jobId: IdAble,
                     jsonSetting: JsObject,
                     name: String,
                     description: String,
                     projectId: Int): Future[EngineJob] = {
    val now = JodaDateTime.now()
    logger.info(
      s"Updating multi-job ${jobId.toIdString} job settings ${jsonSetting.prettyPrint.toString}")

    val action = for {
      job <- qEngineJobById(jobId).result.head
      _ <- DBIO.seq(
        qEngineMultiJobById(jobId)
          .map(j =>
            (j.updatedAt, j.jsonSettings, j.name, j.comment, projectId))
          .update(now, jsonSetting.toString(), name, description, projectId)
      )
      updatedJob <- qEngineMultiJobById(jobId).result.head
    } yield updatedJob

    db.run(action.transactionally)
  }

  /**
    *
    * Update the workflow state of the Multi-Job
    */
  def updateMultiJobState(jobId: IdAble,
                          state: AnalysisJobStates.JobStates,
                          workflow: JsObject,
                          message: String,
                          errorMessage: Option[String]): Future[EngineJob] = {
    logger.info(
      s"Updating multi-job state of job-id ${jobId.toIdString} to $state")
    val now = JodaDateTime.now()
    val xs = for {
      job <- qEngineMultiJobById(jobId).result.head
      _ <- DBIO.seq(
        qEngineMultiJobById(jobId)
          .map(j => (j.state, j.updatedAt, j.workflow, j.errorMessage))
          .update(state, now, workflow.toString(), errorMessage),
        jobEvents += JobEvent(UUID.randomUUID(), job.id, state, message, now)
      )
      updatedJob <- qEngineJobById(jobId).result.headOption
    } yield updatedJob

    db.run(xs.transactionally)
      .flatMap(failIfNone(s"Failed to find Job ${jobId.toIdString}"))

  }

  private def insertEngineJob(
      engineJob: EngineJob,
      entryPoints: Seq[EngineJobEntryPointRecord]): Future[EngineJob] = {
    val updates = (engineJobs returning engineJobs.map(_.id) into (
        (j,
         i) => j.copy(id = i)) += engineJob) flatMap { job =>
      val jobId = job.id

      // Using the RunnableJobWithId is a bit clumsy and heavy for such a simple task
      // Resolving Path so we can update the state in the DB
      // Note, this will raise if the path can't be created
      val resolvedPath = resolver.resolve(jobId).toAbsolutePath.toString

      val jobEvent = JobEvent(
        UUID.randomUUID(),
        jobId,
        AnalysisJobStates.CREATED,
        s"Created job $jobId type ${engineJob.jobTypeId} with ${engineJob.uuid.toString}",
        JodaDateTime.now()
      )

      DBIO
        .seq(
          engineJobs
            .filter(_.id === jobId)
            .map(_.path)
            .update(resolvedPath.toString),
          jobEvents += jobEvent,
          engineJobsDataSets ++= entryPoints.map(e =>
            EngineJobEntryPoint(jobId, e.datasetUUID, e.datasetType))
        )
        .map(_ => engineJob.copy(id = jobId, path = resolvedPath.toString))
    }

    val action =
      projects.filter(_.id === engineJob.projectId).exists.result.flatMap {
        case true => updates
        case false =>
          DBIO.failed(
            new UnprocessableEntityError(
              s"Project id ${engineJob.projectId} does not exist"))
      }

    val f = db.run(action.transactionally)

    // Need to send an event to EngineManager to Check for work
    f onSuccess { case engineJob: EngineJob => sendEventToManager(engineJob) }

    f
  }

  def createMultiJob(
      uuid: UUID,
      name: String,
      description: String,
      jobTypeId: JobTypeIds.JobType,
      entryPoints: Seq[EngineJobEntryPointRecord] =
        Seq.empty[EngineJobEntryPointRecord],
      jsonSetting: JsObject,
      createdBy: Option[String] = None,
      createdByEmail: Option[String] = None,
      smrtLinkVersion: Option[String] = None,
      projectId: Int = JobConstants.GENERAL_PROJECT_ID,
      workflow: JsObject = JsObject.empty): Future[EngineJob] = {
    val path = ""
    val createdAt = JodaDateTime.now()

    val engineJob = EngineJob(
      -1,
      uuid,
      name,
      description,
      createdAt,
      createdAt,
      AnalysisJobStates.CREATED,
      jobTypeId.id,
      path,
      jsonSetting.toString(),
      createdBy,
      createdByEmail,
      smrtLinkVersion,
      projectId = projectId,
      parentMultiJobId = None,
      isMultiJob = true,
      workflow = workflow.toString()
    )

    insertEngineJob(engineJob, entryPoints)
  }

  /** New Actor-less model **/
  def createCoreJob(
      uuid: UUID,
      name: String,
      description: String,
      jobTypeId: JobTypeIds.JobType,
      entryPoints: Seq[EngineJobEntryPointRecord] =
        Seq.empty[EngineJobEntryPointRecord],
      jsonSetting: JsObject,
      createdBy: Option[String] = None,
      createdByEmail: Option[String] = None,
      smrtLinkVersion: Option[String] = None,
      projectId: Int = JobConstants.GENERAL_PROJECT_ID,
      parentMultiJobId: Option[Int] = None,
      importedAt: Option[JodaDateTime] = None): Future[EngineJob] = {

    val path = ""
    val createdAt = JodaDateTime.now()

    val engineJob = EngineJob(
      -1,
      uuid,
      name,
      description,
      createdAt,
      createdAt,
      AnalysisJobStates.CREATED,
      jobTypeId.id,
      path,
      jsonSetting.toString(),
      createdBy,
      createdByEmail,
      smrtLinkVersion,
      projectId = projectId,
      parentMultiJobId = parentMultiJobId,
      importedAt = importedAt
    )

    insertEngineJob(engineJob, entryPoints)
  }

  /**
    * Import a job from another SMRT Link system.
    * @param job EngineJob from exported manifest
    * @param parentJob the import-job job being run
    * @param entryPoints entry point records
    * @return new EngineJob object for the imported job
    */
  def importRawEngineJob(
      job: EngineJob,
      parentJob: EngineJob,
      entryPoints: Seq[EngineJobEntryPointRecord] =
        Seq.empty[EngineJobEntryPointRecord]): Future[EngineJob] = {
    val importedJob = job.copy(id = -1,
                               path = "",
                               projectId = parentJob.projectId,
                               importedAt = Some(JodaDateTime.now()))
    insertEngineJob(importedJob, entryPoints)
  }

  def addJobEvent(jobEvent: JobEvent): Future[JobEvent] =
    db.run(jobEvents += jobEvent).map(_ => jobEvent)

  def addJobEvents(events: Seq[JobEvent]): Future[Seq[JobEvent]] =
    db.run(jobEvents ++= events).map(_ => events)

  def getJobEvents: Future[Seq[JobEvent]] = db.run(jobEvents.result)

  def addJobTask(jobTask: JobTask): Future[JobTask] = {
    // when pbsmrtpipe has parity with the AnalysisJobStates, JobTask should have state:AnalysisJobState
    val state = AnalysisJobStates
      .toState(jobTask.state)
      .getOrElse(AnalysisJobStates.UNKNOWN)
    val errorMessage = s"Failed to insert JobEvent from JobTask $jobTask"
    val message =
      s"Creating task ${jobTask.name} id:${jobTask.taskId} type:${jobTask.taskTypeId} State:${jobTask.state}"

    val jobEvent = JobEvent(jobTask.uuid,
                            jobTask.jobId,
                            state,
                            message,
                            jobTask.createdAt,
                            eventTypeId =
                              JobConstants.EVENT_TYPE_JOB_TASK_STATUS)

    val fx = for {
      _ <- jobTasks += jobTask
      _ <- jobEvents += jobEvent
      task <- jobTasks.filter(_.uuid === jobTask.uuid).result
    } yield task

    db.run(fx.transactionally)
      .map(_.headOption)
      .flatMap(failIfNone(errorMessage))
  }

  /**
    * Update the state of a Job Task and create an JobEvent
    *
    * @param update Task Update record
    * @return
    */
  def updateJobTask(update: UpdateJobTask): Future[JobTask] = {
    // Need to sync the pbsmrtpipe task states with the allowed JobStates
    val taskState = AnalysisJobStates
      .toState(update.state)
      .getOrElse(AnalysisJobStates.UNKNOWN)
    val now = JodaDateTime.now()

    val futureFailMessage =
      s"Unable to find JobTask uuid:${update.uuid} for Job id ${update.jobId}"

    val fx = for {
      _ <- jobTasks
        .filter(_.uuid === update.uuid)
        .filter(_.jobId === update.jobId)
        .map((x) => (x.state, x.errorMessage, x.updatedAt))
        .update((update.state, update.errorMessage, now))
      _ <- jobEvents += JobEvent(UUID.randomUUID(),
                                 update.jobId,
                                 taskState,
                                 update.message,
                                 now,
                                 JobConstants.EVENT_TYPE_JOB_TASK_STATUS)
      jobTask <- jobTasks.filter(_.uuid === update.uuid).result
    } yield jobTask

    db.run(fx.transactionally)
      .map(_.headOption)
      .flatMap(failIfNone(futureFailMessage))
  }

  /**
    * Get all tasks associated with a specific EngineJob
    *
    * Will fail if the job is not found, or will return an empty
    * list of Tasks if none are found.
    *
    * @param ix Int or UUID of Engine Job
    * @return
    */
  def getJobTasks(ix: IdAble): Future[Seq[JobTask]] = {
    ix match {
      case IntIdAble(i) =>
        db.run(jobTasks.filter(_.jobId === i).result)
      case UUIDIdAble(uuid) =>
        getJobById(uuid).flatMap(job =>
          db.run(jobTasks.filter(_.jobId === job.id).result))
    }
  }

  def getJobTask(taskId: UUID): Future[JobTask] = {
    val errorMessage = s"Can't find job task $taskId"
    db.run(jobTasks.filter(_.uuid === taskId).result)
      .map(_.headOption)
      .flatMap(failIfNone(errorMessage))
  }

  // TODO(smcclellan): limit is never used. add `.take(limit)`?
  def getEngineCoreJobs(
      limit: Int = 100,
      includeInactive: Boolean = false): Future[Seq[EngineJob]] = {
    if (!includeInactive)
      db.run(engineJobs.filter(_.isActive).sortBy(_.id.desc).result)
    else db.run(engineJobs.sortBy(_.id.desc).result)
  }

  def getEngineMultiJobs(
      limit: Int = 100,
      includeInactive: Boolean = false): Future[Seq[EngineJob]] = {
    val q0 = qEngineMultiJobs
    val q1 = if (!includeInactive) q0.filter(_.isActive) else q0
    db.run(q1.sortBy(_.id.desc).result)
  }

  def getJobsByTypeId(
      jobTypeId: String,
      includeInactive: Boolean = false,
      projectId: Option[Int] = None): Future[Seq[EngineJob]] = {
    val q1 = engineJobs.filter(_.jobTypeId === jobTypeId).sortBy(_.id.desc)
    val q2 = if (!includeInactive) q1.filter(_.isActive) else q1
    val q3 =
      if (projectId.isDefined) q2.filter(_.projectId === projectId.get) else q2
    db.run(q3.result)
  }

  def getJobEntryPoints(jobId: Int): Future[Seq[EngineJobEntryPoint]] =
    db.run(engineJobsDataSets.filter(_.jobId === jobId).result)

  def deleteJobById(jobId: IdAble): Future[EngineJob] = {
    logger.info(s"Setting isActive=false for job-id ${jobId.toIdString}")
    val now = JodaDateTime.now()
    db.run(for {
        _ <- qEngineJobById(jobId)
          .map(j => (j.isActive, j.updatedAt))
          .update(false, now)
        job <- qEngineJobById(jobId).result.headOption
      } yield job)
      .flatMap(failIfNone(
        s"Unable to Delete job. Unable to find job id ${jobId.toIdString}"))
  }
}

/**
  * Model extend DataSet Component to have 'extended' importing of datastore files by file type
  * (e.g., DataSet, Report)
  *
  * Mixin the Job Component because the files depended on the job
  */
trait DataSetStore extends DaoFutureUtils with LazyLogging {
  this: EventComponent with JobDataStore with DalComponent =>

  val DEFAULT_PROJECT_ID = 1
  val DEFAULT_USER_ID = 1
  import CommonModelImplicits._

  /**
    * Import a DataStoreJob File
    *
    * All of these insert/add methods should return the entity that was inserted.
    *
    * @param ds DataStore Job file
    * @return
    */
  def addDataStoreFile(ds: DataStoreJobFile): Future[MessageResponse] = {
    logger.info(s"adding datastore file for $ds")
    getJobById(ds.jobId).flatMap(engineJob =>
      importDataStoreFile(ds.dataStoreFile, engineJob.uuid))
  }

  /**
    * Get DataStoreService Files
    *
    * In practice, this would return a list that is very large
    *
    * @param ignoreInactive Ignore Inactive files
    * @return
    */
  def getDataStoreFiles(
      ignoreInactive: Boolean = true): Future[Seq[DataStoreServiceFile]] = {
    val q = if (ignoreInactive) {
      datastoreServiceFiles.filter(_.isActive)
    } else {
      datastoreServiceFiles
    }
    db.run(q.result)
  }

  def getDataStoreFileByUUID(uuid: UUID): Future[DataStoreServiceFile] =
    db.run(datastoreServiceFiles.filter(_.uuid === uuid).result.headOption)
      .flatMap(failIfNone(s"Unable to find DataStore File with uuid `$uuid`"))

  def qDatastoreServiceFilesByJobId(id: IdAble) = {
    id match {
      case IntIdAble(i) => datastoreServiceFiles.filter(_.jobId === i)
      case UUIDIdAble(uuid) => datastoreServiceFiles.filter(_.jobUUID === uuid)
    }
  }

  def getDataStoreServiceFilesByJobId(
      i: IdAble): Future[Seq[DataStoreServiceFile]] =
    db.run(qDatastoreServiceFilesByJobId(i).result)

  def getDataStoreReportFilesByJobId(
      jobId: IdAble): Future[Seq[DataStoreReportFile]] =
    db.run {
        qDatastoreServiceFilesByJobId(jobId)
          .filter(_.fileTypeId === FileTypes.REPORT.fileTypeId)
          .result
      }
      .map(_.map((d: DataStoreServiceFile) =>
        DataStoreReportFile(d, d.sourceId.split("-").head)))

  // Return the contents of the Report. THis should really return Future[JsObject]
  def getDataStoreReportByUUID(reportUUID: UUID): Future[String] = {
    val action = datastoreServiceFiles
      .filter(_.uuid === reportUUID)
      .result
      .headOption
      .map {
        case Some(x) =>
          if (Files.exists(Paths.get(x.path))) {
            Option(scala.io.Source.fromFile(x.path).mkString)
          } else {
            logger.error(s"Unable to find report ${x.uuid} path ${x.path}")
            None
          }
        case None => None
      }
    db.run(action)
      .flatMap(failIfNone(s"Unable to find report with id $reportUUID"))
  }

  def qDsMetaDataById(id: IdAble) = {
    id match {
      case IntIdAble(i) => dsMetaData2.filter(_.id === i)
      case UUIDIdAble(uuid) => dsMetaData2.filter(_.uuid === uuid)
    }
  }

  val qDsMetaDataIsActive = dsMetaData2.filter(_.isActive)

  /**
    * Get the Base PacBioDataSet data for DataSets imported into the system
    * @param limit Maximum number of returned results
    * @return
    */
  def getDataSetMetas(
      limit: Option[Int] = None,
      activity: Option[Boolean]): Future[Seq[DataSetMetaDataSet]] = {
    val qActive = activity
      .map(activity => dsMetaData2.filter(_.isActive === activity))
      .getOrElse(dsMetaData2)
    val q = limit.map(x => qActive.take(x)).getOrElse(qActive)
    db.run(q.sortBy(_.id).result)
  }

  /**
    * Update the
    * @param ids DataSetMetaSets that are to marked as InActive.
    * @return
    */
  def updatedDataSetMetasAsInActive(ids: Set[Int]): Future[MessageResponse] = {
    val q = qDsMetaDataIsActive
      .filter(_.id inSet ids)
      .map(d => (d.isActive, d.updatedAt))
      .update((false, JodaDateTime.now()))
    // Is there a better way to do this?
    db.run(q.map(_ =>
      MessageResponse(s"Marked ${ids.size} MetaDataSet as inActive")))
  }

  private def getDataSetMetaDataSet(
      id: IdAble): Future[Option[DataSetMetaDataSet]] =
    db.run(qDsMetaDataById(id).result.headOption)

  def getDataSetMetaData(id: IdAble): Future[DataSetMetaDataSet] =
    getDataSetMetaDataSet(id).flatMap(
      failIfNone(s"Unable to find dataset with ID ${id.toIdString}"))

  // removes a query that seemed like it was potentially nested based on race condition with executor
  private def getDataSetMetaDataSetBlocking(
      uuid: UUID): Option[DataSetMetaDataSet] =
    Await.result(getDataSetMetaDataSet(uuid), 23456 milliseconds)

  private def insertMetaData(ds: ServiceDataSetMetadata)
    : DBIOAction[Int, NoStream, Effect.Read with Effect.Write] = {
    val createdAt = JodaDateTime.now()
    val modifiedAt = createdAt
    dsMetaData2 returning dsMetaData2.map(_.id) += DataSetMetaDataSet(
      -999,
      ds.uuid,
      ds.name,
      ds.path,
      createdAt,
      modifiedAt,
      ds.numRecords,
      ds.totalLength,
      ds.tags,
      ds.version,
      ds.comments,
      ds.md5,
      ds.createdBy,
      ds.jobId,
      ds.projectId,
      isActive = true,
      parentUuid = ds.parentUuid
    )
  }
  // Util func for composing the composition of loading the dataset and translation to
  // a necessary file formats.
  // Note, DsServiceJobFile has projectId, jobId is used to populated
  // the ServiceDataSetMetadata
  private def loadImportAbleServiceFileLoader[T <: DataSetType,
                                              X <: ServiceDataSetMetadata,
                                              Y <: ImportAbleServiceFile](
      loader: (Path => T),
      converter: ((T, Path, Option[String], Int, Int) => X),
      g: ((DsServiceJobFile, X) => Y))(dsj: DsServiceJobFile): Y = {
    val path = Paths.get(dsj.file.path)
    val dataset = loader(path)

    logger.debug(
      s"Converting DataSet${dataset.getUniqueId} with projectId ${dsj.projectId}")
    val sds = converter(dataset,
                        path.toAbsolutePath,
                        dsj.createdBy,
                        dsj.file.jobId,
                        dsj.projectId)
    logger.debug(
      s"Converted DataSet id:${sds.id} uuid:${sds.uuid} jobId:${sds.jobId} projectId:${sds.projectId}")

    g(dsj, sds)
  }

  def loadImportAbleSubreadSet(dsj: DsServiceJobFile): ImportAbleSubreadSet =
    loadImportAbleServiceFileLoader[SubreadSet,
                                    SubreadServiceDataSet,
                                    ImportAbleSubreadSet](
      DataSetLoader.loadSubreadSet,
      Converters.convertSubreadSet,
      ImportAbleSubreadSet.apply)(dsj)

  def loadImportAbleHdfSubreadSet(
      dsj: DsServiceJobFile): ImportAbleHdfSubreadSet =
    loadImportAbleServiceFileLoader[HdfSubreadSet,
                                    HdfSubreadServiceDataSet,
                                    ImportAbleHdfSubreadSet](
      DataSetLoader.loadHdfSubreadSet,
      Converters.convertHdfSubreadSet,
      ImportAbleHdfSubreadSet.apply)(dsj)

  def loadImportAbleAlignmentSet(
      dsj: DsServiceJobFile): ImportAbleAlignmentSet =
    loadImportAbleServiceFileLoader[AlignmentSet,
                                    AlignmentServiceDataSet,
                                    ImportAbleAlignmentSet](
      DataSetLoader.loadAlignmentSet,
      Converters.convertAlignmentSet,
      ImportAbleAlignmentSet.apply)(dsj)

  def loadImportAbleReferenceSet(
      dsj: DsServiceJobFile): ImportAbleReferenceSet =
    loadImportAbleServiceFileLoader[ReferenceSet,
                                    ReferenceServiceDataSet,
                                    ImportAbleReferenceSet](
      DataSetLoader.loadReferenceSet,
      Converters.convertReferenceSet,
      ImportAbleReferenceSet.apply)(dsj)

  def loadImportAbleBarcodeSet(dsj: DsServiceJobFile): ImportAbleBarcodeSet =
    loadImportAbleServiceFileLoader[BarcodeSet,
                                    BarcodeServiceDataSet,
                                    ImportAbleBarcodeSet](
      DataSetLoader.loadBarcodeSet,
      Converters.convertBarcodeSet,
      ImportAbleBarcodeSet.apply)(dsj)

  def loadImportAbleConsensusReadSet(
      dsj: DsServiceJobFile): ImportAbleConsensusReadSet =
    loadImportAbleServiceFileLoader[ConsensusReadSet,
                                    ConsensusReadServiceDataSet,
                                    ImportAbleConsensusReadSet](
      DataSetLoader.loadConsensusReadSet,
      Converters.convertConsensusReadSet,
      ImportAbleConsensusReadSet.apply)(dsj)

  def loadImportAbleConsensusAlignmentSet(
      dsj: DsServiceJobFile): ImportAbleConsensusAlignmentSet =
    loadImportAbleServiceFileLoader[ConsensusAlignmentSet,
                                    ConsensusAlignmentServiceDataSet,
                                    ImportAbleConsensusAlignmentSet](
      DataSetLoader.loadConsensusAlignmentSet,
      Converters.convertConsensusAlignmentSet,
      ImportAbleConsensusAlignmentSet.apply)(dsj)

  def loadImportAbleContigSet(dsj: DsServiceJobFile): ImportAbleContigSet =
    loadImportAbleServiceFileLoader[ContigSet,
                                    ContigServiceDataSet,
                                    ImportAbleContigSet](
      DataSetLoader.loadContigSet,
      Converters.convertContigSet,
      ImportAbleContigSet.apply)(dsj)

  def loadImportAbleGmapReferenceSet(
      dsj: DsServiceJobFile): ImportAbleGmapReferenceSet =
    loadImportAbleServiceFileLoader[GmapReferenceSet,
                                    GmapReferenceServiceDataSet,
                                    ImportAbleGmapReferenceSet](
      DataSetLoader.loadGmapReferenceSet,
      Converters.convertGmapReferenceSet,
      ImportAbleGmapReferenceSet.apply)(dsj)

  /**
    * def loadImportAbleFile(file: DataStoreFile): ImportAbleFile
    * def importImportAbleFile(file: ImportAbleFile): Future[MessageResponse]]
    *
    * 1. val files = Seq[DataStoreFile]
    * 2. val serviceFiles = Seq[DataStoreServiceFile] (turn into DataStore ServiceFile)
    * 3. val importAbleFiles: Seq[ImportAbleFile] = files.map(loadImportAbleFile) // Load from filesystem
    * 4. val xs = Future.sequence(importableFiles.map(file => importImportAbleFile(file))): Future[Seq[MessageResponse]] // import into DB
    */
  def loadImportAbleFile[T >: ImportAbleServiceFile](
      dsj: DsServiceJobFile): T = {
    DataSetMetaTypes
      .fromString(dsj.file.fileTypeId)
      .map {
        case DataSetMetaTypes.Subread => loadImportAbleSubreadSet(dsj)
        case DataSetMetaTypes.HdfSubread =>
          loadImportAbleHdfSubreadSet(dsj)
        case DataSetMetaTypes.Alignment => loadImportAbleAlignmentSet(dsj)
        case DataSetMetaTypes.Barcode => loadImportAbleBarcodeSet(dsj)
        case DataSetMetaTypes.CCS => loadImportAbleConsensusReadSet(dsj)
        case DataSetMetaTypes.AlignmentCCS =>
          loadImportAbleConsensusAlignmentSet(dsj)
        case DataSetMetaTypes.Contig => loadImportAbleContigSet(dsj)
        case DataSetMetaTypes.Reference => loadImportAbleReferenceSet(dsj)
        case DataSetMetaTypes.GmapReference =>
          loadImportAbleGmapReferenceSet(dsj)
      }
      .getOrElse(ImportAbleDataStoreFile(dsj))
  }

  def importSimpleDataStoreFile(
      f: ImportAbleDataStoreFile): Future[MessageResponse] = {

    val ds = f.ds.file
    val action0 = datastoreServiceFiles += ds

    val ax =
      datastoreServiceFiles
        .filter(_.uuid === ds.uuid)
        .exists
        .result
        .flatMap {
          case true =>
            DBIO.successful(MessageResponse(
              s"DataStoreFile ${ds.uuid} ${ds.fileTypeId} already exists. File:${ds.path}"))
          case false => action0
        }

    db.run(ax.transactionally)
      .map(
        _ =>
          MessageResponse(
            s"Successfully imported DSF ${ds.uuid} type:${ds.fileTypeId}"))
  }

  type U = slick.profile.FixedSqlAction[Int,
                                        slick.dbio.NoStream,
                                        slick.dbio.Effect.Write]

  private def insertSubreadSetRecord(dsId: Int, ds: SubreadServiceDataSet)
    : DBIOAction[Int, NoStream, Effect.Read with Effect.Write] = {

    dsSubread2 returning dsSubread2.map(_.id) forceInsert SubreadServiceSet(
      dsId,
      ds.uuid,
      ds.cellId,
      ds.metadataContextId,
      ds.wellSampleName,
      ds.wellName,
      ds.bioSampleName,
      ds.cellIndex,
      ds.instrumentName,
      ds.instrumentName,
      ds.runName,
      ds.instrumentControlVersion,
      ds.dnaBarcodeName
    )
  }

  private def insertHdfSubreadSetRecord(dsId: Int,
                                        ds: HdfSubreadServiceDataSet)
    : DBIOAction[Int, NoStream, Effect.Read with Effect.Write] = {
    dsHdfSubread2 forceInsert HdfSubreadServiceSet(
      dsId,
      ds.uuid,
      "cell-id",
      ds.metadataContextId,
      ds.wellSampleName,
      ds.wellName,
      ds.bioSampleName,
      ds.cellIndex,
      ds.instrumentName,
      ds.instrumentName,
      ds.runName,
      "instrument-ctr-version"
    )
  }

  private def checkForServiceMetaData(
      ds: DataStoreServiceFile,
      fileType: String,
      action: DBIO[Unit]): DBIO[MessageResponse] = {
    dsMetaData2.filter(_.uuid === ds.uuid).exists.result.flatMap {
      case false =>
        logger.info(
          s"Job id:${ds.jobId} Attempting to import DataStorefile/$fileType uud:${ds.uuid} path:${ds.path}")
        action.map(
          _ =>
            MessageResponse(
              s"Job id:${ds.jobId} DataStoreFile ${ds.uuid} already exists"))
      case true =>
        logger.info(
          s"Job id:${ds.jobId} DataStore file already imported. $fileType uuid:${ds.uuid} path:${ds.path}")
        DBIO.successful(
          MessageResponse(
            s"Job id:${ds.jobId} DataStoreFile ${ds.uuid} already exists"))
    }
  }

  /**
    *
    * The motivation for this is very unclear. This means a datastore file will potentially have the
    * the wrong job id. If the concurrency issues are fixed, I believe this should fail, not skip
    * the importing.
    *
    * This has three steps:
    *
    * 0  Precheck. Check if dataset meta exists and datastore file exists (see comments above)
    * 1. Import DataSetMeta
    * 2. Import Specific DataSet
    * 3. Import DataStoreFile
    */
  def importSubreadSet(i: ImportAbleSubreadSet): Future[MessageResponse] = {
    val ds = i.file
    val action0 = insertMetaData(i.file)
      .flatMap(i => insertSubreadSetRecord(i, ds))

    val action: DBIO[Unit] = DBIO.seq(
      action0,
      datastoreServiceFiles += i.ds.file
    )

    val ax = checkForServiceMetaData(i.ds.file, i.ds.file.fileTypeId, action)

    db.run(ax.transactionally)
  }

  def importHdfSubreadSet(
      i: ImportAbleHdfSubreadSet): Future[MessageResponse] = {
    val ds = i.file

    val action0 = insertMetaData(i.file)
      .flatMap(i => insertHdfSubreadSetRecord(i, ds))

    val action = DBIO.seq(
      action0,
      datastoreServiceFiles += i.ds.file
    )

    val ax = checkForServiceMetaData(i.ds.file, i.ds.file.fileTypeId, action)
    db.run(ax.transactionally)
  }

  def importAlignmentSet(i: ImportAbleAlignmentSet): Future[MessageResponse] = {
    val ds = i.file

    val action0 = insertMetaData(i.file).map { id: Int =>
      dsAlignment2 forceInsert AlignmentServiceSet(id, ds.uuid)
    }

    val action = DBIO.seq(
      action0,
      datastoreServiceFiles += i.ds.file
    )

    val ax = checkForServiceMetaData(i.ds.file, i.ds.file.fileTypeId, action)

    db.run(ax.transactionally)
  }

  def importImportAbleBarcodeSet(
      i: ImportAbleBarcodeSet): Future[MessageResponse] = {
    val ds = i.file

    val action0 = insertMetaData(i.file).flatMap { id =>
      dsBarcode2 forceInsert BarcodeServiceSet(id, ds.uuid)
    }

    val action = DBIO.seq(
      action0,
      datastoreServiceFiles += i.ds.file
    )

    val ax = checkForServiceMetaData(i.ds.file, i.ds.file.fileTypeId, action)
    db.run(ax.transactionally)
  }

  def importImportAbleConsensusReadSet(
      i: ImportAbleConsensusReadSet): Future[MessageResponse] = {
    val ds = i.file

    val action0 = insertMetaData(i.file).map { id: Int =>
      dsCCSread2 forceInsert ConsensusReadServiceSet(id, ds.uuid)
    }

    val action = DBIO.seq(action0, datastoreServiceFiles += i.ds.file)

    val ax = checkForServiceMetaData(i.ds.file, i.ds.file.fileTypeId, action)
    db.run(ax.transactionally)
  }

  def importImportAbleConsensusAlignmentSet(
      i: ImportAbleConsensusAlignmentSet): Future[MessageResponse] = {
    val ds = i.file

    val action0 = insertMetaData(i.file).map { id: Int =>
      dsCCSAlignment2 forceInsert ConsensusAlignmentServiceSet(id, ds.uuid)
    }

    val action = DBIO.seq(action0, datastoreServiceFiles += i.ds.file)

    val ax = checkForServiceMetaData(i.ds.file, i.ds.file.fileTypeId, action)
    db.run(ax.transactionally)
  }

  def importImportAbleContigSet(
      i: ImportAbleContigSet): Future[MessageResponse] = {
    val ds = i.ds.file

    val action0 = insertMetaData(i.file).map { id: Int =>
      dsContig2 forceInsert ContigServiceSet(id, ds.uuid)
    }

    val action = DBIO.seq(action0, datastoreServiceFiles += ds)

    val ax = checkForServiceMetaData(i.ds.file, i.ds.file.fileTypeId, action)
    db.run(ax.transactionally)
  }

  def importReferenceSet(i: ImportAbleReferenceSet): Future[MessageResponse] = {
    val ds = i.ds.file
    val action0 = insertMetaData(i.file).flatMap { id =>
      dsReference2 forceInsert ReferenceServiceSet(id,
                                                   i.file.uuid,
                                                   i.file.ploidy,
                                                   i.file.organism)
    }

    val action = DBIO.seq(action0, datastoreServiceFiles += ds)

    val ax = checkForServiceMetaData(i.ds.file, i.ds.file.fileTypeId, action)
    db.run(ax.transactionally)
  }

  def importGmapReferenceSet(
      i: ImportAbleGmapReferenceSet): Future[MessageResponse] = {
    val ds = i.ds.file
    val action0 = insertMetaData(i.file).map { id: Int =>
      dsGmapReference2 forceInsert GmapReferenceServiceSet(id,
                                                           i.file.uuid,
                                                           i.file.ploidy,
                                                           i.file.organism)
    }

    val action = DBIO.seq(action0, datastoreServiceFiles += ds)

    val ax = checkForServiceMetaData(i.ds.file, i.ds.file.fileTypeId, action)
    db.run(ax.transactionally)
  }

  private def importImportAbleFile[T >: ImportAbleServiceFile](
      f: T): Future[MessageResponse] = {
    f match {
      case x: ImportAbleDataStoreFile => importSimpleDataStoreFile(x)
      case x: ImportAbleSubreadSet => importSubreadSet(x)
      case x: ImportAbleHdfSubreadSet => importHdfSubreadSet(x)
      case x: ImportAbleAlignmentSet => importAlignmentSet(x)
      case x: ImportAbleBarcodeSet => importImportAbleBarcodeSet(x)
      case x: ImportAbleConsensusReadSet =>
        importImportAbleConsensusReadSet(x)
      case x: ImportAbleConsensusAlignmentSet =>
        importImportAbleConsensusAlignmentSet(x)
      case x: ImportAbleContigSet => importImportAbleContigSet(x)
      case x: ImportAbleReferenceSet => importReferenceSet(x)
      case x: ImportAbleGmapReferenceSet => importGmapReferenceSet(x)
    }
  }

  private def toDataStoreServiceFile(f: DataStoreFile,
                                     jobId: Int,
                                     jobUUID: UUID,
                                     isActive: Boolean) = {
    val now = JodaDateTime.now()
    DataStoreServiceFile(f.uniqueId,
                         f.fileTypeId,
                         f.sourceId,
                         f.fileSize,
                         f.createdAt,
                         f.modifiedAt,
                         now,
                         f.path,
                         jobId,
                         jobUUID,
                         f.name,
                         f.description,
                         isActive)
  }

  def andLog(sx: String): Future[String] = Future {
    logger.info(sx)
    sx
  }

  /**
    *
    * THIS IS THE NEW PUBLIC INTERFACE THAT SHOULD BE USED from the Job interface to
    * import datastore files.
    *
    * With regards to how the project is is propagated, there's a bit of disconnect
    * in the models. If an explicit project id is not passed in, the project id
    * of the companion job (from the jobUUID) project id will be used.
    *
    */
  def importDataStoreFiles(
      files: Seq[DataStoreFile],
      jobId: UUID,
      projectId: Option[Int] = None): Future[Seq[MessageResponse]] = {
    for {
      job <- getJobById(jobId)
      serviceFiles <- Future.successful(files.map(f =>
        toDataStoreServiceFile(f, job.id, job.uuid, isActive = true)))
      _ <- andLog(
        s"Attempting to import datastore with ${serviceFiles.length} files for job ${job.id}")
      importAbleFiles <- Future.successful(
        serviceFiles.map(
          dsf =>
            loadImportAbleFile(
              DsServiceJobFile(dsf,
                               job.createdBy,
                               projectId.getOrElse(job.projectId)))))
      messages <- Future.sequence(importAbleFiles.map(importImportAbleFile))
      _ <- andLog(
        s"Successfully import datastore with import-able (${importAbleFiles.length} files) for job ${job.id}")
    } yield messages
  }

  /**
    * PUBLIC INTERFACE THAT SHOULD BE USED for importing a Single DataStoreFile
    *
    * See comments above with regards to the project id propagation.
    */
  def importDataStoreFile(
      file: DataStoreFile,
      jobId: UUID,
      projectId: Option[Int] = None): Future[MessageResponse] = {
    importDataStoreFiles(Seq(file), jobId, projectId).map(
      _ =>
        MessageResponse(
          s"Successfully imported ${file.uniqueId} ${file.fileTypeId}"))
  }

  def getDataSetTypeById(typeId: String): Future[ServiceDataSetMetaType] =
    db.run(datasetMetaTypes.filter(_.id === typeId).result.headOption)
      .flatMap(failIfNone(s"Unable to find dataSet type `$typeId`"))

  def getDataSetTypes: Future[Seq[ServiceDataSetMetaType]] =
    db.run(datasetMetaTypes.result)

  def getDataSetById(id: IdAble): Future[DataSetMetaDataSet] =
    db.run(qDsMetaDataById(id).result.headOption)
      .flatMap(failIfNone(s"Unable to find dataSet with id ${id.toIdString}"))

  def deleteDataSetById(
      id: IdAble,
      setIsActive: Boolean = false): Future[MessageResponse] = {
    db.run(
        qDsMetaDataById(id)
          .map(d => (d.isActive, d.updatedAt))
          .update(setIsActive, JodaDateTime.now()))
      .map(_ =>
        MessageResponse(
          s"Successfully set isActive=$setIsActive for dataset $id"))
  }

  def updateDataSetById(
      id: IdAble,
      path: String,
      setIsActive: Boolean = true): Future[MessageResponse] = {
    val msg =
      s"Successfully set path=$path and isActive=$setIsActive for dataset ${id.toIdString}"
    db.run(
        qDsMetaDataById(id)
          .map(d => (d.isActive, d.path, d.updatedAt))
          .update(setIsActive, path, JodaDateTime.now()))
      .map(_ => MessageResponse(msg))
  }

  def updateSubreadSetDetails(
      id: IdAble,
      bioSampleName: Option[String],
      wellSampleName: Option[String]): Future[MessageResponse] = {
    getSubreadDataSetById(id).flatMap { ds =>
      val newBioSample = bioSampleName.getOrElse(ds.bioSampleName)
      val newWellSample = wellSampleName.getOrElse(ds.wellSampleName)
      db.run {
        DBIO.seq(
          dsSubread2
            .filter(_.id === ds.id)
            .map(s => (s.bioSampleName, s.wellSampleName))
            .update(newBioSample, newWellSample),
          qDsMetaDataById(id)
            .map(d => Tuple1(d.updatedAt))
            .update(Tuple1(JodaDateTime.now()))
        )
      } map { _ =>
        val msg =
          s"Set bioSampleName=$newBioSample and wellSampleName=$newWellSample"
        MessageResponse(msg)
      }
    }
  }

  //FIXME. Make this IdAble
  def getDataSetJobsByUUID(id: UUID): Future[Seq[EngineJob]] = {
    val q = engineJobsDataSets.filter(_.datasetUUID === id) join engineJobs
      .filter(_.isActive) on (_.jobId === _.id)
    db.run(q.result).map(_.map(x => x._2))
  }

  /**
    * These conversion funcs are necessary to take the base dataset metadata model to
    * the flattened out data model that will be returned from the Web Services
    *
    * @param t1 base dataset metadata
    * @param t2 Subread data model (e.g., subreadset specific db table)
    * @return
    */
  private def toSds(t1: DataSetMetaDataSet,
                    t2: SubreadServiceSet): SubreadServiceDataSet =
    SubreadServiceDataSet(
      t1.id,
      t1.uuid,
      t1.name,
      t1.path,
      t1.createdAt,
      t1.updatedAt,
      t1.numRecords,
      t1.totalLength,
      t1.version,
      t1.comments,
      t1.tags,
      t1.md5,
      t2.instrumentName,
      t2.instrumentControlVersion,
      t2.metadataContextId,
      t2.wellSampleName,
      t2.wellName,
      t2.bioSampleName,
      t2.cellIndex,
      t2.cellId,
      t2.runName,
      t1.createdBy,
      t1.jobId,
      t1.projectId,
      t2.dnaBarcodeName,
      t1.parentUuid
    )

  /**
    * Get a SubreadServiceDataSet by Id
    *
    * @param id Int or UUID of dataset
    * @return
    */
  def getSubreadDataSetById(id: IdAble): Future[SubreadServiceDataSet] = {
    val q = qDsMetaDataById(id) join dsSubread2 on (_.id === _.id)
    db.run(q.result.headOption)
      .map(_.map(x => toSds(x._1, x._2)))
      .flatMap(
        failIfNone(s"Unable to find SubreadSet with id ${id.toIdString}"))
  }

  // This might be wrapped in a Try to fail the future downstream with a better HTTP error code
  private def subreadToDetails(ds: SubreadServiceDataSet): String =
    DataSetJsonUtils.subreadSetToJson(
      DataSetLoader.loadSubreadSet(Paths.get(ds.path)))

  def getSubreadDataSetDetailsById(id: IdAble): Future[String] =
    getSubreadDataSetById(id).map(subreadToDetails)

  def getSubreadDataSets(
      limit: Int = DEFAULT_MAX_DATASET_LIMIT,
      includeInactive: Boolean = false,
      projectIds: Seq[Int] = Nil): Future[Seq[SubreadServiceDataSet]] = {
    val qj = dsMetaData2 join dsSubread2 on (_.id === _.id)
    val qi = if (!includeInactive) qj.filter(_._1.isActive) else qj
    val q =
      if (projectIds.nonEmpty) qi.filter(_._1.projectId inSet projectIds)
      else qi
    db.run(q.result).map(_.map(x => toSds(x._1, x._2)))
  }

  /**
    * Convert to the Service Data model
    *
    * See the SubreadSet toSds for more context.
    */
  private def toR(t1: DataSetMetaDataSet,
                  t2: ReferenceServiceSet): ReferenceServiceDataSet =
    ReferenceServiceDataSet(
      t1.id,
      t1.uuid,
      t1.name,
      t1.path,
      t1.createdAt,
      t1.updatedAt,
      t1.numRecords,
      t1.totalLength,
      t1.version,
      t1.comments,
      t1.tags,
      t1.md5,
      t1.createdBy,
      t1.jobId,
      t1.projectId,
      t2.ploidy,
      t2.organism
    )

  def getReferenceDataSets(
      limit: Int = DEFAULT_MAX_DATASET_LIMIT,
      includeInactive: Boolean = false,
      projectIds: Seq[Int] = Nil): Future[Seq[ReferenceServiceDataSet]] = {
    val qj = dsMetaData2 join dsReference2 on (_.id === _.id)
    val qi = if (!includeInactive) qj.filter(_._1.isActive) else qj
    val q =
      if (projectIds.nonEmpty) qi.filter(_._1.projectId inSet projectIds)
      else qi
    db.run(q.result).map(_.map(x => toR(x._1, x._2)))
  }

  def getReferenceDataSetById(id: IdAble): Future[ReferenceServiceDataSet] = {
    val q = qDsMetaDataById(id) join dsReference2 on (_.id === _.id)
    db.run(q.result.headOption)
      .map(_.map(x => toR(x._1, x._2)))
      .flatMap(
        failIfNone(s"Unable to find ReferenceSet with id ${id.toIdString}"))
  }

  private def referenceToDetails(ds: ReferenceServiceDataSet): String =
    DataSetJsonUtils.referenceSetToJson(
      DataSetLoader.loadReferenceSet(Paths.get(ds.path)))

  def getReferenceDataSetDetailsById(id: IdAble): Future[String] =
    getReferenceDataSetById(id).map(referenceToDetails)

  // See the SubreadSet toDs for context
  private def toGmapR(
      t1: DataSetMetaDataSet,
      t2: GmapReferenceServiceSet): GmapReferenceServiceDataSet =
    GmapReferenceServiceDataSet(
      t1.id,
      t1.uuid,
      t1.name,
      t1.path,
      t1.createdAt,
      t1.updatedAt,
      t1.numRecords,
      t1.totalLength,
      t1.version,
      t1.comments,
      t1.tags,
      t1.md5,
      t1.createdBy,
      t1.jobId,
      t1.projectId,
      t2.ploidy,
      t2.organism
    )

  def getGmapReferenceDataSets(
      limit: Int = DEFAULT_MAX_DATASET_LIMIT,
      includeInactive: Boolean = false,
      projectIds: Seq[Int] = Nil): Future[Seq[GmapReferenceServiceDataSet]] = {
    val qj = dsMetaData2 join dsGmapReference2 on (_.id === _.id)
    val qi = if (!includeInactive) qj.filter(_._1.isActive) else qj
    val q =
      if (projectIds.nonEmpty) qi.filter(_._1.projectId inSet projectIds)
      else qi
    db.run(q.result).map(_.map(x => toGmapR(x._1, x._2)))
  }

  def getGmapReferenceDataSetById(
      id: IdAble): Future[GmapReferenceServiceDataSet] = {
    val q = qDsMetaDataById(id) join dsGmapReference2 on (_.id === _.id)
    db.run(q.result.headOption)
      .map(_.map(x => toGmapR(x._1, x._2)))
      .flatMap(failIfNone(
        s"Unable to find GmapReferenceSet with id ${id.toIdString}"))
  }

  private def gmapReferenceToDetails(ds: GmapReferenceServiceDataSet): String =
    DataSetJsonUtils.gmapReferenceSetToJson(
      DataSetLoader.loadGmapReferenceSet(Paths.get(ds.path)))

  def getGmapReferenceDataSetDetailsById(id: IdAble): Future[String] =
    getGmapReferenceDataSetById(id).map(gmapReferenceToDetails)

  def getHdfDataSets(
      limit: Int = DEFAULT_MAX_DATASET_LIMIT,
      includeInactive: Boolean = false,
      projectIds: Seq[Int] = Nil): Future[Seq[HdfSubreadServiceDataSet]] = {
    val qj = dsMetaData2 join dsHdfSubread2 on (_.id === _.id)
    val qi = if (!includeInactive) qj.filter(_._1.isActive) else qj
    val q =
      if (projectIds.nonEmpty) qi.filter(_._1.projectId inSet projectIds)
      else qi
    db.run(q.result).map(_.map(x => toHds(x._1, x._2)))
  }

  private def toHds(t1: DataSetMetaDataSet,
                    t2: HdfSubreadServiceSet): HdfSubreadServiceDataSet =
    HdfSubreadServiceDataSet(
      t1.id,
      t1.uuid,
      t1.name,
      t1.path,
      t1.createdAt,
      t1.updatedAt,
      t1.numRecords,
      t1.totalLength,
      t1.version,
      t1.comments,
      t1.tags,
      t1.md5,
      t2.instrumentName,
      t2.metadataContextId,
      t2.wellSampleName,
      t2.wellName,
      t2.bioSampleName,
      t2.cellIndex,
      t2.runName,
      t1.createdBy,
      t1.jobId,
      t1.projectId
    )

  def getHdfDataSetById(id: IdAble): Future[HdfSubreadServiceDataSet] = {
    val q = qDsMetaDataById(id) join dsHdfSubread2 on (_.id === _.id)
    db.run(q.result.headOption)
      .map(_.map(x => toHds(x._1, x._2)))
      .flatMap(failIfNone(s"Unable to find HdfSubreadSet with id `$id`"))
  }

  private def hdfsubreadToDetails(ds: HdfSubreadServiceDataSet): String =
    DataSetJsonUtils.hdfSubreadSetToJson(
      DataSetLoader.loadHdfSubreadSet(Paths.get(ds.path)))

  def getHdfDataSetDetailsById(id: IdAble): Future[String] =
    getHdfDataSetById(id).map(hdfsubreadToDetails)

  private def toA(t1: DataSetMetaDataSet) =
    AlignmentServiceDataSet(
      t1.id,
      t1.uuid,
      t1.name,
      t1.path,
      t1.createdAt,
      t1.updatedAt,
      t1.numRecords,
      t1.totalLength,
      t1.version,
      t1.comments,
      t1.tags,
      t1.md5,
      t1.createdBy,
      t1.jobId,
      t1.projectId
    )

  def getAlignmentDataSets(
      limit: Int = DEFAULT_MAX_DATASET_LIMIT,
      includeInactive: Boolean = false,
      projectIds: Seq[Int] = Nil): Future[Seq[AlignmentServiceDataSet]] = {
    val qj = dsMetaData2 join dsAlignment2 on (_.id === _.id)
    val qi = if (!includeInactive) qj.filter(_._1.isActive) else qj
    val q =
      if (projectIds.nonEmpty) qi.filter(_._1.projectId inSet projectIds)
      else qi
    db.run(q.result).map(_.map(x => toA(x._1)))
  }

  def getAlignmentDataSetById(id: IdAble): Future[AlignmentServiceDataSet] = {
    val q = qDsMetaDataById(id) join dsAlignment2 on (_.id === _.id)
    db.run(q.result.headOption)
      .map(_.map(x => toA(x._1)))
      .flatMap(failIfNone(s"Unable to find AlignmentSet with id `$id`"))
  }

  private def alignmentSetToDetails(ds: AlignmentServiceDataSet): String = {
    DataSetJsonUtils.alignmentSetToJson(
      DataSetLoader.loadAlignmentSet(Paths.get(ds.path)))
  }

  def getAlignmentDataSetDetailsById(id: IdAble): Future[String] =
    getAlignmentDataSetById(id).map(alignmentSetToDetails)

  /*--- CONSENSUS READS ---*/

  private def toCCSread(t1: DataSetMetaDataSet) =
    ConsensusReadServiceDataSet(
      t1.id,
      t1.uuid,
      t1.name,
      t1.path,
      t1.createdAt,
      t1.updatedAt,
      t1.numRecords,
      t1.totalLength,
      t1.version,
      t1.comments,
      t1.tags,
      t1.md5,
      t1.createdBy,
      t1.jobId,
      t1.projectId
    )

  // TODO(smcclellan): limit is never uesed. add `.take(limit)`?
  def getConsensusReadDataSets(
      limit: Int = DEFAULT_MAX_DATASET_LIMIT,
      includeInactive: Boolean = false,
      projectIds: Seq[Int] = Nil): Future[Seq[ConsensusReadServiceDataSet]] = {
    val qj = dsMetaData2 join dsCCSread2 on (_.id === _.id)
    val qi = if (!includeInactive) qj.filter(_._1.isActive) else qj
    val q =
      if (projectIds.nonEmpty) qi.filter(_._1.projectId inSet projectIds)
      else qi
    db.run(q.result).map(_.map(x => toCCSread(x._1)))
  }

  def getConsensusReadDataSetById(
      id: IdAble): Future[ConsensusReadServiceDataSet] = {
    val q = qDsMetaDataById(id) join dsCCSread2 on (_.id === _.id)
    db.run(q.result.headOption)
      .map(_.map(x => toCCSread(x._1)))
      .flatMap(failIfNone(s"Unable to find ConsensusReadSet with id `$id`"))
  }

  private def consensusReadSetToDetails(
      ds: ConsensusReadServiceDataSet): String =
    DataSetJsonUtils.consensusSetToJson(
      DataSetLoader.loadConsensusReadSet(Paths.get(ds.path)))

  def getConsensusReadDataSetDetailsById(id: IdAble): Future[String] =
    getConsensusReadDataSetById(id).map(consensusReadSetToDetails)

  /*--- CONSENSUS ALIGNMENTS ---*/

  private def toCCSA(t1: DataSetMetaDataSet) =
    ConsensusAlignmentServiceDataSet(
      t1.id,
      t1.uuid,
      t1.name,
      t1.path,
      t1.createdAt,
      t1.updatedAt,
      t1.numRecords,
      t1.totalLength,
      t1.version,
      t1.comments,
      t1.tags,
      t1.md5,
      t1.createdBy,
      t1.jobId,
      t1.projectId
    )

  def getConsensusAlignmentDataSets(limit: Int = DEFAULT_MAX_DATASET_LIMIT,
                                    includeInactive: Boolean = false,
                                    projectIds: Seq[Int] = Nil)
    : Future[Seq[ConsensusAlignmentServiceDataSet]] = {
    val qj = dsMetaData2 join dsCCSAlignment2 on (_.id === _.id)
    val qi = if (!includeInactive) qj.filter(_._1.isActive) else qj
    val q =
      if (projectIds.nonEmpty) qi.filter(_._1.projectId inSet projectIds)
      else qi
    db.run(q.result).map(_.map(x => toCCSA(x._1)))
  }

  def getConsensusAlignmentDataSetById(
      id: IdAble): Future[ConsensusAlignmentServiceDataSet] = {
    val q = qDsMetaDataById(id) join dsCCSAlignment2 on (_.id === _.id)
    db.run(q.result.headOption)
      .map(_.map(x => toCCSA(x._1)))
      .flatMap(failIfNone(
        s"Unable to find ConsensusAlignmentSet with uuid ${id.toIdString}"))
  }

  private def consensusAlignmentSetToDetails(
      ds: ConsensusAlignmentServiceDataSet): String =
    DataSetJsonUtils.consensusAlignmentSetToJson(
      DataSetLoader.loadConsensusAlignmentSet(Paths.get(ds.path)))

  def getConsensusAlignmentDataSetDetailsById(id: IdAble): Future[String] =
    getConsensusAlignmentDataSetById(id).map(consensusAlignmentSetToDetails)

  /*--- BARCODES ---*/

  private def toB(t1: DataSetMetaDataSet) =
    BarcodeServiceDataSet(
      t1.id,
      t1.uuid,
      t1.name,
      t1.path,
      t1.createdAt,
      t1.updatedAt,
      t1.numRecords,
      t1.totalLength,
      t1.version,
      t1.comments,
      t1.tags,
      t1.md5,
      t1.createdBy,
      t1.jobId,
      t1.projectId
    )

  def getBarcodeDataSets(
      limit: Int = DEFAULT_MAX_DATASET_LIMIT,
      includeInactive: Boolean = false,
      projectIds: Seq[Int] = Nil): Future[Seq[BarcodeServiceDataSet]] = {
    val qj = dsMetaData2 join dsBarcode2 on (_.id === _.id)
    val qi = if (!includeInactive) qj.filter(_._1.isActive) else qj
    val q =
      if (projectIds.nonEmpty) qi.filter(_._1.projectId inSet projectIds)
      else qi
    db.run(q.result).map(_.map(x => toB(x._1)))
  }

  def getBarcodeDataSetById(id: IdAble): Future[BarcodeServiceDataSet] = {
    val q = qDsMetaDataById(id) join dsBarcode2 on (_.id === _.id)
    db.run(q.result.headOption)
      .map(_.map(x => toB(x._1)))
      .flatMap(failIfNone(s"Unable to find BarcodeSet with id `$id`"))
  }

  private def barcodeSetToDetails(ds: BarcodeServiceDataSet): String =
    DataSetJsonUtils.barcodeSetToJson(
      DataSetLoader.loadBarcodeSet(Paths.get(ds.path)))

  def getBarcodeDataSetDetailsById(id: IdAble): Future[String] =
    getBarcodeDataSetById(id).map(barcodeSetToDetails)

  /*--- CONTIGS ---*/

  private def toCtg(t1: DataSetMetaDataSet) =
    ContigServiceDataSet(
      t1.id,
      t1.uuid,
      t1.name,
      t1.path,
      t1.createdAt,
      t1.updatedAt,
      t1.numRecords,
      t1.totalLength,
      t1.version,
      t1.comments,
      t1.tags,
      t1.md5,
      t1.createdBy,
      t1.jobId,
      t1.projectId
    )

  def getContigDataSets(
      limit: Int = DEFAULT_MAX_DATASET_LIMIT,
      includeInactive: Boolean = false,
      projectIds: Seq[Int] = Nil): Future[Seq[ContigServiceDataSet]] = {
    val qj = dsMetaData2 join dsContig2 on (_.id === _.id)
    val qi = if (!includeInactive) qj.filter(_._1.isActive) else qj
    val q =
      if (projectIds.nonEmpty) qi.filter(_._1.projectId inSet projectIds)
      else qi
    db.run(q.result).map(_.map(x => toCtg(x._1)))
  }

  def getContigDataSetById(id: IdAble): Future[ContigServiceDataSet] = {
    val q = qDsMetaDataById(id) join dsContig2 on (_.id === _.id)
    db.run(q.result.headOption)
      .map(_.map(x => toCtg(x._1)))
      .flatMap(failIfNone(s"Unable to find ContigSet with id `$id`"))
  }

  private def contigSetToDetails(ds: ContigServiceDataSet): String =
    DataSetJsonUtils.contigSetToJson(
      DataSetLoader.loadContigSet(Paths.get(ds.path)))

  def getContigDataSetDetailsById(id: IdAble): Future[String] =
    getContigDataSetById(id).map(contigSetToDetails)

  /*--- DATASTORE ---*/

  private def toDataStoreJobFile(x: DataStoreServiceFile) =
    DataStoreJobFile(x.jobUUID,
                     DataStoreFile(x.uuid,
                                   x.sourceId,
                                   x.fileTypeId,
                                   x.fileSize,
                                   x.createdAt,
                                   x.modifiedAt,
                                   x.path,
                                   name = x.name,
                                   description = x.description))

  def getDataStoreFilesByJobId(i: IdAble): Future[Seq[DataStoreJobFile]] = {
    db.run {
      val q = for {
        engineJob <- qEngineJobById(i)
        dsFiles <- datastoreServiceFiles.filter(_.jobId === engineJob.id)
      } yield dsFiles
      q.result.map(_.map(toDataStoreJobFile))
    }
  }

  /**
    * Get a DataStore Service File by DataStore file UUID
    *
    * @param id Unique Id of the datastore file
    * @return
    */
  def getDataStoreFile(id: UUID): Future[DataStoreJobFile] =
    db.run(
        datastoreServiceFiles
          .filter(_.uuid === id)
          .result
          .headOption
          .map(_.map(toDataStoreJobFile)))
      .flatMap(failIfNone(s"Unable to find DataStore File with UUID $id"))

  def deleteDataStoreFile(
      id: UUID,
      setIsActive: Boolean = false): Future[MessageResponse] = {
    db.run(
        datastoreServiceFiles
          .filter(_.uuid === id)
          .map(f => (f.isActive, f.modifiedAt))
          .update(setIsActive, JodaDateTime.now()))
      .map(_ =>
        MessageResponse(
          s"Successfully set datastore file $id to isActive=$setIsActive"))
  }

  /**
    * Update the Path and the Activity of a DataStore file
    *
    * FIXME. These should be using Path, not String
    *
    * @param id          Unique id of the datastore file
    * @param path        Absolute path to the file
    * @param setIsActive activity of the file
    * @return
    */
  def updateDataStoreFile(
      id: UUID,
      path: Option[String] = None,
      fileSize: Option[Long] = None,
      setIsActive: Boolean = true): Future[MessageResponse] = {
    val now = JodaDateTime.now()
    val q1 = datastoreServiceFiles.filter(_.uuid === id)
    val q2 = List(
      path.map(
        p =>
          q1.map(f => (f.isActive, f.path, f.modifiedAt))
            .update((setIsActive, p, now))),
      fileSize.map(
        fsize =>
          q1.map(f => (f.isActive, f.fileSize, f.modifiedAt))
            .update((setIsActive, fsize, now)))
    ).flatten
    db.run(DBIO.sequence(q2))
      .map(_ =>
        MessageResponse(
          s"Successfully set datastore file $id to path=$path, fileSize=$fileSize and isActive=$setIsActive"))
  }

  /**
    * Delete a DataStore File by data store file UUID
    *
    * @param id UUID of the datastore file
    * @return
    */
  def deleteDataStoreJobFile(id: UUID): Future[MessageResponse] = {
    def addOptionalDelete(
        ds: Option[DataStoreServiceFile]): Future[MessageResponse] = {
      // 1 of 3: delete the DataStoreServiceFile, if it isn't already in the DB
      val deleteDsFile = ds
        .map(dsFile => DBIO.from(deleteDataStoreFile(id)))
        .getOrElse(DBIO.from(Future.successful(
          MessageResponse(s"No datastore file with ID $id found"))))

      // 2 of 3: insert of the data set, if it is a known/supported file type
      val optionalDelete = ds
        .map { dsFile =>
          DataSetMetaTypes
            .toDataSetType(dsFile.fileTypeId)
            .map(_ => DBIO.from(deleteDataSetById(dsFile.uuid)))
            .getOrElse(DBIO.from(Future.successful(MessageResponse(
              s"File type ${dsFile.fileTypeId} is not a dataset, so no metadata to delete."))))
        }
        .getOrElse(DBIO.from(Future.successful(MessageResponse(
          s"No datastore file, so no dataset metadata to delete"))))

      // 3 of 3: run the appropriate actions in a transaction
      val fin = for {
        _ <- deleteDsFile
        od <- optionalDelete
      } yield od
      db.run(fin.transactionally)
    }

    // This needed queries un-nested due to SQLite limitations -- see #197
    db.run(datastoreServiceFiles.filter(_.uuid === id).result.headOption)
      .flatMap(addOptionalDelete)
  }

  def getJobChildrenByJobId(i: IdAble): Future[Seq[EngineJob]] = {
    val jobDsJoin = for {
      j <- qEngineJobById(i)
      d <- datastoreServiceFiles if d.jobId === j.id
      e <- engineJobsDataSets if e.datasetUUID === d.uuid
      c <- engineJobs if ((c.id === e.jobId) && c.isActive)
    } yield (d, c)
    db.run(jobDsJoin.result).map(_.filter(_._1.fileExists).map(_._2))
  }

  def getSystemSummary(header: String = "System Summary"): Future[String] = {
    for {
      ssets <- db.run(
        (dsMetaData2 join dsSubread2 on (_.id === _.id)).length.result)
      rsets <- db.run(
        (dsMetaData2 join dsReference2 on (_.id === _.id)).length.result)
      asets <- db.run(
        (dsMetaData2 join dsAlignment2 on (_.id === _.id)).length.result)
      jobCounts <- db.run(
        engineJobs
          .groupBy(x => (x.jobTypeId, x.state))
          .map({
            case ((jobType, state), list) => (jobType, state, list.length)
          })
          .result)
      jobEvents <- db.run(jobEvents.length.result)
      dsFiles <- db.run(datastoreServiceFiles.length.result)
      entryPoints <- db.run(engineJobsDataSets.length.result)
    } yield
      s"""
         |$header
         |--------
         |DataSets
         |--------
         |nsubreads            : $ssets
         |alignments           : $asets
         |references           : $rsets
         |--------
         |Jobs
         |--------
         | ${jobCounts
           .map(x => f"${x._1}%25s  ${x._2}%10s  ${x._3}%6d")
           .mkString("\n         | ")}
         |--------
         |Total JobEvents      : $jobEvents
         |Total entryPoints    : $entryPoints
         |Total DataStoreFiles : $dsFiles
       """.stripMargin
  }

  def addEulaRecord(eulaRecord: EulaRecord): Future[EulaRecord] = {
    val f = db.run(eulas += eulaRecord).map(_ => eulaRecord)
    f.onSuccess { case e: EulaRecord => sendEventToManager[EulaRecord](e) }
    f
  }

  def getEulas: Future[Seq[EulaRecord]] = db.run(eulas.result)

  def getEulaByVersion(version: String): Future[EulaRecord] =
    db.run(eulas.filter(_.smrtlinkVersion === version).result.headOption)
      .flatMap(failIfNone(s"Unable to find Eula version `$version`"))

  def removeEula(version: String): Future[Int] =
    db.run(eulas.filter(_.smrtlinkVersion === version).delete)
}

/**
  * Core SMRT Link Data Access Object for interacting with DataSets, Projects and Jobs.
  *
  * @param db Postgres Database Config
  * @param resolver Resolver that will determine where to write jobs to
  * @param eventManager Event/Message manager to send EventMessages (e.g., accepted Eula, Job changed state)
  */
class JobsDao(val db: Database,
              val resolver: JobResourceResolver,
              eventManager: Option[ActorRef] = None)
    extends DalComponent
    with SmrtLinkConstants
    with EventComponent
    with ProjectDataStore
    with JobDataStore
    with DataSetStore {

  import JobModels._

  override def sendEventToManager[T](message: T): Unit = {
    eventManager.map(a => a ! message)
  }

}

trait JobsDaoProvider {
  this: DalProvider
    with SmrtLinkConfigProvider
    with EventManagerActorProvider =>

  val jobsDao: Singleton[JobsDao] =
    Singleton(
      () => new JobsDao(db(), jobResolver(), Some(eventManagerActor())))
}
