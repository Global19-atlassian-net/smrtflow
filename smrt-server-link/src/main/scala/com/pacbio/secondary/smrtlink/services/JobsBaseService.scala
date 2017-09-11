package com.pacbio.secondary.smrtlink.services

import java.io.File
import java.util.UUID
import java.nio.file.{Files, Path, Paths}

import akka.actor.ActorSystem
import akka.util.Timeout
import com.pacbio.secondary.smrtlink.actors.{ActorRefFactoryProvider, ActorSystemProvider, JobsDao, JobsDaoProvider}
import com.pacbio.secondary.smrtlink.analysis.jobs.JobModels._
import com.pacbio.common.models.CommonModels._
import com.pacbio.secondary.smrtlink.services.PacBioServiceErrors.{MethodNotImplementedError, ResourceNotFoundError, UnprocessableEntityError}
import com.pacbio.common.models.CommonModelImplicits
import com.pacbio.common.models.CommonModelSpraySupport
import com.pacbio.secondary.smrtlink.JobServiceConstants
import com.pacbio.secondary.smrtlink.actors.CommonMessages.MessageResponse
import com.pacbio.secondary.smrtlink.analysis.datasets.DataSetFileUtils
import com.pacbio.secondary.smrtlink.analysis.jobs.AnalysisJobStates
import com.pacbio.secondary.smrtlink.analysis.jobtypes.PbsmrtpipeJobUtils
import com.pacbio.secondary.smrtlink.app.SmrtLinkConfigProvider
import com.pacbio.secondary.smrtlink.auth.{Authenticator, AuthenticatorProvider}
import com.pacbio.secondary.smrtlink.dependency.Singleton
import com.pacbio.secondary.smrtlink.jobtypes._
import com.pacbio.secondary.smrtlink.models._
import com.pacbio.secondary.smrtlink.jsonprotocols.SmrtLinkJsonProtocols
import com.pacbio.secondary.smrtlink.models.ConfigModels.SystemJobConfig
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.{FileUtils, FilenameUtils}
import spray.http.{HttpData, HttpEntity, _}
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling.Unmarshaller
import spray.httpx.marshalling.Marshaller
import spray.json._
import spray.routing._
import spray.routing.directives.FileAndResourceDirectives

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}


object JobResourceUtils extends  LazyLogging{
  // FIXME. This is a very lackluster idea.
  // This assumes an id -> file which is wrong
  def getJobResource(jobDir: String, imageFileName: String): Option[String] = {
    val jobP = Paths.get(jobDir)
    val ext = FilenameUtils.getExtension(imageFileName)
    val filterExt = if (ext.isEmpty) Seq("*") else Seq(ext)
    logger.debug(s"Trying to resolve resource '$imageFileName' with ext '$ext' from '$jobDir'")
    val it = FileUtils.iterateFiles(jobP.toFile, filterExt.toArray, true).filter(x => x.getName == imageFileName)
    it.toList.headOption match {
      case Some(x) => Some(x.toPath.toAbsolutePath.toString)
      case _ => None
    }
  }

  def resolveResourceFrom(rootJobDir: Path, imageFileName: String)(implicit ec: ExecutionContext) = Future {
    JobResourceUtils.getJobResource(rootJobDir.toAbsolutePath.toString, imageFileName).getOrElse(s"Failed to find resource '$imageFileName' from $rootJobDir")
  }
}

trait JobServiceRoutes {
  def jobTypeId: JobTypeIds.JobType
  def routes: Route
}

trait CommonJobsRoutes[T <: ServiceJobOptions] extends SmrtLinkBaseMicroService with JobServiceConstants with JobServiceRoutes {
  val dao: JobsDao
  val authenticator: Authenticator
  val config: SystemJobConfig

  implicit val um: Unmarshaller[T]
  implicit val sm: Marshaller[T]
  implicit val jwriter: JsonWriter[T]

  import SmrtLinkJsonProtocols._
  import CommonModelSpraySupport._
  import CommonModelImplicits._

  override val manifest = PacBioComponentManifest(
    toServiceId(s"job_service_${jobTypeId.id.replace("-", "_")}"),
    s"JobService ${jobTypeId.id} Service",
    "0.2.0", s"Job Service ${jobTypeId.id} ${jobTypeId.description}")


  /**
    * Logging util
    *
    * @param sx String
    * @return
    */
  def andLog(sx: String): String = {
    logger.info(sx)
    sx
  }

  /**
    * Validation of the Opts returns Option[Error], This will fail
    * the Future if any validation errors occur.
    *
    * @param opts
    * @param user
    * @return
    */
  def validator(opts: T, user: Option[UserRecord]): Future[T] = {
    opts.validate(dao, config) match {
      case Some(ex) => Future.failed(throw new UnprocessableEntityError(ex.msg))
      case _ => Future.successful(opts)
    }
  }

  /**
    * Optional Termination implementation of a Job. By default this method is not supported.
    *
    * @param jobId Job id to terminateb
    * @return
    */
  def terminateJob(jobId: IdAble): Future[MessageResponse] =
    Future.failed(throw new MethodNotImplementedError(s"Job type ${jobTypeId.id} does NOT support termination"))

  /**
    * Central interface for creating Jobs using the ServiceJobOptions.
    *
    * This interface will handle the POST (and unmarshalling) and
    * validation and creating a new EngineJob from the ServiceJobOptions
    * provided.
    *
    * If there are customized needs, you should override this and
    * always call super.createJob.
    *
    * @param opts
    * @param user
    * @return
    */
  def createJob(opts: T, user: Option[UserRecord]): Future[EngineJob] = {
    val uuid = UUID.randomUUID()

    val name = opts.name.getOrElse(opts.jobTypeId.id)
    val comment = opts.description.getOrElse(s"Description for job ${opts.jobTypeId.name}")

    // This will require an implicit JsonWriter in scope
    val jsettings = opts.toJson.asJsObject

    val projectId = opts.projectId.getOrElse(JobConstants.GENERAL_PROJECT_ID)

    for {
      vopts <- validator(opts, user) // This will fail the Future if any validation errors occur.
      entryPoints <- Future(vopts.resolveEntryPoints(dao))
      engineJob <- dao.createJob2(uuid, name, comment, vopts.jobTypeId, entryPoints, jsettings, user.map(_.userId),
        user.flatMap(_.userEmail),
        config.smrtLinkVersion, projectId)
    } yield engineJob
  }

  private def resolveContentType(path: Path): ContentType = {
    val mimeType = MimeTypeProperties.fromFile(path.toFile)
    val mediaType = MediaType.custom(mimeType)
    ContentType(mediaType)
  }

  private def resolveJobResource(fx: Future[EngineJob], id: String)(implicit ec: ExecutionContext): Future[HttpEntity] = {
    fx.map { engineJob =>
      JobResourceUtils.getJobResource(engineJob.path, id)
    }.recover {
      case NonFatal(e) => None
    }.flatMap {
      case Some(x) =>
        val mtype = if (id.endsWith(".png")) MediaTypes.`image/png` else MediaTypes.`text/plain`
        Future { HttpEntity(ContentType(mtype), HttpData(new File(x))) }
      case None =>
        Future.failed(throw new ResourceNotFoundError(s"Unable to find image resource '$id'"))
    }
  }

  private def toHttpEntity(path: Path): HttpEntity = {
    logger.debug(s"Resolving path ${path.toAbsolutePath.toString}")
    if (Files.exists(path)) {
      HttpEntity(resolveContentType(path), HttpData(path.toFile))
    } else {
      logger.error(s"Failed to resolve ${path.toAbsolutePath.toString}")
      throw new ResourceNotFoundError(s"Failed to find ${path.toAbsolutePath.toString}")
    }
  }

  val allRootJobRoutes: Route =
    pathEndOrSingleSlash {
      post {
        optionalAuthenticate(authenticator.wso2Auth) { user =>
          entity(as[T]) { opts =>
            complete {
              created {
                createJob(opts, user)
              }
            }
          }
        }
      } ~
      get {
        parameters('showAll.?, 'projectId.?.as[Option[Int]]) { (showAll, projectId) =>
          complete {
            ok {
              dao.getJobsByTypeId(jobTypeId.id, showAll.isDefined, projectId)
            }
          }
        }
      }
    }


  def allIdAbleJobRoutes(implicit ec: ExecutionContext): Route =
    pathPrefix(IdAbleMatcher) { jobId =>
      pathEndOrSingleSlash {
        get {
          complete {
            ok {
              dao.getJobById(jobId)
            }
          }
        }
      } ~
      path(LOG_PREFIX) {
        pathEndOrSingleSlash {
          post {
            entity(as[LogMessageRecord]) { m =>
              complete {
                created {
                  val f = jobId match {
                    case IntIdAble(n) => Future.successful(n)
                    case UUIDIdAble(_) => dao.getJobById(jobId).map(_.id)
                  }
                  f.map { intId =>
                    val message = s"$LOG_PB_SMRTPIPE_RESOURCE_ID::job::$intId::${m.sourceId} ${m.message}"
                    // FIXME. Need to map this to the proper log level
                    logger.info(message)
                    MessageResponse(s"Successfully logged. $message")
                  }
                }
              }
            }
          }
        }
      } ~
      path("terminate") {
        pathEndOrSingleSlash {
          post {
            complete {
              ok {
                terminateJob(jobId)
              }
            }
          }
        }
      } ~
      path(JOB_TASK_PREFIX) {
        get {
          complete {
            ok {
              dao.getJobTasks(jobId)
            }
          }
        } ~
        post {
          entity(as[CreateJobTaskRecord]) { r =>
            complete {
              created {
                dao.getJobById(jobId).flatMap { job =>
                  dao.addJobTask(JobTask(r.uuid, job.id, r.taskId, r.taskTypeId, r.name, AnalysisJobStates.CREATED.toString, r.createdAt, r.createdAt, None))
                }
              }
            }
          }
        }
      } ~
      path(JOB_TASK_PREFIX / JavaUUID) { taskUUID =>
        get {
          complete {
            ok {
              dao.getJobTask(taskUUID)
            }
          }
        } ~
        put {
          entity(as[UpdateJobTaskRecord]) { r =>
            complete {
              created {
                dao.getJobById(jobId).flatMap { engineJob =>
                 dao.updateJobTask(UpdateJobTask(engineJob.id, taskUUID, r.state, r.message, r.errorMessage))
                }
              }
            }
          }
        }
      } ~
      path(JOB_REPORT_PREFIX / JavaUUID) { reportUUID =>
        get {
          respondWithMediaType(MediaTypes.`application/json`) {
            complete {
              ok {
                dao.getDataStoreReportByUUID(reportUUID)
              }
            }
          }
        }
      } ~
      path(JOB_REPORT_PREFIX) {
        get {
          complete {
            dao.getJobById(jobId).flatMap { engineJob =>
                dao.getDataStoreReportFilesByJobId(engineJob.id)
            }
          }
        }
      } ~
      path(JOB_EVENT_PREFIX) {
        get {
          complete {
            ok {
              dao.getJobById(jobId).flatMap { engineJob =>
                dao.getJobEventsByJobId(engineJob.id)
              }
            }
          }
        }
      } ~
      path(JOB_DATASTORE_PREFIX) {
        get {
          complete {
            ok {
              dao.getJobById(jobId).flatMap { engineJob =>
                dao.getDataStoreServiceFilesByJobId(engineJob.id)
              }
            }
          }
        }
      } ~
      path(JOB_DATASTORE_PREFIX / JavaUUID) { datastoreFileUUID =>
        get {
          complete {
            ok {
              dao.getDataStoreFileByUUID(datastoreFileUUID)
            }
          }
        } ~
        put {
          entity(as[DataStoreFileUpdateRequest]) { sopts =>
            complete {
              ok {
                dao.updateDataStoreFile(datastoreFileUUID, sopts.path, sopts.fileSize, sopts.isActive)
              }
            }
          }
        }
      } ~
      path(JOB_OPTIONS) {
        get {
          complete {
            ok {
              dao.getJobById(jobId).map(_.jsonSettings)
            }
          }
        }
      } ~
      path(ENTRY_POINTS_PREFIX) {
        get {
          complete {
            dao.getJobById(jobId).flatMap { engineJob =>
              dao.getJobEntryPoints(engineJob.id)
            }
          }
        }
      } ~
      path(JOB_DATASTORE_PREFIX) {
        post {
          entity(as[DataStoreFile]) { dsf =>
            complete {
              created {
                dao.getJobById(jobId).flatMap { engineJob =>
                  dao.insertDataStoreFileById(dsf, engineJob.id)
                }
              }
            }
          }
        }
      } ~
      path(JOB_DATASTORE_PREFIX / JavaUUID / "download") { datastoreFileUUID =>
        get {
          complete {
            dao.getDataStoreFileByUUID(datastoreFileUUID)
                .map { dsf =>
                  val httpEntity = toHttpEntity(Paths.get(dsf.path))
                  // The datastore needs to be updated to be written at the root of the job dir,
                  // then the paths should be relative to the root dir. This will require that the DataStoreServiceFile
                  // returns the correct absolute path
                  val fn = s"job-${jobId.toIdString}-${dsf.uuid.toString}-${Paths.get(dsf.path).toAbsolutePath.getFileName}"
                  // Using headers= instead of respondWithHeader because need to set the name file file
                  HttpResponse(entity = httpEntity, headers = List(HttpHeaders.`Content-Disposition`("attachment; filename=" + fn)))
                }
          }
        }
      } ~
      path(JOB_DATASTORE_PREFIX) {
        post {
          entity(as[DataStoreFile]) { dsf =>
            complete {
              created {
                dao.getJobById(jobId).flatMap { engineJob =>
                  dao.insertDataStoreFileById(dsf, engineJob.id)
                }
              }
            }
          }
        }
      } ~
      path("resources") {
        parameter('id) { id =>
          logger.info(s"Attempting to resolve resource $id from ${jobId.toIdString}")
          complete {
            resolveJobResource(dao.getJobById(jobId), id)
          }
        }
      } ~
      path("children") {
        complete {
          ok {
            dao.getJobChildrenByJobId(jobId).mapTo[Seq[EngineJob]]
          }
        }
      }
    }

  override def routes:Route = allIdAbleJobRoutes ~ allRootJobRoutes
}


// All Service Jobs should be defined here. This a bit boilerplate, but it's very simple and there aren't a large number of job types
class HelloWorldJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[HelloWorldJobOptions], implicit val sm: Marshaller[HelloWorldJobOptions], implicit val jwriter: JsonWriter[HelloWorldJobOptions]) extends CommonJobsRoutes[HelloWorldJobOptions] {
  override def jobTypeId = JobTypeIds.HELLO_WORLD
}

class DbBackupJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[DbBackUpJobOptions], implicit val sm: Marshaller[DbBackUpJobOptions], implicit val jwriter: JsonWriter[DbBackUpJobOptions]) extends CommonJobsRoutes[DbBackUpJobOptions] {
  override def jobTypeId = JobTypeIds.DB_BACKUP
}

class DeleteDataSetJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[DeleteDataSetJobOptions], implicit val sm: Marshaller[DeleteDataSetJobOptions], implicit val jwriter: JsonWriter[DeleteDataSetJobOptions]) extends CommonJobsRoutes[DeleteDataSetJobOptions] {
  override def jobTypeId = JobTypeIds.DELETE_DATASETS
}

class DeleteSmrtLinkJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[DeleteSmrtLinkJobOptions], implicit val sm: Marshaller[DeleteSmrtLinkJobOptions], implicit val jwriter: JsonWriter[DeleteSmrtLinkJobOptions]) extends CommonJobsRoutes[DeleteSmrtLinkJobOptions] {
  override def jobTypeId = JobTypeIds.DELETE_JOB
}

class ExportDataSetsJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[ExportDataSetsJobOptions], implicit val sm: Marshaller[ExportDataSetsJobOptions], implicit val jwriter: JsonWriter[ExportDataSetsJobOptions]) extends CommonJobsRoutes[ExportDataSetsJobOptions] {
  override def jobTypeId = JobTypeIds.EXPORT_DATASETS
}

class ExportJobsService(override val dao: JobsDao,
                        override val authenticator: Authenticator,
                        override val config: SystemJobConfig)
                       (implicit val um: Unmarshaller[ExportSmrtLinkJobOptions],
                        implicit val sm: Marshaller[ExportSmrtLinkJobOptions],
                        implicit val jwriter: JsonWriter[ExportSmrtLinkJobOptions]) extends CommonJobsRoutes[ExportSmrtLinkJobOptions] {
  override def jobTypeId = JobTypeIds.EXPORT_JOBS
}

class ImportBarcodeFastaJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[ImportBarcodeFastaJobOptions], implicit val sm: Marshaller[ImportBarcodeFastaJobOptions], implicit val jwriter: JsonWriter[ImportBarcodeFastaJobOptions]) extends CommonJobsRoutes[ImportBarcodeFastaJobOptions] {
  override def jobTypeId = JobTypeIds.CONVERT_FASTA_BARCODES
}

class ImportDataSetJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[ImportDataSetJobOptions], implicit val sm: Marshaller[ImportDataSetJobOptions], implicit val jwriter: JsonWriter[ImportDataSetJobOptions]) extends CommonJobsRoutes[ImportDataSetJobOptions] with DataSetFileUtils {
  //type T = ImportDataSetJobOptions
  import CommonModelImplicits._

  override def jobTypeId = JobTypeIds.IMPORT_DATASET

  private def updatePathIfNecessary(opts: ImportDataSetJobOptions): Future[EngineJob] = {
    for {
      dsMini <- Future.fromTry(Try(getDataSetMiniMeta(opts.path)))
      ds <- dao.getDataSetById(dsMini.uuid)
      engineJob <- dao.getJobById(ds.jobId)
      m1 <- dao.updateDataStoreFile(ds.uuid, Some(opts.path.toString), None, true)
      m2 <- dao.updateDataSetById(ds.uuid, opts.path.toString, true)
      _ <- Future.successful(andLog(s"$m1 $m2"))

    } yield engineJob
  }

  /**
    * Need a custom create job method to return the companion dataset job IF the
    * dataset has already been imported.
    *
    * If the path of dataset in the database is different than the provided path, the
    * path is assumed to be updated to the path provided.
    *
    * @param opts
    * @param user
    * @return
    */
  override def createJob(opts: ImportDataSetJobOptions, user: Option[UserRecord]): Future[EngineJob] = {

    val f1 = for {
      _ <- validator(opts, user)
      engineJob <- updatePathIfNecessary(opts)
    } yield engineJob

    f1.recoverWith { case err: ResourceNotFoundError => super.createJob(opts, user) }
  }

}

class ImportFastaJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[ImportFastaJobOptions], implicit val sm: Marshaller[ImportFastaJobOptions], implicit val jwriter: JsonWriter[ImportFastaJobOptions]) extends CommonJobsRoutes[ImportFastaJobOptions] {
  override def jobTypeId = JobTypeIds.CONVERT_FASTA_REFERENCE

}

class MergeDataSetJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[MergeDataSetJobOptions], implicit val sm: Marshaller[MergeDataSetJobOptions], implicit val jwriter: JsonWriter[MergeDataSetJobOptions]) extends CommonJobsRoutes[MergeDataSetJobOptions] {
  override def jobTypeId = JobTypeIds.MERGE_DATASETS
}


class MockPbsmrtpipeJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[MockPbsmrtpipeJobOptions], implicit val sm: Marshaller[MockPbsmrtpipeJobOptions], implicit val jwriter: JsonWriter[MockPbsmrtpipeJobOptions]) extends CommonJobsRoutes[MockPbsmrtpipeJobOptions] {
  override def jobTypeId = JobTypeIds.MOCK_PBSMRTPIPE
}


class PbsmrtpipeJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[PbsmrtpipeJobOptions], implicit val sm: Marshaller[PbsmrtpipeJobOptions], implicit val jwriter: JsonWriter[PbsmrtpipeJobOptions]) extends CommonJobsRoutes[PbsmrtpipeJobOptions] {
  override def jobTypeId = JobTypeIds.PBSMRTPIPE

  override def terminateJob(jobId: IdAble): Future[MessageResponse] = {
    for {
      engineJob <- dao.getJobById(jobId)
      message <- terminatePbsmrtpipeJob(engineJob)
    } yield message
  }

  private def failIfNotRunning(engineJob: EngineJob): Future[EngineJob] = {
    if (engineJob.isRunning) Future { engineJob }
    else Future.failed(new UnprocessableEntityError(s"Only terminating ${AnalysisJobStates.RUNNING} is supported"))
  }

  private def terminatePbsmrtpipeJob(engineJob: EngineJob): Future[MessageResponse] = {
    for {
      runningJob <- failIfNotRunning(engineJob)
      _ <- Future { PbsmrtpipeJobUtils.terminateJobFromDir(Paths.get(runningJob.path))} // FIXME Handle failure in better well defined model
    } yield MessageResponse(s"Attempting to terminate analysis job ${runningJob.id} in ${runningJob.path}")
  }
}


class RsConvertMovieToDataSetJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[RsConvertMovieToDataSetJobOptions], implicit val sm: Marshaller[RsConvertMovieToDataSetJobOptions], implicit val jwriter: JsonWriter[RsConvertMovieToDataSetJobOptions]) extends CommonJobsRoutes[RsConvertMovieToDataSetJobOptions] {
  override def jobTypeId = JobTypeIds.CONVERT_RS_MOVIE
}


class SimpleJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[SimpleJobOptions], implicit val sm: Marshaller[SimpleJobOptions], implicit val jwriter: JsonWriter[SimpleJobOptions]) extends CommonJobsRoutes[SimpleJobOptions] {
  override def jobTypeId = JobTypeIds.SIMPLE
}


class TsJobBundleJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[TsJobBundleJobOptions], implicit val sm: Marshaller[TsJobBundleJobOptions], implicit val jwriter: JsonWriter[TsJobBundleJobOptions]) extends CommonJobsRoutes[TsJobBundleJobOptions] {
  override def jobTypeId = JobTypeIds.TS_JOB
}


class TsSystemStatusBundleJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[TsSystemStatusBundleJobOptions], implicit val sm: Marshaller[TsSystemStatusBundleJobOptions], implicit val jwriter: JsonWriter[TsSystemStatusBundleJobOptions]) extends CommonJobsRoutes[TsSystemStatusBundleJobOptions] {
  override def jobTypeId = JobTypeIds.TS_SYSTEM_STATUS
}

// This is the used for the "Naked" untyped job route service <smrt-link>/<job-manager>/jobs
class NakedNoTypeJobsService(override val dao: JobsDao, override val authenticator: Authenticator, override val config: SystemJobConfig)(implicit val um: Unmarshaller[SimpleJobOptions], implicit val sm: Marshaller[SimpleJobOptions], implicit val jwriter: JsonWriter[SimpleJobOptions]) extends CommonJobsRoutes[SimpleJobOptions] {
  override def jobTypeId = JobTypeIds.SIMPLE
  override def createJob(opts: SimpleJobOptions, user: Option[UserRecord]): Future[EngineJob] =
    Future.failed(throw new UnprocessableEntityError("Unsupported Request for Job creation. Using specific job type endpoint"))

}

/**
  * This is factor-ish util to Adhere to the current SL System design.
  *
  * This has all the job related endpoints and a few misc routes.
  *
  * @param dao JobDao
  * @param authenticator Authenticator
  */
class JobsServiceUtils(dao: JobsDao, authenticator: Authenticator, config: SystemJobConfig)(implicit val actorSystem: ActorSystem) extends PacBioService
    with JobServiceConstants
    with FileAndResourceDirectives
    with LazyLogging {

  import SmrtLinkJsonProtocols._

  // For getFile and friends to work correctly
  implicit val routing = RoutingSettings.default

  override val manifest = PacBioComponentManifest(
    toServiceId("new_job_service"),
    "New Job Service",
    "0.1.0", "New Job Service")

  def getServiceJobs():Seq[JobServiceRoutes] = Seq(
    new DbBackupJobsService(dao, authenticator, config),
    new DeleteDataSetJobsService(dao, authenticator, config),
    new DeleteSmrtLinkJobsService(dao, authenticator, config),
    new ExportDataSetsJobsService(dao, authenticator, config),
    new ExportJobsService(dao, authenticator, config),
    new HelloWorldJobsService(dao, authenticator, config),
    new ImportBarcodeFastaJobsService(dao, authenticator, config),
    new ImportDataSetJobsService(dao, authenticator, config),
    new ImportFastaJobsService(dao, authenticator, config),
    new MergeDataSetJobsService(dao, authenticator, config),
    new MockPbsmrtpipeJobsService(dao, authenticator, config),
    new PbsmrtpipeJobsService(dao, authenticator, config),
    new RsConvertMovieToDataSetJobsService(dao, authenticator, config),
    new SimpleJobsService(dao, authenticator, config),
    new TsJobBundleJobsService(dao, authenticator, config),
    new TsSystemStatusBundleJobsService(dao, authenticator, config)
  )

  // Note these is duplicated within a Job
  def datastoreRoute(): Route = {
    pathPrefix(DATASTORE_FILES_PREFIX / JavaUUID) { dsFileUUID =>
        pathEndOrSingleSlash {
          get {
            complete {
              ok {
                dao.getDataStoreFile(dsFileUUID)
              }
            }
          } ~
              put {
                entity(as[DataStoreFileUpdateRequest]) { sopts =>
                  complete {
                    ok {
                      dao.updateDataStoreFile(dsFileUUID, sopts.path, sopts.fileSize, sopts.isActive)
                    }
                  }
                }
              }
        } ~
            path("download") {
              get {
                onSuccess(dao.getDataStoreFileByUUID(dsFileUUID)) { file =>
                  val fn = s"job-${file.jobId}-${file.uuid.toString}-${Paths.get(file.path).toAbsolutePath.getFileName}"
                  respondWithHeader(HttpHeaders.`Content-Disposition`("attachment; filename=" + fn)) {
                    getFromFile(file.path)
                  }
                }
              }
            } ~
            path("resources") {
              get {
                parameter("relpath") { relpath =>
                  onSuccess(dao.getDataStoreFileByUUID(dsFileUUID)) { file =>
                    val resourcePath = Paths.get(file.path).resolveSibling(relpath)
                    getFromFile(resourcePath.toFile)
                  }
                }
              }
            }
      }
    }

  def getJobTypesRoute(jobTypes: Seq[JobTypeIds.JobType]): Route = {
    val jobTypeEndPoints = jobTypes.map(x => JobTypeEndPoint(x.id, x.description))

    pathPrefix(JOB_TYPES_PREFIX) {
      pathEndOrSingleSlash {
        get {
          complete {
            jobTypeEndPoints
          }
        }
      }
    }
  }

  def getServiceJobRoutes(): Route = {

    // This will NOT be wrapped in a job-type prefix
    val nakedJob = new NakedNoTypeJobsService(dao, authenticator, config)

    // These will be wrapped with the a job-type-id specific prefix
    val jobs = getServiceJobs()

    // Unprefix Job (Meta) Type routes for each registered Job type
    val jobTypeRoutes:Route = getJobTypesRoute(jobs.map(_.jobTypeId))

    // Create all Job Routes with <job-type-id> prefix
    val rx = jobs.map(j => pathPrefix(j.jobTypeId.id) {j.routes}).reduce(_ ~ _)

    val allJobRoutes:Route = nakedJob.allIdAbleJobRoutes ~ rx

    val prefixedJobTypeRoutes = pathPrefix(ROOT_SERVICE_PREFIX / JOB_MANAGER_PREFIX) {jobTypeRoutes} ~  pathPrefix(ROOT_SL_PREFIX / JOB_MANAGER_PREFIX) {jobTypeRoutes}

    def jobWrap(jobRoutes: Route): Route = pathPrefix(ROOT_SERVICE_PREFIX / JOB_MANAGER_PREFIX / JOB_ROOT_PREFIX) { jobRoutes } ~ pathPrefix(ROOT_SL_PREFIX / JOB_MANAGER_PREFIX / JOB_ROOT_PREFIX) { jobRoutes }

    // Misc DataStore routes. This should probable migrated to a cleaner subroute.

    val prefixedDataStoreFileRoutes = pathPrefix(ROOT_SERVICE_PREFIX) {datastoreRoute()} ~ pathPrefix(ROOT_SL_PREFIX) {datastoreRoute()}

    // These need to be prefixed with secondary-analysis as well
    // Keep the backward compatibility of /smrt-link/ and /secondary-analysis root prefix
    prefixedJobTypeRoutes ~ jobWrap(allJobRoutes) ~ jobWrap(rx) ~ prefixedDataStoreFileRoutes
  }

  override def routes: Route = getServiceJobRoutes()

}

trait JobsServiceProvider {
  this: ActorRefFactoryProvider
      with ActorSystemProvider
      with AuthenticatorProvider
      with ServiceComposer
      with JobsDaoProvider
      with SmrtLinkConfigProvider =>

  //FIXME(mpkocher)(8-27-2017) Rename this to something sensible
  val newJobService: Singleton[JobsServiceUtils] = Singleton {() =>
    implicit val system = actorSystem()
    new JobsServiceUtils(jobsDao(), authenticator(), systemJobConfig())}

  addService(newJobService)
}