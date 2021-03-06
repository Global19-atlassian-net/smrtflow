package com.pacbio.secondary.smrtlink.app

import java.net.URL
import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import com.pacbio.secondary.smrtlink.actors.DalProvider
import com.pacbio.secondary.smrtlink.dependency.Singleton
import com.pacbio.secondary.smrtlink.analysis.configloaders.{
  EngineCoreConfigLoader,
  PbsmrtpipeConfigLoader
}
import com.pacbio.secondary.smrtlink.analysis.jobs.{
  JobResourceResolver,
  PacBioIntJobResolver
}
import com.pacbio.secondary.smrtlink.analysis.pbsmrtpipe.{
  CommandTemplate,
  PbsmrtpipeEngineOptions
}
import com.pacbio.secondary.smrtlink.io.PacBioDataBundleIOUtils
import com.pacbio.secondary.smrtlink.loaders.ManifestLoader
import com.pacbio.secondary.smrtlink.models.ConfigModels.{
  MailConfig,
  SystemJobConfig
}
import com.pacbio.secondary.smrtlink.models.{EngineConfig, PacBioDataBundleIO}
import com.pacbio.secondary.smrtlink.utils.SmrtServerIdUtils
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

trait SmrtLinkConfigProvider extends SmrtServerIdUtils with LazyLogging {
  this: PbsmrtpipeConfigLoader with EngineCoreConfigLoader with DalProvider =>

  /**
    * Create a Directories (mkdir -p) if they don't exist.
    *
    * @param p
    * @return
    */
  def createDirIfNotExist(p: Path): Path = {
    if (!Files.exists(p)) {
      Files.createDirectories(p)
      logger.info(s"created dir $p")
    }
    p
  }

  val chemistryBundleId = "chemistry-pb"

  val serverId: Singleton[UUID] = Singleton(() => getSystemUUID(conf))

  val port: Singleton[Int] = Singleton(
    () => conf.getInt("smrtflow.server.port"))
  val host: Singleton[String] = Singleton(
    () => conf.getString("smrtflow.server.host"))
  val dnsName: Singleton[Option[String]] = Singleton(
    () => Try { conf.getString("smrtflow.server.dnsName") }.toOption)

  // See comments for systemJobConfig. This "EngineConfig" should be replaced with SystemJobConfig
  val jobEngineConfig: Singleton[EngineConfig] = Singleton(() => engineConfig)
  val cmdTemplate: Singleton[Option[CommandTemplate]] = Singleton(
    () => loadCmdTemplate)
  val pbsmrtpipeEngineOptions: Singleton[PbsmrtpipeEngineOptions] =
    Singleton(() => loadPbsmrtpipeEngineConfigOrDefaults)
  val jobResolver: Singleton[JobResourceResolver] =
    Singleton(() => new PacBioIntJobResolver(jobEngineConfig().pbRootJobDir))

  // Unfortunately this is duplicated in the Manifest service
  val smrtLinkVersion: Singleton[Option[String]] =
    Singleton(
      () =>
        ManifestLoader
          .loadFromConfig(conf)
          .toList
          .find(_.id == ManifestLoader.SMRTLINK_ID)
          .map(_.version))

  val pacBioBundleRoot: Singleton[Path] =
    Singleton(
      () =>
        createDirIfNotExist(
          getPath("smrtflow.server.bundleDir").toAbsolutePath()))

  val pacBioBundles: Singleton[Seq[PacBioDataBundleIO]] =
    Singleton(
      () => PacBioDataBundleIOUtils.loadBundlesFromRoot(pacBioBundleRoot()))

  // Optional SMRT Link System level Root Dir e.g., /path/to/smrtsuite/
  val smrtLinkSystemRoot: Singleton[Option[Path]] =
    Singleton(
      () => Try { getPath("pacBioSystem.smrtLinkSystemRoot") }.toOption)

  // To ensure the entire application is configured with the correct temp directory
  // the standard *nix approach should be used. Export TMPDIR, TMP or TMP_DIR
  // before starting the services
  val smrtLinkTempDir: Singleton[Path] =
    Singleton(
      () =>
        Try(getPath("smrtflow.pacBioSystem.tmpDir")).toOption
          .getOrElse(Paths.get("/tmp")))

  private def getPath(keyName: String): Path =
    Paths.get(conf.getString(keyName))

  /**
    * This will load the key and convert to URL.
    * Any errors will *only* be logged. This is probably not the best model.
    *
    * @return
    */
  private def loadUrl(key: String): Option[URL] = {
    Try { new URL(conf.getString(key)) } match {
      case Success(url) =>
        logger.info(s"Converted $key to URL $url")
        Some(url)
      case Failure(ex) =>
        logger.error(s"Failed to load URL from key '$key' ${ex.getMessage}")
        None
    }
  }

  val externalEveUrl: Singleton[Option[URL]] =
    Singleton(() => loadUrl("smrtflow.server.eventUrl"))

  val externalBundleUrl: Singleton[Option[URL]] = {
    Singleton(() => loadUrl("pacBioSystem.remoteBundleUrl"))
  }

  val externalBundlePollDuration: Singleton[FiniteDuration] = {
    Singleton(
      () =>
        FiniteDuration(
          Try(conf.getInt("pacBioSystem.remoteBundlePollHours")).getOrElse(12),
          HOURS))
  }

  val swaggerResource: Singleton[String] =
    Singleton(() => "smrtlink_swagger.json")

  val apiSecret: Singleton[String] =
    Singleton(() => conf.getString("smrtflow.event.apiSecret"))

  private def getAndCreateIfProvided(sx: String): Option[Path] = {
    Try { Paths.get(conf.getString(sx)) }.toOption.map(createDirIfNotExist)
  }

  // This directory creation might be better to be done at the App startup + validation step
  val rootDataBaseDir: Singleton[Option[Path]] =
    Singleton(() => getAndCreateIfProvided("pacBioSystem.pgDataDir"))

  val rootDataBaseBackUpDir: Singleton[Option[Path]] =
    Singleton(() =>
      Try {
        Paths.get(conf.getString("pacBioSystem.pgDataDir")).resolve("backups")
      }.toOption.map(createDirIfNotExist))

  // Centralizing this here so it's not hardcoded randomly in different places. This is the SMRT Link UI PORT that is
  // host via https
  val smrtLinkUiPort: Singleton[Int] = Singleton(() => 8243)

  // There's some duplication here. and translation from
  val systemJobConfig: Singleton[SystemJobConfig] = Singleton { () =>
    SystemJobConfig(
      pbsmrtpipeEngineOptions(),
      dnsName().getOrElse("localhost"),
      port(),
      smrtLinkVersion(),
      serverId(),
      smrtLinkSystemRoot(),
      engineConfig.maxWorkers,
      engineConfig.numQuickWorkers,
      externalEveUrl(),
      rootDataBaseBackUpDir(),
      dbConfigSingleton(),
      mailConfig(),
      smrtLinkUiPort(),
      apiSecret()
    )
  }

  val multiJobPoll: Singleton[Duration] =
    Singleton(() => conf.getInt("smrtflow.engine.multiJobPoll").seconds)
  val multiJobWorkerPoll: Singleton[Duration] =
    Singleton(() => conf.getInt("smrtflow.engine.multiJobWorkerPoll").seconds)

  val mailConfig: Singleton[Option[MailConfig]] = Singleton { () =>
    mailHost().map(h => MailConfig(h, mailPort(), mailUser(), mailPassword()))
  }

  // Mail Specific Config
  val mailHost: Singleton[Option[String]] =
    Singleton(() => Try { conf.getString("pacBioSystem.mailHost") }.toOption)
  val mailPort: Singleton[Int] =
    Singleton(() =>
      Try { conf.getInt("pacBioSystem.mailPort") }.toOption.getOrElse(25))
  val mailUser: Singleton[Option[String]] =
    Singleton(() => Try { conf.getString("pacBioSystem.mailUser") }.toOption)
  val mailPassword: Singleton[Option[String]] =
    Singleton(
      () => Try { conf.getString("pacBioSystem.mailPassword") }.toOption)

}
