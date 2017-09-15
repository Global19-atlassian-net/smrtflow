/**
  * NOTE: This class is auto generated by the akka-scala (beta) swagger code generator program.
  * https://github.com/swagger-api/swagger-codegen
  * For any issue or feedback, please open a ticket via https://github.com/swagger-api/swagger-codegen/issues/new
  */
package org.wso2.carbon.apimgt.rest.api.store.models

import org.joda.time.DateTime

case class Error(code: Long,
                 /* Error message. */
                 message: String,
                 /* A detail description about the error message.  */
                 description: Option[String],
                 /* Preferably an url with more details about the error.  */
                 moreInfo: Option[String],
                 /* If there are more than one error list them out. For example, list out validation errors by each field.  */
                 error: Option[Seq[ErrorListItem]])
