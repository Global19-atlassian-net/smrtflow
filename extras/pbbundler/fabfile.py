#!/usr/bin/env python
"""Main Point of Entry for Creating versioned Bundles


sbt, nodejs, npm, java1.8, python2.7

This assumes that the Perforce Workspace has both the UI and services root dirs

Specifically,

//depot/software/smrtanalysis/services-ui/scala/
//ui/main/... //mkocher_server_sa3_demo/ui/curbranch/...

"""
import sys
import re
import logging
import shutil
import os
import json
import warnings
import time
import subprocess
import xml.dom.minidom
from zipfile import ZipFile
from distutils.dir_util import copy_tree

from fabric.api import lcd, task
from fabric.api import local as flocal


log = logging.getLogger(__name__)
logging.basicConfig(level=logging.DEBUG)

__author__ = "M. Kocher"

# Root directory of the project, location of the bundle directory templates
_ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
# Location of installed bundles
_ROOT_BUILT_BUNDLES = os.path.join(_ROOT_DIR, 'built-bundles')
_RESOURCES_DIR = os.path.join(_ROOT_DIR, 'resources')
_SL_ROOT = os.path.dirname(os.path.dirname(os.path.join(_ROOT_DIR)))
_SL_SYSTEM_AVSC = os.path.join(_SL_ROOT, "SmrtLinkSystemConfig.avsc")
_STATIC_FILE_DIR = "sl"

_LOG_FORMAT = '[%(levelname)s] %(asctime)-15s [%(name)s %(funcName)s %(lineno)d] %(message)s'


def _to_sbt_cmd(ivy_cache=None):
    if ivy_cache is None:
        return "sbt -no-colors -batch "
    else:
        return "sbt -Dsbt.ivy.home={s} -no-colors -batch ".format(s=ivy_cache)


def setup_log(alog, level=logging.INFO, file_name=None, log_filter=None, str_formatter=_LOG_FORMAT):
    alog.setLevel(logging.DEBUG)
    if file_name is None:
        handler = logging.StreamHandler(sys.stdout)
    else:
        handler = logging.FileHandler(file_name)

    formatter = logging.Formatter(str_formatter)
    handler.setFormatter(formatter)
    handler.setLevel(level)

    if log_filter:
        handler.addFilter(log_filter)

    alog.addHandler(handler)

    return alog


def local(*args, **kwargs):
    """
    Override the fabric command.
    Log local command and runtime and results

    """
    started_at = time.time()
    log.info("Running {a} kw:{k}".format(a=args, k=kwargs))
    x = flocal(*args, **kwargs)
    run_time = time.time() - started_at
    log.info("Completed running cmd:{c} in {r:.2f} sec Result '{x}'".format(r=run_time, c=args, x=x))
    return x


class PacBioVersion(object):
    def __init__(self, idx, name, version, description, dependencies=None):
        self.idx = idx
        self.version = version
        self.name = name
        self.description = description
        self.dependencies = [] if dependencies is None else list(set(dependencies))

    def __repr__(self):
        d = self.to_dict()
        d['k'] = self.__class__.__name__
        return "<{k} id={id} name={name} version={version} >".format(**d)

    @staticmethod
    def from_d(d):
        return PacBioVersion(d['id'], d['name'], d['version'], d['description'], d['dependencies'])

    def to_dict(self):
        return dict(id=self.idx,
                    name=self.name,
                    version=self.version,
                    description=self.description,
                    dependencies=self.dependencies)


def write_pacbio_versions(pacbio_versions, output_json):

    d = [p.to_dict() for p in pacbio_versions]

    with open(output_json, 'w') as f:
        f.write(json.dumps(d))

    return d


def load_pacbio_versions(path):
    with open(path, 'r') as f:
        d = json.loads(f.read())

    # handle a list of values (correct form) versus an object
    if isinstance(d, (list, tuple)):
        return [PacBioVersion.from_d(x) for x in d]
    else:
        return [PacBioVersion.from_d(d)]


class Constants(object):
    # This is not correct. The SLS + UI will be 'minimal' dataset job types
    # not the complete Secondary analysis services
    SLS_UI = "smrtlink_services_ui"
    SLS = "smrtlink_services"
    SLC = "smrtlink_common_services"
    # This will be the complete secondary analysis services and job types
    SLAS_UI = "smrtlink_analysis_services_ui"


class PbConstants(object):
    # FIle in the bundle
    MANIFEST_FILE = "pacbio-manifest.json"
    # Build log in the bundle
    BUILD_LOG = "build.log"
    # Tomcat Users XML
    TCAT_USERS = "tomcat-users.xml"


def _raise_if_not_exists(path, custom_message=None):
    m = "" if custom_message is None else custom_message
    msg = "{m} Unable to find {p}".format(m=m, p=path)
    if not os.path.exists(path):
        raise IOError(msg)


def _raise_non_none_if_not_exists(path_or_none, custom_message=None):
    if path_or_none is not None:
        return _raise_if_not_exists(path_or_none, custom_message=custom_message)


def _copy_and_extract_tomcat(tomcat_tgz, output_dir):

    name = os.path.basename(tomcat_tgz)

    rx = re.compile('apache-tomcat-\d+.\d+.\d+')
    m = re.match(rx, name)

    if m is None:
        raise ValueError("Unable to determine tomcat name from {f} using regex {r}".format(f=tomcat_tgz, r=rx.pattern))

    tomcat_output = os.path.join(output_dir, m.group())

    with lcd(output_dir):
        if not os.path.exists(tomcat_output):
            local("tar xvfz {p}".format(p=tomcat_tgz))
        else:
            log.debug("Tomcat already exists {}, skipping copying".format(tomcat_output))

    return tomcat_output


def _archive_tomcat_webapp_root(tomcat_output_dir):
    webapp_path = os.path.join(tomcat_output_dir, 'webapps')
    shutil.move(os.path.join(webapp_path, 'ROOT'), os.path.join(webapp_path, 'ROOT.bak'))
    os.mkdir(os.path.join(webapp_path, 'ROOT'))


def _copy_chemistry_bundle(chemistry_bundle_dir, dest_dir):
    log.info("Copying chemistry bundle from {b}".format(b=chemistry_bundle_dir))
    xml_file = os.path.join(chemistry_bundle_dir, "manifest.xml")
    manifest = xml.dom.minidom.parse(xml_file)
    vtags = manifest.getElementsByTagName("Version")
    assert len(vtags) == 1
    version = str(vtags[0].lastChild.nodeValue)
    res_bundle_dir = os.path.join(dest_dir, "resources", "pacbio-bundles")
    if not os.path.exists(res_bundle_dir):
        os.makedirs(res_bundle_dir)
    target_dir = os.path.join(res_bundle_dir, "chemistry-{v}".format(v=version))
    current_link = os.path.join(res_bundle_dir, "chemistry-active")
    tarball = target_dir + ".tar.gz"
    if os.path.exists(target_dir):
        shutil.rmtree(target_dir)
    for pathname in [current_link, tarball]:
        if os.path.lexists(pathname):
            log.info("deleting old {f}".format(f=pathname))
            os.remove(pathname)
    shutil.copytree(chemistry_bundle_dir, target_dir)
    log.info("Linking {t} to {s}".format(t=target_dir, s=current_link))
    cwd = os.getcwd()
    os.chdir(res_bundle_dir)
    os.symlink(os.path.basename(target_dir), os.path.basename(current_link))
    os.chdir(cwd)
    git_dir = os.path.join(target_dir, ".git")
    if os.path.exists(git_dir):
        shutil.rmtree(git_dir)
    local("tar czvf {t} -C {d} .".format(t=tarball, d=target_dir))
    log.info("Chemistry bundle is {t}".format(t=tarball))


def _copy_bundle_from_template(template_dir, bundle_version_dir):
    if os.path.exists(bundle_version_dir):
        log.warn("Bundle {v} already exists.".format(v=bundle_version_dir))
    else:
        shutil.copytree(template_dir, bundle_version_dir)


def _to_build_name(bundle_id, version):
    name = "{n}-{v}".format(n=bundle_id, v=version)
    return name


def _create_gzip(bundle_path):
    base_name = os.path.basename(bundle_path)
    gzip_name = base_name + ".tar.gz"
    with lcd(os.path.dirname(bundle_path)):
        local("tar vcfz {g} {b}".format(g=gzip_name, b=base_name))

    return os.path.join(os.path.dirname(bundle_path), gzip_name)


def _publish_to(bundle_tgz, publish_dir):
    """Copy zipped bundle to a external directory"""
    if publish_dir is not None:
        name = os.path.basename(bundle_tgz)
        output_gz = os.path.join(publish_dir, name)
        if os.path.exists(output_gz):
            warnings.warn("Bundle version {n} has already been published. Skipping publishing.".format(n=name))
        else:
            shutil.copy(bundle_tgz, output_gz)
            log.info("Published {x} to {o}".format(x=name, o=output_gz))


def _get_smrtflow_version(root_dir):
    p = os.path.join(root_dir, 'smrt-common-models/target/scala-2.11/resource_managed/main/pacbio-manifest.json')
    return load_pacbio_versions(p)


def _get_git_sha(repo_path):
    cwd = os.getcwd()
    try:
        os.chdir(repo_path)
        return subprocess.check_output(["git","describe","--always"]).strip()
    except subprocess.CalledProcessError as e:
        log.error(e)
        return "unknown"
    finally:
        os.chdir(cwd)


def _get_smrtlink_ui_version(root_dir):
    ui_manifest_path = os.path.join(root_dir, 'dist/pacbio-manifest.json')
    pbversions = load_pacbio_versions(ui_manifest_path)
    sha = _get_git_sha(root_dir)
    for v in pbversions:
        if v.idx == "smrtlink_ui":
            v.version += "." + sha
    return pbversions


def _get_chemistry_bundle_version(chem_bundle_dir):
    if chem_bundle_dir is None:
        log.warn("No chemistry bundle available")
        return []
    version = _get_git_sha(chem_bundle_dir)
    return [PacBioVersion("chemistry-data-bundle", "chemistry-data-bundle",
                          version, "Chemistry resources bundle")]


def _chmod(file_name):
    os.chmod(file_name, 0o777)


def _chmod_on_files(bundle_bin_dir):
    for fname in os.listdir(bundle_bin_dir):
        _chmod(os.path.join(bundle_bin_dir, fname))


def _update_tomcat_users_xml(bundle_dir, tomcat_output_dir):
    src = os.path.join(bundle_dir, 'templates', PbConstants.TCAT_USERS)
    dest = os.path.join(tomcat_output_dir, 'conf', PbConstants.TCAT_USERS)
    shutil.copy(src, dest)
    log.info("Copied tomcat-users.xml from {s} to {d}".format(s=src, d=dest))


def __copy_pipeline_templates(resolved_pipeline_templates_dir, services_root_dir):
    """
    Copy all resolved pipeline templates into smrt-server-link within smrtflow.

    Note, this will overwrite all existing files
    """

    def to_scala_path(x):
        return os.path.join(services_root_dir, x)

    if resolved_pipeline_templates_dir is not None:
        target_json_dir = to_scala_path(
            "smrt-server-link/src/main/resources/resolved-pipeline-templates")
        if os.path.exists(target_json_dir):
            log.warn("removing old resolved pipeline templates dir {d}".format(
                d=target_json_dir))
            shutil.rmtree(target_json_dir)
        os.mkdir(target_json_dir)
        log.info("copying resolved pipeline templates in {d}...".format(
            d=resolved_pipeline_templates_dir))
        nfiles = 0
        for json_file in os.listdir(resolved_pipeline_templates_dir):
            src_file = os.path.join(resolved_pipeline_templates_dir, json_file)
            if json_file.endswith(".json"):
                nfiles += 1
                target_file = os.path.join(target_json_dir, json_file)
                if os.path.exists(target_file):
                    os.remove(target_file)
                log.debug("  cp {s} {t}".format(s=src_file, t=target_file))
                shutil.copyfile(src_file, target_file)
        if nfiles == 0:
            # the services require at least one. MK this should fixed.
            emsg = "No resolved pipeline templates found in {d}. SMRT Link " \
                   "services requires at least one pipeline template".format(d=resolved_pipeline_templates_dir)
            raise ValueError(emsg)


def _build_wso2_api_manager(wso2_api_manager_zip, output_bundle_root_dir):
    """
    Extract and add custom

    1. extract
    2. remove sample applications
    3. Override custom files

    Note: the bundle output dir will have the templates and other resources
    """
    t0 = time.time()
    log.info("Unzipping and building WS02 bundle from '{}'".format(wso2_api_manager_zip))
    with ZipFile(wso2_api_manager_zip) as z:
        z.extractall(output_bundle_root_dir)

    name, ext = os.path.splitext(os.path.basename(wso2_api_manager_zip))

    wso2_output_dir = os.path.join(output_bundle_root_dir, name)

    def to_wp(r):
        # Output path relative to wso2
        return os.path.join(wso2_output_dir, r)

    def to_bp(r):
        # output path relative to templates-wso2 dir in the bundle output dir
        return os.path.join(output_bundle_root_dir, 'templates', 'templates-wso2', r)

    def copy_template_to_wso2(template_name, output_rel_to_wso2):
        src = to_bp(template_name)
        dest = to_wp(output_rel_to_wso2)
        log.debug("Copying {} to {}".format(src, dest))
        shutil.copy(src, dest)

    # Delete all sample apps
    samples_dir = to_wp("samples")
    if os.path.exists(samples_dir):
        shutil.rmtree(samples_dir)

    # Copy Custom Override files into wso2
    copy_template_to_wso2("logging-bridge.properties", 'repository/conf/etc/logging-bridge.properties')
    copy_template_to_wso2('api-manager.xml', 'repository/conf/api-manager.xml')
    copy_template_to_wso2('axis2.xml', 'repository/conf/axis2/axis2.xml')

    # enable CORS for OAuth2 endpoints
    copy_template_to_wso2('_RevokeAPI_.xml', 'repository/deployment/server/synapse-configs/default/api/_RevokeAPI_.xml')
    copy_template_to_wso2('_TokenAPI_.xml', 'repository/deployment/server/synapse-configs/default/api/_TokenAPI_.xml')

    log.info("Completed building WSO2 manager in {:.2f} sec".format(time.time() - t0))


def _build_smrtlink_services(services_root_dir, output_bundle_dir,
                             resolved_pipeline_templates_dir=None,
                             ivy_cache=None,
                             analysis_server="smrt-server-link"):
    """
    Builds all of the Scala Services

    :param services_root_dir: smrtflow root directory to the scala services
    code
    :param output_bundle_dir: Path to output bundle directory
    :param resolved_pipeline_templates_dir: Path to directory containing
    resolved pipeline template JSON files
    :param ivy_cache: Custom ivy-cache location
    :param analysis_server: sbt smrt-server subproject name

    """
    t0 = time.time()

    # Build Scala Services
    def to_scala_path(x):
        return os.path.join(services_root_dir, x)

    if resolved_pipeline_templates_dir is not None:
        __copy_pipeline_templates(resolved_pipeline_templates_dir, services_root_dir)
    else:
        log.warn("No Resolved Pipeline Templates dir provided")

    sbt_cmd = _to_sbt_cmd(ivy_cache)

    # this only really works for smrt-server-link. Remove
    cmds = [analysis_server + "/pack"]

    with lcd(services_root_dir):
        for cmd in cmds:
            local("{s} {c}".format(s=sbt_cmd, c=cmd))

    # Copy built SL Analysis tools into bundle tools dir
    output_tools_root = os.path.join(output_bundle_dir, 'tools')
    tools_root = to_scala_path(analysis_server + "/target/pack")
    log.info("Copying {i} to {o}".format(i=tools_root, o=output_tools_root))
    copy_tree(tools_root, output_tools_root)

    log.debug("Completed building {s} in SL Services in {t:.2f} sec.".format(s=analysis_server, t=time.time() - t0))


@task
def build_smrtlink_services_ui(version,
                               smrtlink_ui_dir,
                               smrtflow_root_dir,
                               resolved_pipeline_templates_dir,
                               publish_to=None,
                               ivy_cache=None,
                               wso2_api_manager_zip="wso2am-2.0.0.zip",
                               tomcat_tgz="apache-tomcat-8.0.26.tar.gz",
                               chemistry_bundle_dir=None,
                               doc_dir=None
                               ):
    """
    Build the SMRT Link UI and copy it into the Tomcat. The bundles will be
    written to ./built-bundles/

    :param version: Semantic Version string of the bundle

    :param smrtlink_ui_dir: SMRT Link UI root directory

    Example: /Users/mkocher/workspaces/mk_mb_pbbundler/ui/main/apps/smrt-link

    :param smrtflow_root_dir: Root Directory to the smrtflow repo (e.g.,

    Example: /Users/mkocher/workspaces/mk_mb_pbbundler/smrtflow)

    :param resolved_pipeline_templates_dir: Directory containing resolved
    pipeline template JSON files

    :param publish_to: Copy the bundle.tgz to output directory. Ignore if
    not given

    :param ivy_cache: Path to the ivy cache for sbt

    :param analysis_server: smrtflow sbt subproject id (e.g., smrt-server-link)

    :param wso2_api_manager_zip: Path to the zip of the WSO2 API Manager (
    v2.0.0)

    :param chemistry_bundle_dir: path to chemistry resources repo
    :type chemistry_bundle_dir: str | None

    :param doc_dir: Directory of docs that will be copied into tomcat (if doc_dir not None)
    :type doc_dir: str | None

    Example of running from the commandline

    $> fab build_smrtlink_services_ui:"0.2.2-1234",
    "/Users/mkocher/workspaces/mk_mb_pbbundler/ui/main/apps/smrtlink",
    "/Users/mkocher/workspaces/mk_mb_pbbundler/smrtflow",
    "/Users/mkocher/workspaces/mk_mb_pbbundler/resolved-pipeline-templates",
    ivy_cache="~/.ivy-cache-custom",
    analysis_server="smrt-server-link",
    wso2_api_manager_zip=/path/to/ws02am-2.0.0.zip
    tomcat_tgz=/path/to/apache-tomcat-8.0.26.tar.gz


    Add publish_to="/mnt/secondary/Share/smrtserver-bundles-nightly"

    To copy the tar.gz bundle to the outputdir.
    """
    bamboo_build_number = os.environ.get("bamboo_buildNumber", "")
    log.info("Starting SL Bundler. Building components: SL Analysis Server, Tomcat and wso2 AM manager")
    log.info("bamboo build number {}".format(bamboo_build_number))

    def to_p(x):
        return os.path.abspath(os.path.expanduser(x))

    wso2_api_manager_zip = to_p(wso2_api_manager_zip)
    smrtlink_ui_dir = to_p(smrtlink_ui_dir)
    resolved_pipeline_templates_dir = to_p(resolved_pipeline_templates_dir)
    wso2_api_manager_zip = to_p(wso2_api_manager_zip)
    tomcat_tgz = to_p(tomcat_tgz)
    doc_dir = to_p(doc_dir) if doc_dir is not None else doc_dir

    # Validation to fail early
    _raise_if_not_exists(smrtlink_ui_dir, "SMRTLink UI not found.")
    _raise_if_not_exists(smrtflow_root_dir, "smrtflow services not found.")
    _raise_if_not_exists(resolved_pipeline_templates_dir, "pbsmrtpipe Resources not found.")
    _raise_if_not_exists(wso2_api_manager_zip, "Unable to find wso2 API Manager zip '{}'".format(wso2_api_manager_zip))
    _raise_if_not_exists(tomcat_tgz, "Unable to find tomcat from '{}'".format(tomcat_tgz))

    _raise_non_none_if_not_exists(doc_dir, "Unable to find Document Directory {}".format(doc_dir))
    _raise_non_none_if_not_exists(publish_to, "Publish directory not Found. {}".format(publish_to))

    started_at = time.time()
    name = _to_build_name(Constants.SLS_UI, version)
    output_bundle_dir = os.path.join(_ROOT_BUILT_BUNDLES, name)
    log.info("Creating bundle {n} -> {d} ".format(n=name, d=output_bundle_dir))

    _d = dict(n=name, d=output_bundle_dir, s=smrtflow_root_dir, u=smrtlink_ui_dir)

    _copy_bundle_from_template(Constants.SLS_UI, output_bundle_dir)
    if chemistry_bundle_dir is not None:
        _copy_chemistry_bundle(chemistry_bundle_dir, output_bundle_dir)
    log.info("Copying {f} to bundle {o}".format(f=_SL_SYSTEM_AVSC, o=output_bundle_dir))
    shutil.copy(_SL_SYSTEM_AVSC, output_bundle_dir)

    # Change Permissions. Not sure if this is really even necessary.
    dirs = ("bin", "resources")
    for px in dirs:
        for root, dirs, files in os.walk(os.path.join(output_bundle_dir, px)):
            for file_name in files:
                f = os.path.join(output_bundle_dir, root, file_name)
                _chmod(f)

    build_log = os.path.join(output_bundle_dir, PbConstants.BUILD_LOG)
    setup_log(log, level=logging.DEBUG, file_name=build_log)
    log.info("Bundle {n} to {d} with ui:{u} services:{s}".format(**_d))

    _build_wso2_api_manager(wso2_api_manager_zip, output_bundle_dir)

    t0 = time.time()
    log.info("Building SMRT Link UI from {}".format(smrtlink_ui_dir))
    local("npm --version")
    local("node --version")
    with lcd(smrtlink_ui_dir):
        local("npm run build -- --production")
    log.info("Completed building UI in {:.2f} sec".format(time.time() - t0))

    # Tomcat
    tomcat_output_dir = _copy_and_extract_tomcat(tomcat_tgz, output_bundle_dir)
    _archive_tomcat_webapp_root(tomcat_output_dir)
    _update_tomcat_users_xml(output_bundle_dir, tomcat_output_dir)

    # Copy new-SL-UI to Tomcat
    # root_app_dir = to_path("curbranch/apps/smrt-link/dist")
    root_app_dir = os.path.join(smrtlink_ui_dir, "dist")
    root_html_dir = os.path.join(tomcat_output_dir, "webapps", "ROOT", _STATIC_FILE_DIR)
    if os.path.exists(root_html_dir):
        shutil.rmtree(root_html_dir)
    shutil.copytree(root_app_dir, root_html_dir)

    # Copy Docs in Tomcat
    if doc_dir is None:
        log.warn("No docs directory provided. Skipping copying docs")
    else:
        doc_app_dir = os.path.join(root_html_dir, "docs")
        if os.path.exists(doc_app_dir):
            shutil.rmtree(doc_app_dir)
        log.info("Copying docs into {}".format(doc_app_dir))
        shutil.copytree(doc_dir, doc_app_dir)

    # Build Scala SMRT Link Analysis Services
    _build_smrtlink_services(smrtflow_root_dir, output_bundle_dir,
                             resolved_pipeline_templates_dir=resolved_pipeline_templates_dir,
                             ivy_cache=ivy_cache,
                             analysis_server="smrt-server-link")
    # build simulator tools
    _build_smrtlink_services(smrtflow_root_dir, output_bundle_dir,
                             analysis_server="smrt-server-sim")
    # Write the PacBio Version file
    smrtflow_versions = {i for i in _get_smrtflow_version(smrtflow_root_dir)}
    log.info("Versions: smrtflow -> {}".format(smrtflow_versions))
    smrtlink_ui_versions = {i for i in _get_smrtlink_ui_version(smrtlink_ui_dir)}
    log.info("Versions: smrtlink-ui -> {}".format(smrtlink_ui_versions))
    resource_versions = {i for i in _get_chemistry_bundle_version(chemistry_bundle_dir)}

    versions = list(smrtflow_versions | smrtlink_ui_versions | resource_versions)

    pacbio_version_file = os.path.join(output_bundle_dir, PbConstants.MANIFEST_FILE)
    write_pacbio_versions(versions, pacbio_version_file)
    log.info("Wrote smrtflow versions {v} to {o}".format(v=versions, o=pacbio_version_file))

    gzip_path = _create_gzip(output_bundle_dir)
    _publish_to(gzip_path, publish_to)
    run_time = time.time() - started_at
    log.info("Completed build bundle {b} in {s:.2f} sec to {d}".format(b=name, d=output_bundle_dir, s=run_time))
    return 0
