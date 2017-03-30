import com.pacbio.common.models.ServiceStatus
import com.pacbio.secondary.smrtlink.models.{PacBioDataBundle, SmrtLinkJsonProtocols}
import com.pacbio.secondary.smrtlink.app.{SmrtLinkApi, SmrtLinkProviders}
import org.specs2.mutable.Specification
import spray.testkit.Specs2RouteTest
import spray.httpx.SprayJsonSupport._


class PacBioBundleServiceSpec extends Specification with Specs2RouteTest {

  object Api extends SmrtLinkApi {
    override val providers = new SmrtLinkProviders {}
    val eventManagerActorX = providers.eventManagerActor()
  }

  val routes = Api.routes

  import SmrtLinkJsonProtocols._

  "Bundle Service tests" should {
    "Uptime should be >0" in {
      Get("/status") ~> routes ~> check {
        val status = responseAs[ServiceStatus]
        // Uptime is in sec, not millisec
        // this is the best we can do
        status.uptime must be_>=(0L)
      }
    }
    "Bundle Sanity check" in {
      Get("/smrt-link/bundles") ~> routes ~> check {
        val bundles = responseAs[Seq[PacBioDataBundle]]
        //println(s"All loaded bundles $bundles")
        status.isSuccess must beTrue
      }
    }
    "Get bundle type id 'chemistry' " in {
      Get("/smrt-link/bundles/chemistry") ~> routes ~> check {
        val bundles = responseAs[Seq[PacBioDataBundle]]
        println(s"Example bundles $bundles")
        status.isSuccess must beTrue
      }
    }
    "Get lastest bundle type id 'chemistry' " in {
      Get("/smrt-link/bundles/chemistry/latest") ~> routes ~> check {
        status.isSuccess must beTrue
      }
    }
    "Get bundle type id 'chemistry' by version id" in {
      Get("/smrt-link/bundles/chemistry/0.1.2") ~> routes ~> check {
        status.isSuccess must beTrue
      }
    }
  }
}
