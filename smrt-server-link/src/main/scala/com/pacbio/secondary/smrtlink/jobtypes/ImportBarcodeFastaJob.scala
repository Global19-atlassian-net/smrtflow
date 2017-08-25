package com.pacbio.secondary.smrtlink.jobtypes

import java.nio.file.{Path, Paths}
import java.util.UUID

import com.pacbio.secondary.smrtlink.actors.JobsDao
import com.pacbio.secondary.smrtlink.analysis.jobs.JobModels.JobConstants.GENERAL_PROJECT_ID
import com.pacbio.secondary.smrtlink.analysis.jobs.JobModels._
import com.pacbio.secondary.smrtlink.analysis.jobs.JobResultWriter
import com.pacbio.secondary.smrtlink.analysis.jobtypes.{ConvertImportFastaBarcodesOptions, MockJobUtils}
import com.pacbio.secondary.smrtlink.analysis.tools.timeUtils
import com.typesafe.scalalogging.LazyLogging
import org.joda.time.{DateTime => JodaDateTime}
import spray.json._

import scala.util.{Failure, Success, Try}


//FIXME(mpkocher)(8-17-2017) There's a giant issue with the job "name" versus "name" used in job options.
case class ImportBarcodeFastaJobOptions(path: String, name: Option[String], description: Option[String],
                                        projectId: Option[Int] = Some(JobConstants.GENERAL_PROJECT_ID)
                                       ) extends ServiceJobOptions {
  override def jobTypeId = JobTypeIds.CONVERT_FASTA_BARCODES
  override def validate() = None
  override def toJob() = new ImportBarcodeFastaJob(this)
}

class ImportBarcodeFastaJob(opts: ImportBarcodeFastaJobOptions) extends ServiceCoreJob(opts){
  type Out = PacBioDataStore
  override def run(resources: JobResourceBase, resultsWriter: JobResultWriter, dao: JobsDao): Either[ResultFailed, PacBioDataStore] = {
    // Shim layer
    val name = opts.name.getOrElse("Fasta-Barcodes")
    val projectId = opts.projectId.getOrElse(GENERAL_PROJECT_ID)
    val oldOpts = ConvertImportFastaBarcodesOptions(name, opts.path, projectId)
    val job = oldOpts.toJob
    job.run(resources, resultsWriter)
  }
}