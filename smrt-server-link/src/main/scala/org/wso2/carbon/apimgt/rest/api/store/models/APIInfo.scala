/**
  * NOTE: This class is auto generated by the akka-scala (beta) swagger code generator program.
  * https://github.com/swagger-api/swagger-codegen
  * For any issue or feedback, please open a ticket via https://github.com/swagger-api/swagger-codegen/issues/new
  */
package org.wso2.carbon.apimgt.rest.api.store.models

import org.joda.time.DateTime

case class APIInfo(id: Option[String],
                   name: Option[String],
                   description: Option[String],
                   context: Option[String],
                   version: Option[String],
                   /* If the provider value is not given, the user invoking the API will be used as the provider.  */
                   provider: Option[String],
                   status: Option[String])
