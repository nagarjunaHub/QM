import hudson.model.*
import groovy.json.JsonSlurperClassic
import org.jvnet.jenkins.plugins.nodelabelparameter.NodeParameterValue
// Regex to have dynamic trigger: Project: (?i)^(?!.*\/(.*manifest-release|docker\/|integration\/|etools\/)).*$  branch: (?i)^(t2k_sc|.*showcar)_.*

// From https://t2k-gerrit.elektrobit.com/plugins/gitiles/infrastructure/pipeline-global-library/+log/refs/heads/aosp
@Library(['pipeline-global-library@aosp']) _
import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import com.eb.lib.aosp.PipelineEnvironment

// For pipeline common config
def commonlib = new aospUtils()
def common_env = new CommonEnvironment()

def pipeline_node = "Linux_BuildBot"

boolean NOTIFICATION_FAILURE = true

node("Linux_BuildBot") {
    // Checkout build tools into workspsace
    timestamps {
      echo "Checking out ${buildtools_url}.."
      checkout([$class: 'GitSCM', branches: [[name: "${buildtools_branch}"]], \
        userRemoteConfigs: [[url: "${buildtools_url}"]], \
        extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${WORKSPACE}/.launchers"]]])
    }

    def pipeline_env = new PipelineEnvironment(this)
    pipeline_env.setupGlobalEnvironment()
    pipeline_env.printConfiguration()

    config = pipeline_env.configuration
    configRuntime = pipeline_env.configProperties

    configRuntime.pipelineType = PIPELINE_TYPE.toLowerCase().trim() //verify, devel, snapshot

    configRuntime.pipeline_node = "Linux_BuildBot"
    configRuntime.build_on_node = (BUILD_ON_NODE != "") ? BUILD_ON_NODE : ""

    configRuntime.get_flash_image = false
    configRuntime.supported_targets = config.supported_target_ids.keySet().collect().join(" ") // This default list will be filter out base on gerrit event
    //build_type_list is depend on target_id. so, moved to all *.groovy file under target_id_list.each loop
    //configRuntime.build_type_list = config[configRuntime.pipelineType]["build_type_list"].tokenize(" ")


    if (config.project_branch == null) {
        configRuntime.project_branch = config.project_line+"_"+config.android_version+"_"+config.branch_identifier
    } else {
       configRuntime.project_branch = config.project_branch
    }

    Date date = new Date()
    configRuntime.project_release_version = [config.project_line,config.project_type,config.android_version,config.branch_identifier,date.format("yyyy-MM-dd_HH-mm")].join("_").trim().toUpperCase()

    if (config.prebuilt_release_required == null) {
      configRuntime.prebuilt_release_name = "n/a"
    } else {
      configRuntime.prebuilt_release_name = [config.project_line,config.project_type,config.android_version,config.branch_identifier,"prebuilt",configRuntime.pipelineType].join("_").trim().toUpperCase()
    }

    configRuntime.repo_dev_manifest_revision = configRuntime.project_branch
    configRuntime.repo_rel_manifest_revision = configRuntime.project_branch + "_master"

    configRuntime.build_variant = [:]

    configRuntime.verify_message = ""
    configRuntime.verify_score = 1
    configRuntime.dependencies = ""

    configRuntime.BUILD_STATUS = "SUCCESS"
    configRuntime.TEST_STATUS = "SUCCESS"

    configRuntime.NET_SHAREDRIVE_TABLE = [
        "qnx": config.net_sharedrive + "/qnx_release/",
        "snapshot": config.net_sharedrive + "/snapshots/",
        "apps": config.net_sharedrive + "/app_releases/",
        "devel": config.net_sharedrive + "/devel/",
        "verify": config.net_sharedrive  + "/devel/",
        "get_flash": config.net_sharedrive + "/get_flash/"
    ]

    if (TESTING_DISABLE == "true") {
        configRuntime.disable_testing = true
    } else {
        configRuntime.disable_testing = false
    }

    configRuntime.test_addinfo = ""
    configRuntime.build_description = ""
    configRuntime.failure_mail_body = """JOB URL: ${BUILD_URL}
    ERROR: %s
    INFO: %s
    """

    configRuntime.email_build_info = ""
    configRuntime.email_recipients = config.email_recipients

    print(configRuntime)
}


stage("Prepare Pipeline") {
  timestamps {
    try {
      aospPreparePipeline(script: this)
    } catch(err) {
      commonlib.__INFO(err.toString())
      currentBuild.result = "FAILURE"
      configRuntime.BUILD_STATUS = "FAILURE"
      configRuntime.build_addinfo = "(Pipeline preparation failed)!!!"
      configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
    }
  }
}

if (currentBuild.result && currentBuild.result.equalsIgnoreCase('ABORTED') && !configRuntime.pipelineType.contains("verify") ) {
    error("ABORTING early.")
}

stage("Prepare WS") {
  timestamps {
    if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
        aospPrepareWS(script: this)
    }
  }  /* timestamp */
}


stage("Sync WS") {
    timestamps {
        if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
            aospSyncWS(script: this)
        }
    } /* timestamp */
}

stage("Change Log") {
    timestamps {
        if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
            aospChangeLog(script: this)
        }
    } /* timestamp */
}

stage("Build") {
    timestamps {
      if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
        aospBuild(script: this)
      }
    }
}

// Release the node after build. AOSP Build is the most resource intensive part.
if (configRuntime.build_on_node != "") {
  commonlib.removeNodeLabel(configRuntime.build_on_node, "RESERVED")
}
stage("Testing"){
    if (configRuntime.get_flash_image == true){
        if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
            aospGetFlashParallel(script:this)
        }
    } else {
        if (configRuntime.disable_testing == false) {
            timestamps {
                if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
                  aospTest(script: this)
                }
            }
        }
    }
}


stage("Publish WS") {
    timestamps {
      if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
        aospPublishWS(script: this)
      }
    }
}


stage("Sonarqube analysis") {
  timestamps{
    if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
      aospSonarqube(script:this)
    }
  }
}


if (!configRuntime.pipelineType.contains("verify")) {
  stage("Notify Integration Completion") {
      timestamps {
        if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
          aospNotifyIntegrationCompletion(script:this)
        }
      }
  }
}


stage("CleanUp WS") {
    timestamps {
        aospCleanUpWS(script: this)
    }
}


if (configRuntime.pipelineType.contains("verify")) {
    stage("Review Feedback") {
        aospReviewAndFeedback(script:this)
    }
} else {
    stage("Release") {
      timestamps {
          if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
            aospRelease(script: this)
          }
      } /* timestamp */
    }
}


if (!configRuntime.pipelineType.contains("verify")) {
    stage("Release Notification") {
      timestamps {
          if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
            aospReleaseNotify(script: this)
          }
      } /* timestamp */
    }
}

node(pipeline_node){
    // Make sure to remove node label "RESERVED" if set.
    if (configRuntime.build_on_node != "") {
      commonlib.removeNodeLabel(configRuntime.build_on_node, "RESERVED")
    }

    if (configRuntime.BUILD_STATUS == 'FAILURE'){
        currentBuild.description = configRuntime.build_description + "B:-1" + configRuntime.build_addinfo + " T:0"
    } else {
        if (configRuntime.TEST_STATUS == "FAILURE"){
            currentBuild.description = configRuntime.build_description + "B:+1 T:-1" + configRuntime.test_addinfo
        } else {
            if (config.RELEASE_STATUS == "FAILURE"){
                currentBuild.description = configRuntime.build_description + "B:+1 T:+1 R:-1"
            } else {
                if (currentBuild.result == "ABORTED") {
                    currentBuild.description = configRuntime.build_description + " (ABORTED/SKIPPED)"
                } else {
                    NOTIFICATION_FAILURE = false
                    currentBuild.description = configRuntime.build_description + "B:+1 T:+1 R:+1"
                }
            }
        }
    }

    if (NOTIFICATION_FAILURE) {
        sh """#!/bin/bash -x
            rm -rf *_buildlog.txt *_testlog.txt
        """

        def email_recipients = configRuntime.email_recipients.replaceAll(",null","").trim()

        def build_log = "${configRuntime.project_release_version}_buildlog.txt"
        commonlib.fetchJenkinsLog(BUILD_URL, build_log)

        def subject = currentBuild.result+": ${JOB_NAME}#${BUILD_NUMBER}"
        def check_abort_msg = sh(returnStdout:true, script:"""#!/bin/bash -x
            grep -Eh \"Aborted\\s+by\" ${build_log} || true""").trim()
        configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString()," : "+check_abort_msg).trim()

        //commonlib.ebEmail(subject, config.default_email_sender, email_recipients, "${configRuntime.failure_mail_body}", attachmentlist)
        emailext(body: configRuntime.failure_mail_body.toString(),
            to: email_recipients,
            from: config.default_email_sender,
            subject: subject)
    }
}
