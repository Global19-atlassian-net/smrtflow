import com.pacbio.secondary.smrtlink.io.PacBioDataBundleIOUtils
import com.pacbio.secondary.smrtlink.models.PacBioDataBundle
import org.specs2.mutable.Specification
import org.joda.time.{DateTime => JodaDateTime}

class PacBioBundleUtilsSpec extends Specification{

  "Bundle Utils tests" should {
    "Sort bundles by semVer" in {

      def toBundle(v: String) = PacBioDataBundle("a", v, JodaDateTime.now(), None)

      val bundles = Seq("1.2.4", "1.0.0", "1.1.0", "1.0.1", "1.3.1").map(toBundle)

      val newestBundle = PacBioDataBundleIOUtils.getNewestBundleVersionByType(bundles, "a")

      newestBundle.map(_.version) must beSome("1.3.1")
    }
  }

}
