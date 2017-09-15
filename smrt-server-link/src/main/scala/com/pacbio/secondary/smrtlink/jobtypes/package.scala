package com.pacbio.secondary.smrtlink

import java.io.{PrintWriter, StringWriter}
import java.net.InetAddress

import com.pacbio.secondary.smrtlink.actors.CommonMessages.MessageResponse
import com.pacbio.secondary.smrtlink.actors.JobsDao
import com.pacbio.secondary.smrtlink.analysis.jobs.JobModels._
import com.pacbio.secondary.smrtlink.analysis.jobs.{
  InvalidJobOptionError,
  JobResultWriter,
  CoreJobModel
}
import com.pacbio.secondary.smrtlink.jsonprotocols.{
  ServiceJobTypeJsonProtocols,
  SmrtLinkJsonProtocols
}
import com.pacbio.secondary.smrtlink.models.ConfigModels.SystemJobConfig
import com.pacbio.secondary.smrtlink.models.{
  BoundServiceEntryPoint,
  EngineJobEntryPointRecord
}
import com.pacbio.secondary.smrtlink.validators.ValidateServiceDataSetUtils
import com.typesafe.scalalogging.LazyLogging
import spray.json._

import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
  * Created by mkocher on 8/17/17.
  */
package object jobtypes {

  trait ServiceCoreJobModel extends LazyLogging {
    type Out
    val jobTypeId: JobTypeIds.JobType

    // This should be rethought
    def host = InetAddress.getLocalHost.getHostName

    /**
      * The Service Job has access to the DAO, but should not update or mutate the state of the current job (or any
      * other job). The ServiceRunner and EngineWorker actor will handle updating the state of the job.
      *
      * At the job level, the job is responsible for importing any data back into the system and sending
      * "Status" update events.
      *
      * @param resources     Resources for the Job to use (e.g., job id, root path) This needs to be expanded to include the system config.
      *                      This be renamed for clarity
      * @param resultsWriter Writer to write to job stdout and stderr
      * @param dao           interface to the DB. See above comments on suggested use and responsibility
      * @param config        System Job config. Any specific config for any job type needs to be pushed into this layer
      * @return
      */
    def run(resources: JobResourceBase,
            resultsWriter: JobResultWriter,
            dao: JobsDao,
            config: SystemJobConfig): Either[ResultFailed, Out]

    // Util layer to get the ServiceJobRunner to compose better.
    def runTry(resources: JobResourceBase,
               resultsWriter: JobResultWriter,
               dao: JobsDao,
               config: SystemJobConfig): Try[Out] = {
      Try {
        run(resources, resultsWriter, dao, config)
      } match {
        case Success(result) =>
          result match {
            case Right(rx) => Success(rx)
            case Left(rx) =>
              val msg = s"Failed to run job ${rx.message}"
              resultsWriter.writeLineError(msg)
              Failure(new Exception(msg))
          }
        case Failure(ex) =>
          val msg = s"Failed to run job ${ex.getMessage}"
          resultsWriter.writeLineError(msg)
          val sw = new StringWriter
          ex.printStackTrace(new PrintWriter(sw))
          resultsWriter.writeLineError(sw.toString)
          Failure(new Exception(msg))
      }
    }

    def runAndBlock[T](fx: Future[T], timeOut: FiniteDuration): Try[T] =
      Try {
        Await.result(fx, timeOut)
      }

  }

  trait ServiceJobOptions {
    // This metadata will be used when creating an instance of a EngineJob
    val name: Option[String]
    val description: Option[String]
    val projectId: Option[Int]

    // This is duplicated with projectId because of JSON optional options. It would be better if
    // "projectId" was private.
    def getProjectId(): Int =
      projectId.getOrElse(JobConstants.GENERAL_PROJECT_ID)

    // This needs to be defined at the job option level to be a globally unique type.
    def jobTypeId: JobTypeIds.JobType

    // This is a def for seralization reasons.
    def toJob(): ServiceCoreJob

    /**
      * This the default timeout for DAO operations.
      *
      * It's important this is a def, otherwise there will be runtime errors from spray serialization
      *
      * @return
      */
    def DEFAULT_TIMEOUT = 10.seconds

    /**
      * Job Option validation
      *
      * This should be relatively quick (e.g., not validation of 1G fasta file)
      *
      * Any time or resource consuming validation should be pushed to job run time.
      *
      *
      * TODO: Does this also need UserRecord passed in?
      *
      * Validate the Options (and make sure they're consistent within the system config if necessary)
      *
      * @return
      */
    def validate(dao: JobsDao,
                 config: SystemJobConfig): Option[InvalidJobOptionError]

    /**
      * Validation func for resolving a future and translating any errors to
      * the InvalidJobOption error.
      *
      * @param fx      Func to run
      * @param timeout timeout
      * @tparam T
      * @return
      */
    def validateOptionsAndBlock[T](
        fx: => Future[T],
        timeout: FiniteDuration): Option[InvalidJobOptionError] = {
      Try(Await.result(fx, timeout)) match {
        case Success(_) => None
        case Failure(ex) =>
          Some(
            InvalidJobOptionError(
              s"Failed option validation. ${ex.getMessage}"))
      }
    }

    /**
      * Common Util for resolving entry points
      *
      * Only DataSet types will be resolved, but the entry point is a general file type
      *
      * @param e   Bound Service Entry Point
      * @param dao db DAO
      * @return
      */
    def resolveEntry(
        e: BoundServiceEntryPoint,
        dao: JobsDao): Future[(EngineJobEntryPointRecord, BoundEntryPoint)] = {
      // Only DataSet types will be resolved, but the entry point is a general file type id

      for {
        datasetType <- ValidateServiceDataSetUtils.validateDataSetType(
          e.fileTypeId)
        d <- ValidateServiceDataSetUtils.resolveDataSet(datasetType,
                                                        e.datasetId,
                                                        dao)
      } yield
        (EngineJobEntryPointRecord(d.uuid, e.fileTypeId),
         BoundEntryPoint(e.entryId, d.path))
    }

    def resolver(entryPoints: Seq[BoundServiceEntryPoint], dao: JobsDao)
      : Future[Seq[(EngineJobEntryPointRecord, BoundEntryPoint)]] =
      Future.sequence(entryPoints.map(ep => resolveEntry(ep, dao)))

    /**
      * This is used to communicate the EntryPoints used for the Job. This is abstract so that the model is explicit.
      *
      * @param dao JobsDoa
      * @return
      */
    def resolveEntryPoints(dao: JobsDao): Seq[EngineJobEntryPointRecord] =
      Seq.empty[EngineJobEntryPointRecord]
  }

  abstract class ServiceCoreJob(opts: ServiceJobOptions)
      extends ServiceCoreJobModel {
    // sugar
    val jobTypeId = opts.jobTypeId
  }

  // Use to encode a multi job type
  trait MultiJob

  trait ServiceMultiJobOptions extends ServiceJobOptions with MultiJob {
    // fighting with the type system here
    def toMultiJob(): ServiceMultiJobModel
  }

  abstract class ServiceMultiJob(opts: ServiceMultiJobOptions)
      extends ServiceCoreJobModel {
    val jobTypeId = opts.jobTypeId
  }

  trait ServiceMultiJobModel extends ServiceCoreJobModel {

    // This needs to be removed, or fixed. This is necessary for multi-jobs and is not used. `runWorkflow` is the method to call
    override def run(resources: JobResourceBase,
                     resultsWriter: JobResultWriter,
                     dao: JobsDao,
                     config: SystemJobConfig) = {
      throw new Exception("Direct call of Run on MultiJob is not supported")
    }

    /**
      * This is the core method that is called to run a Multi-Job. This should be completely self contained
      * and omnipotent.
      */
    def runWorkflow(engineJob: EngineJob,
                    resources: JobResourceBase,
                    resultsWriter: JobResultWriter,
                    dao: JobsDao,
                    config: SystemJobConfig): Future[MessageResponse]
  }

  trait Converters {

    import SmrtLinkJsonProtocols._
    import ServiceJobTypeJsonProtocols._

    /**
      * Load the JSON Settings from an Engine job and create the companion ServiceJobOption
      * instance.
      *
      * If there's a deseralization issue, this will raise.
      *
      * @param engineJob EngineJob
      * @tparam T ServiceJobOptions
      * @return
      */
    def convertServiceCoreJobOption[T >: ServiceJobOptions](
        engineJob: EngineJob): T = {

      val jx = engineJob.jsonSettings.parseJson

      // The EngineJob data model should be using a proper type
      val jobTypeId: JobTypeIds.JobType = JobTypeIds
        .fromString(engineJob.jobTypeId)
        .getOrElse(
          throw new IllegalArgumentException(
            s"Job type '${engineJob.jobTypeId}' is not supported"))

      convertToOption[T](jobTypeId, jx)
    }

    private def convertToOption[T >: ServiceJobOptions](
        jobTypeId: JobTypeIds.JobType,
        jx: JsValue): T = {

      jobTypeId match {
        case JobTypeIds.HELLO_WORLD => jx.convertTo[HelloWorldJobOptions]
        case JobTypeIds.DB_BACKUP => jx.convertTo[DbBackUpJobOptions]
        case JobTypeIds.DELETE_DATASETS =>
          jx.convertTo[DeleteDataSetJobOptions]
        case JobTypeIds.EXPORT_DATASETS =>
          jx.convertTo[ExportDataSetsJobOptions]
        case JobTypeIds.EXPORT_JOBS => jx.convertTo[ExportSmrtLinkJobOptions]
        case JobTypeIds.CONVERT_FASTA_BARCODES =>
          jx.convertTo[ImportBarcodeFastaJobOptions]
        case JobTypeIds.IMPORT_DATASET => jx.convertTo[ImportDataSetJobOptions]
        case JobTypeIds.CONVERT_FASTA_REFERENCE =>
          jx.convertTo[ImportFastaJobOptions]
        case JobTypeIds.MERGE_DATASETS => jx.convertTo[MergeDataSetJobOptions]
        case JobTypeIds.MOCK_PBSMRTPIPE =>
          jx.convertTo[MockPbsmrtpipeJobOptions]
        case JobTypeIds.PBSMRTPIPE => jx.convertTo[PbsmrtpipeJobOptions]
        case JobTypeIds.SIMPLE => jx.convertTo[SimpleJobOptions]
        case JobTypeIds.CONVERT_RS_MOVIE =>
          jx.convertTo[RsConvertMovieToDataSetJobOptions]
        case JobTypeIds.DELETE_JOB => jx.convertTo[DeleteSmrtLinkJobOptions]
        case JobTypeIds.TS_JOB => jx.convertTo[TsJobBundleJobOptions]
        case JobTypeIds.TS_SYSTEM_STATUS =>
          jx.convertTo[TsSystemStatusBundleJobOptions]
        // These really need to be separated out into there own class
        case JobTypeIds.MJOB_MULTI_ANALYSIS =>
          jx.convertTo[MultiAnalysisJobOptions]
      }
    }

    private def convertToMultiCoreJobOptions[T >: ServiceMultiJobOptions](
        jobTypeId: JobTypeIds.JobType,
        jx: JsValue): T = {

      //FIXME(mpkocher)(9-12-2017) need to fix this at the type level
      jobTypeId match {
        case JobTypeIds.MJOB_MULTI_ANALYSIS =>
          jx.convertTo[MultiAnalysisJobOptions]
        case x =>
          throw new IllegalArgumentException(
            s"Job Type id '$x' is not a supported MultiJob type")
      }
    }

    def convertServiceMultiJobOption[T >: ServiceMultiJobOptions](
        engineJob: EngineJob): T = {
      val jx = engineJob.jsonSettings.parseJson

      // The EngineJob data model should be using a proper type
      val jobTypeId: JobTypeIds.JobType = JobTypeIds
        .fromString(engineJob.jobTypeId)
        .getOrElse(
          throw new IllegalArgumentException(
            s"Job type '${engineJob.jobTypeId}' is not supported"))

      convertToMultiCoreJobOptions[T](jobTypeId, jx)
    }
  }

  object Converters extends Converters

}
