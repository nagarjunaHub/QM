package com.eb.lib.aosp

import jenkins.plugins.git.GitSCMSource
import jenkins.plugins.git.traits.BranchDiscoveryTrait
import org.jenkinsci.plugins.workflow.libs.SCMSourceRetriever


def prepare_pipeline() {
    if ("${PROJECT_CONFIG}" != "") {
        echo("Using PROJECT_CONFIG from the Jenkins multi-line text field.")
        project_config = "${PROJECT_CONFIG}".toString()
        project_config_json = readJSON text: project_config
    } else {
        echo("Using PROJECT_CONFIG from the Jenkins PROJECT_CONFIG_FILE.")
        project_config_json = readJSON file: ".launchers/pipeline/project_config/${PROJECT_CONFIG_FILE}"
    }
    // defaults
    project_config_json.TESTING_DISABLE = "false"
    // defaults

    project_config_json.pipeline_node = "Linux_BuildBot"


    project_config_json.get_flash_image_msg = "__get_flash__"
    project_config_json.get_flash_image_msg_clean = "__get_flash_clean__"
    project_config_json.get_flash_image = false
    project_config_json.verify_message = "Automated commit verification review:"
    project_config_json.verify_score = 1

    pipelineType = PIPELINE_TYPE.toLowerCase().trim() //verify, devel, snapshot

    project_config_json.buildtools_url = buildtools_url
    project_config_json.buildtools_branch = buildtools_branch

    project_config_json.pipelineType = pipelineType

    project_config_json.build_vts = project_config_json[pipelineType]["build_vts"]
    project_config_json.vts_make_target = project_config_json["vts"]["vts_make_target"]
    project_config_json.vts_repositories = project_config_json["vts"]["vts_repositories"]

    project_config_json.test_job = project_config_json[pipelineType]["test_job"]

    project_config_json.testing_stage = project_config_json[pipelineType]["testing_stage"]

    project_config_json.pipeline_make_targets = project_config_json[pipelineType]["pipeline_make_targets"]
    project_config_json.files_to_publish = project_config_json[pipelineType]["files_to_publish"]
    project_config_json.app_files_to_publish = project_config_json[pipelineType]["app_files_to_publish"]

    project_line = project_config_json.project_line
    sub_project = project_config_json.sub_project
    project_type = project_config_json.project_type
    project_prefix = (sub_project == "") ? "" : sub_project+"_"
    android_version = project_config_json.android_version
    branch_identifier = project_config_json.branch_identifier

    project_config_json.project_branch = project_line+"_"+android_version+"_"+branch_identifier
    Date date = new Date()
    project_config_json.project_release_version = [project_line,project_type,android_version,branch_identifier,date.format("yyyy-MM-dd_HH-mm")].join("_").trim().toUpperCase()
    project_config_json.prebuilt_release_name = [project_line,project_type,android_version,branch_identifier,"prebuilt",pipelineType].join("_").trim().toUpperCase()

    /* Rules to define target Id (aka Build Variant) is: manifest's name for that variant, should start with target ID.
     * We will check commit if found in manifest then will build, else skip build for that variant.
    */
    project_config_json.supported_targets = project_config_json["supported_target_ids"].keySet().collect().join(" ") // This default list will be filter out base on gerrit event

    // Network Release sharedrive
    net_sharedrive = project_config_json.net_sharedrive
    project_config_json.NET_SHAREDRIVE_TABLE = [
        "qnx": net_sharedrive + "/qnx_release/",
        "snapshot": net_sharedrive + "/" + project_prefix.toLowerCase() + "snapshots/",
        "apps": net_sharedrive + "/app_releases/",
        "devel": net_sharedrive + "/" + project_prefix.toLowerCase() + "devel/",
        "verify": net_sharedrive + "/" + project_prefix.toLowerCase() + "devel/",
        "get_flash": net_sharedrive + "/get_flash/"
    ]

    project_config_json.build_type_list = project_config_json[pipelineType]["build_type_list"].tokenize(" ")

    project_config_json.repo_dev_manifest_revision = project_config_json.project_branch
    project_config_json.repo_rel_manifest_revision = project_config_json.project_branch + "_master"

    if (project_config_json[pipelineType].containsKey("sonar_analysis")) {
      if (DISABLE_SONARQUBE_ANALYSIS == "false") {
        print("Sonarqube analysis enabled")
        project_config_json.run_sonar_analysis = project_config_json[pipelineType]["sonar_analysis"]["run_sonar_analysis"]
        project_config_json.sonar_server = project_config_json[pipelineType]["sonar_analysis"]["sonar_server"]
        project_config_json.sonar_scanner = project_config_json[pipelineType]["sonar_analysis"]["sonar_scanner"]
        project_config_json.sonar_build_wrapper = project_config_json[pipelineType]["sonar_analysis"]["sonar_build_wrapper"]
        project_config_json.sonar_projectkey_prefix = project_config_json[pipelineType]["sonar_analysis"]["sonar_projectkey_prefix"]
        project_config_json.sonar_freestyle_job = project_config_json[pipelineType]["sonar_analysis"]["sonar_freestyle_job"]
        project_config_json.sonar_freestyle_job = "T2K-CI/aosp/playground/Sonarqube_analysis" // Mock !!! Remove later !!!
      } else {
        print("Sonarqube analysis disabled.")
      }
    }

    project_config_json.leastloadednodeMap = [:]
    project_config_json.exit_statuses = [:]

    project_config_json.BUILD_STATUS = "SUCCESS"
    project_config_json.TEST_STATUS = "SUCCESS"
    project_config_json.RELEASE_STATUS = "SUCCESS"

    project_config_json.failure_mail_body = """JOB URL: ${BUILD_URL}
    ERROR: %s
    INFO: %s
    """

    project_config_json.email_build_info = ""

    return project_config_json
}
