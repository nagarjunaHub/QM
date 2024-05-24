import hudson.model.*
import groovy.json.JsonSlurperClassic
import org.jvnet.jenkins.plugins.nodelabelparameter.NodeParameterValue
// Regex to have dynamic trigger: Project: .*T2K\/(module|libs)\/elektrobit\/?.*  branch: t2k_q_0

// From https://t2k-gerrit.elektrobit.com/plugins/gitiles/infrastructure/pipeline-global-library/+log/refs/heads/aosp
@Library(['pipeline-global-library@app']) _
import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import com.eb.lib.aosp.PipelineEnvironment

// For pipeline common config
def commonlib = new aospUtils()
def common_env = new CommonEnvironment()

// Restrict to using BUILD_ON_NODE if supplied.
def pipeline_node = "Apps_BuildBot"

boolean NOTIFICATION_FAILURE = true

node(pipeline_node) {
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

    configRuntime.pipeline_node = pipeline_node
    print(configRuntime)
    if (params.DEVENV2_TESTING != null) {
        print("using custom dev_env: ${devenv2_path}")
        config.dev_env = devenv2_path
    }
}


stage("PreparePipeline") {
    timestamps {
        try {
            print("dev_env: ${config.dev_env}")
            appPreparePipeline(script: this)
        } catch(err) {
            commonlib.__INFO(err.toString())
            currentBuild.result = "FAILURE"
            configRuntime.BUILD_STATUS = "FAILURE"
            configRuntime.build_addinfo = "(Pipeline preparation failed)!!!"
            configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
        }
    }
}

stage("PrepareWS") {
    timestamps {
        if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
            appPrepareWS(script: this)
        }
    }  /* timestamp */
}

if (currentBuild.result && currentBuild.result.equalsIgnoreCase('ABORTED') && !configRuntime.pipelineType.contains("verify") ) {
    appCleanUpWS(script: this)
    error("ABORTING early.")
}

//if (configRuntime.pipelineType.contains("master")) {
//    stage("get version") {
//        timestamps {
//            if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
//                appVersion(script: this)
//            }
//        } /* timestamp */
//    }
//}


stage("Build") {
    timestamps {
        if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
            appBuild(script: this)
        }
    }
}

stage("Testing"){
    timestamps {
        if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
            appTest(script: this)
        }
    }
}

stage("SonarqubeAnalysis") {
    if (configRuntime.sonarqube_enable == true) {
        timestamps {
            if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
                appSonarqube(script: this)
            }
        } /* timestamp */
    }
}

if (configRuntime.pipelineType.contains("verify")) {
    stage('QualityGates'){
        timestamps {
            if ((currentBuild.result.equalsIgnoreCase('SUCCESS')) && configRuntime.qualitygate_enable == true) {
                try {
                    timeout(time: 1, unit: 'HOURS') {
                        waitForQualityGate abortPipeline: true
                    }
                } catch(err) {
                    currentBuild.result = 'FAILURE'
                    configRuntime.BUILD_STATUS = "FAILURE"
                    configRuntime.build_addinfo = "(Sonar Quality Gate failed)!!!"
                    configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
                }
            } else {
                commonlib.__INFO("Quality Gate status check is disabled for ${GERRIT_PROJECT}")
            }
        }
    }
    // Review Feedback stage (will run even if Quality Gates fail)
    stage("ReviewAndFeedback") {
        appReviewAndFeedback(script:this)
    }
} else {
    if (params.DEVENV2_TESTING == null) {
        stage("PublishWS") {
            timestamps {
                if (currentBuild.result.equalsIgnoreCase('SUCCESS')) {
                    appPublish(script: this)
                }
            } /* timestamp */
        }
    }
}


// CleanUp WS stage (always runs)
stage("CleanUpWS") {
    timestamps {
        appCleanUpWS(script: this)
    }
}

if(configRuntime.leastloadednode) {
    node(configRuntime.leastloadednode) {

        if (configRuntime.BUILD_STATUS == 'FAILURE') {
            currentBuild.description = configRuntime.build_description + "B:-1" + configRuntime.build_addinfo
        } else {
            if (configRuntime.TEST_STATUS == "FAILURE") {
                currentBuild.description = configRuntime.build_description + "B:+1 T:-1" + configRuntime.test_addinfo
            } else {
                if (config.PUBLISH_STATUS == "FAILURE") {
                    currentBuild.description = configRuntime.build_description + "B:+1 T:+1 P:-1"
                } else {
                    if (currentBuild.result == "ABORTED") {
                        currentBuild.description = configRuntime.build_description + " (ABORTED/SKIPPED)"
                        if (configRuntime.verify_score == 0) {
                            NOTIFICATION_FAILURE = false
                        }
                    } else {
                        NOTIFICATION_FAILURE = false
                        if (configRuntime.pipelineType.contains("verify")) {
                            currentBuild.description = configRuntime.build_description + "B:+1 T:+1"
                        } else if (configRuntime.pipelineType.contains("master")) {
                            currentBuild.description = configRuntime.build_description + "B:+1 T:+1 P:+1"
                        }
                    }
                }
            }
        }

        if (NOTIFICATION_FAILURE) {
            dir(configRuntime.build_workspace) {
                sh """#!/bin/bash -x
                rm -rf *_${BUILD_NUMBER}_buildlog.txt ${BUILD_NUMBER}_testlog.txt
            """

                def email_recipients = configRuntime.email_recipients.replaceAll(",null", "").trim()

                def build_log = "${configRuntime.project}_${BUILD_NUMBER}_buildlog.txt"
                commonlib.fetchJenkinsLog(BUILD_URL, build_log)
                if (configRuntime.BUILD_STATUS == 'FAILURE') {
                    attachmentlist = "**/*_${BUILD_NUMBER}/*_${BUILD_NUMBER}.output"
                } else {
                    if (configRuntime.TEST_STATUS == "FAILURE") {
                        def test_log = "${BUILD_NUMBER}_testlog.txt"
                        commonlib.fetchJenkinsLog(configRuntime.TEST_URL, test_log)
                        attachmentlist = "${test_log}"
                    }
                }
                def subject = currentBuild.result + ": ${JOB_NAME}#${BUILD_NUMBER} ${configRuntime.project}"
                def check_abort_msg = sh(returnStdout: true, script: """#!/bin/bash -x
                grep -Eh \"Aborted\\s+by\" ${build_log} || true""").trim()
                configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(), " : " + check_abort_msg, "").trim()

                emailext(attachmentsPattern: attachmentlist,
                        body: configRuntime.failure_mail_body,
                        to: email_recipients,
                        from: config.default_email_sender,
                        subject: subject)
            }
        }
    }
}
