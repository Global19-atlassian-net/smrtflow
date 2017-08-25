
package com.pacbio.secondary.smrtlink.jobtypes

import java.net.URI
import java.nio.file.Path

import com.pacbio.secondary.smrtlink.actors.JobsDao
import com.pacbio.secondary.smrtlink.analysis.jobs.JobModels._
import com.pacbio.secondary.smrtlink.analysis.jobs.{AnalysisJobStates, JobResultWriter}
import com.pacbio.secondary.smrtlink.analysis.jobtypes.{PbSmrtPipeJobOptions => OldPbSmrtPipeJobOptions}
import com.pacbio.secondary.smrtlink.models.BoundServiceEntryPoint

/**
  * Created by mkocher on 8/17/17.
  */
case class PbsmrtpipeJobOptions(name: Option[String],
                                description: Option[String],
                                pipelineId: String,
                                entryPoints: Seq[BoundServiceEntryPoint],
                                taskOptions: Seq[ServiceTaskOptionBase],
                                workflowOptions: Seq[ServiceTaskOptionBase],
                                projectId: Option[Int] = Some(JobConstants.GENERAL_PROJECT_ID)) extends ServiceJobOptions {
  override def jobTypeId = JobTypeIds.PBSMRTPIPE
  override def validate() = None
  override def toJob() = new PbsmrtpipeJob(this)
}

class PbsmrtpipeJob(opts: PbsmrtpipeJobOptions) extends ServiceCoreJob(opts){
  type Out = PacBioDataStore



  override def run(resources: JobResourceBase, resultsWriter: JobResultWriter, dao: JobsDao): Either[ResultFailed, PacBioDataStore] = {

    //FIXME
    val entryPoints: Seq[BoundEntryPoint] = Seq.empty[BoundEntryPoint]

    // These need to be pulled from the System config
    val envPath: Option[Path] = None
    val serviceURI: Option[URI] = None

    val oldOpts = OldPbSmrtPipeJobOptions(opts.pipelineId, entryPoints, opts.taskOptions, opts.workflowOptions, envPath, serviceURI, None, opts.getProjectId())
    val job = oldOpts.toJob
    job.run(resources, resultsWriter)
  }
}
