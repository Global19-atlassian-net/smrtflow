package com.pacbio.secondary.smrtlink.mail

import com.pacbio.secondary.analysis.jobs.AnalysisJobStates

import scalatags.Text.all._

/**
  * Created by mkocher on 7/21/17.
  */
object Templates {

  trait EmailTemplate[T] {
    def apply(emailInput: T): EmailTemplateResult
  }

  trait SmrtLinkEmailTemplate extends EmailTemplate[SmrtLinkEmailInput] {

    def toSubject(emailInput: SmrtLinkEmailInput): String = ""

    def apply(emailInput: SmrtLinkEmailInput) = {
      val result = html(
        body(
          div(
            toUser(emailInput),
            br(),
            toStatusMessage(emailInput),
            br(),
            toSummary(emailInput),
            br(),
            toJobLink(emailInput),
            toFooter(emailInput)
          )
        )
      )

      EmailTemplateResult(toSubject(emailInput), result.toString())
    }
  }

  private def toUser(input: SmrtLinkEmailInput) = p(s"Dear ${input.emailAddress},")

  private def toStatusMessage(input: SmrtLinkEmailInput) = {
    val msg = if (AnalysisJobStates.isSuccessful(input.jobState)) {
      "Your analysis job has successfully completed."
    } else {
      "Your analysis job has Failed."
    }
    p(msg)
  }


  private def toJobLink(input: SmrtLinkEmailInput) = {
    val m = if (AnalysisJobStates.isSuccessful(input.jobState)) br() else toTechSupportSummary(input)

    p(s"Please visit the following link to view the results: ",
      a(href := s"${input.jobURL}", " [Link] "),
      m)
  }

  private def toSummary(input: SmrtLinkEmailInput) =
    p(
      ul(
        li(s"Job ID: ${input.jobId}"),
        li(s"Job Name: ${input.jobName}"),
        li(s"Start  time: ${input.createdAt}"),
        li(s"Finish time: ${input.completedAt}")
      )
    )

  private def toTechSupportSummary(input: SmrtLinkEmailInput) =
    p("For troubleshooting assistance with this run : ",
      br(),
      ol(
        li("File a case by emailing support@pacb.com. You will receive an autogenerated PacBio customer portal case number."),
        li("Click Send Log Files at the SMRT Link page of the failed analysis to send the log files and case number to PacBio technical support.")
      )
    )

  private def toFooter(input: SmrtLinkEmailInput) =
      p(s"Powered by SMRT Link ${input.smrtLinkVersion.getOrElse("")}",
        br(),
        "Pacific Biosciences of California, Inc.")



  object EmailJobSuccessTemplate extends SmrtLinkEmailTemplate {
    override def toSubject(emailInput: SmrtLinkEmailInput): String =
      s"SMRT Link Job ${emailInput.jobId} Successfully Completed: ${emailInput.jobName}"
  }

  object EmailJobFailedTemplate extends SmrtLinkEmailTemplate {
    override def toSubject(emailInput: SmrtLinkEmailInput): String =
      s"SMRT Link Job ${emailInput.jobId} Failed ${emailInput.jobName}"
  }

}
