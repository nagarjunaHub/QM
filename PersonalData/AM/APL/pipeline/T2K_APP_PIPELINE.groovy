import hudson.model.*
import groovy.json.JsonSlurperClassic
import org.jvnet.jenkins.plugins.nodelabelparameter.NodeParameterValue

// Regex to have dynamic trigger: Project: .*T2K\/(module|libs)\/elektrobit\/?.*  branch: t2k_q_0
// For pipeline common config
def commonlib

// Restrict to using BUILD_ON_NODE if supplied.
def pipeline_node = (BUILD_ON_NODE != "") ? BUILD_ON_NODE : "Apps_BuildBot"

def BUILD_STATUS = "SUCCESS"
def TEST_STATUS = "SUCCESS"
def PUBLISH_STATUS = "SUCCESS"
boolean NOTIFICATION_FAILURE = true

// For common project config
def project_config, build_workspace, app_workspace, prebuilt_app_workspace, project_config_json, ssd_path
def repo_manifest_url, repo_manifest_xml
def pipelineType, branch_identifier
def test_job, test_job_timeout

def dev_env
def app_info, disable_app, version, code_change, publish_app
String PROJECT


// For Release/Feedback info config
def default_email_sender, email_recipients, email_testing_team
def email_build_info = ""
def failure_mail_body = """JOB URL: ${BUILD_URL}
ERROR: %s
INFO: %s
"""

def verify_message = "Automated commit verification review:"
def build_addinfo = ""
def dependencies = ""
def build_description = ""
def attachmentlist = ""
def verify_score = 1


// For stage job config
def build_node_list
def parameter_list = []
def launched_jobs = [:]
def leastloadednode = ""

// Generated bash bash libraries
def app_pipelinelib_bash_functions

node(pipeline_node) {
    /* display name */
    PROJECT = env.GERRIT_PROJECT.substring(env.GERRIT_PROJECT.lastIndexOf('/') + 1)
    currentBuild.displayName = VersionNumber (versionNumberString: "#${BUILD_NUMBER} ${PROJECT}")
    ssd_path = "/ssd/jenkins/${JOB_BASE_NAME}/${PROJECT}"
    build_workspace = "${ssd_path}/${BUILD_NUMBER}"

    pipelineType = PIPELINE_TYPE.toLowerCase().trim() //verify, master
    if(pipelineType.contains("verify")) {
        if (GERRIT_EVENT_TYPE == "wip-state-changed" && GERRIT_CHANGE_WIP_STATE == "true") {
            currentBuild.result = 'ABORTED'
            currentBuild.description = "WIP change. Hence aborting early."
            error("WIP change. Hence aborting early.")
        }
    }
    // Checkout build tools into workspsace
    timestamps {
        echo "Checking out ${buildtools_url}.."
        checkout([$class: 'GitSCM', branches: [[name: "${buildtools_branch}"]], userRemoteConfigs: [[url: "${buildtools_url}"]], extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${WORKSPACE}/.launchers"]]])
        echo "Loading and generating bash libraries.."
        commonlib = load("${WORKSPACE}/.launchers/pipeline/common.groovy")
        app_pipelinelib_bash_functions = commonlib.app_pipelinelib_bash_functions()

        commonlib.generate_app_bash_functions("${env.NODE_NAME}", WORKSPACE, buildtools_branch, buildtools_url, app_pipelinelib_bash_functions)
    }

    if ("${PROJECT_CONFIG}" != "") {
        commonlib.__INFO("Using PROJECT_CONFIG from the Jenkins multi-line text field.")
        project_config = "${PROJECT_CONFIG}".toString()
    } else {
        commonlib.__INFO("Using PROJECT_CONFIG from the Jenkins PROJECT_CONFIG_FILE.")
        project_config = readFile("${WORKSPACE}/.launchers/pipeline/project_config/${PROJECT_CONFIG_FILE}")
    }

    project_config_json = commonlib.parseJsonToMap(project_config)
    //Get latest branch/repo
    repo_manifest_url = project_config_json.repo_manifest_url

    default_email_sender = project_config_json.default_email_sender
    email_recipients = project_config_json.email_recipients
    email_testing_team = project_config_json.email_testing_team

    branch_identifier = project_config_json.branch_identifier
    dev_env = project_config_json.dev_env
    gerrit_reviewer_third_patry_app = project_config_json.gerrit_reviewer_third_patry_app

    test_job = project_config_json[pipelineType]["test_job"]
    test_job_timeout = project_config_json[pipelineType]["timeout"]

    bundle_promotion_job = project_config_json.bundle_promotion_job

    if (test_job == "") {
        TESTING_DISABLE = true
    }

    try{

        app_workspace = "${build_workspace}/app"
        prebuilt_app_workspace = "${build_workspace}/prebuilt_app"
        repo_manifest_xml = "${PROJECT}.xml"

        app_info = project_config_json[GERRIT_PROJECT]
        if (app_info) {
            // Restrict to using BUILD_ON_NODE if supplied.
            if (BUILD_ON_NODE != "") {
                leastloadednode = BUILD_ON_NODE
            } else{
                pipeline_node = project_config_json[GERRIT_PROJECT]["pipeline_node"]
                if ( ! pipeline_node ) {
                    pipeline_node = project_config_json.pipeline_node
                }
                build_node_list = nodesByLabel label: pipeline_node, offline: false
                if ( build_node_list ){
                    leastloadednode = commonlib.getLeastLoadedNode(build_node_list.join(' ').tokenize(' '))
                } else {
                    currentBuild.result = 'ABORTED'
                    commonlib.__INFO("No Build Bot Found For ${GERRIT_PROJECT} ==> SKIP!!!")
                    return
                }
            }
            commonlib.__INFO("build should run on ${pipeline_node}")
            commonlib.__INFO("build should run on ${leastloadednode}")
            buildBot = leastloadednode

            disable_app = project_config_json[GERRIT_PROJECT]["disable_app"]
            if (!disable_app && disable_app != null && disable_app == true) {
                currentBuild.result = 'ABORTED'
                commonlib.__INFO("${GERRIT_PROJECT} is disabled to build ==> SKIP!!!")
                currentBuild.description = "SKIPPED: ${GERRIT_PROJECT}"
                verify_score = -1
                return
            }
        } else {
            commonlib.__INFO("configuration for the ${GERRIT_PROJECT} is not available. Please add configuration into ${PROJECT_CONFIG_FILE} file ==> SKIP!!!")
            currentBuild.description = "SKIPPED: ${GERRIT_PROJECT}"
            verify_score = 0
            return
        }

        if (pipelineType.contains("verify")) {

            try {
                // Not all gerrit events set GERRIT_EVENT_ACCOUNT_EMAIL. That's why, a try catch block. We give precedence to GERRIT_EVENT_ACCOUNT_EMAIL in our email communications.
                email_build_info = "\n\t-Change: ${GERRIT_CHANGE_URL}\n\t-Patchset: ${GERRIT_PATCHSET_NUMBER} (${GERRIT_REFSPEC})\n\t-Project: ${GERRIT_PROJECT}\n\t-Branch: ${GERRIT_BRANCH}\n\t-Triggered By: ${GERRIT_EVENT_ACCOUNT_EMAIL}"
            } catch(err) {
                email_build_info = "\n\t-Change: ${GERRIT_CHANGE_URL}\n\t-Patchset: ${GERRIT_PATCHSET_NUMBER} (${GERRIT_REFSPEC})\n\t-Project: ${GERRIT_PROJECT}\n\t-Branch: ${GERRIT_BRANCH}\n\t-Triggered By: ${GERRIT_PATCHSET_UPLOADER_EMAIL}"
            }

            build_description = "${env.GERRIT_PROJECT}-${env.GERRIT_BRANCH}: "

            email_recipients = email_recipients + commonlib.getReviewersEmailFromChangeNumber(GERRIT_HOST, GERRIT_CHANGE_NUMBER)
            email_recipients = "${email_recipients}${GERRIT_CHANGE_OWNER_EMAIL},${GERRIT_PATCHSET_UPLOADER_EMAIL}".tokenize(',').unique().join(',') + ","

            dependencies = commonlib.gerritGetDependencies(VERBOSE, GERRIT_CHANGE_COMMIT_MESSAGE, GERRIT_HOST)
            // If the change that triggered a commit has dependent changes, collect them in this variable.

            def currentChangeDependencies = commonlib.gerritGetDependentChanges(VERBOSE, GERRIT_CHANGE_NUMBER, GERRIT_HOST)

            if (dependencies != "") {
                dependencies = dependencies + " " + currentChangeDependencies

                commonlib.__INFO("Dependencies: " + dependencies)

                verify_message = verify_message + "\n\n * Info:\n\t Depends on: " + dependencies.replace("true","up-to-date").replace("false","patchset-out-of-date")
            }

            def vmsgtemp = "Automated verification pipeline has started: \n\t\t${env.BUILD_URL}consoleFull"
            commonlib.gerrit_SetReview(GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER, vmsgtemp, 0)

            vmsgtemp = commonlib.isCommitRebasable(GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PROJECT, GERRIT_BRANCH)
            if (vmsgtemp != "") {
                verify_message = verify_message + "\n\n * Warning:\n\t Review commit is not on top of the latest commit of the branch (REBASE)"
            }

            vmsgtemp = commonlib.isJiraTicket(GERRIT_CHANGE_COMMIT_MESSAGE)
            if (vmsgtemp != "") {
                commonlib.__INFO("TRACING ID: " + vmsgtemp)
                verify_message = verify_message + "\n\n * Summary:\n\t [OK] TRACING ID: " + vmsgtemp
            } else {
                commonlib.__INFO("TRACING ID: NOT FOUND!" )
                verify_message = verify_message + "\n\n * Summary:\n\t [  ] TRACING ID: NOT FOUND!"
                //verify_score = -1
            }
        } else if(pipelineType.contains("master")) {
            email_build_info = "\n\t-Project: ${GERRIT_PROJECT}\n\t-Branch: ${GERRIT_BRANCH}"
        }
    } catch(err) {
        commonlib.__INFO(err.toString())
        currentBuild.result = "FAILURE"
        failure_mail_body = String.format(failure_mail_body,err.toString()+"%s",email_build_info)
    }
}

if (leastloadednode) {
    node(leastloadednode) {
        if (pipelineType.contains("master")) {
            //cleaning up the old build workspace
            sh "rm -rf ${ssd_path}/*"
        }
        dir(build_workspace) {
            timestamps {
                checkout([$class: 'GitSCM', branches: [[name: "${buildtools_branch}"]], userRemoteConfigs: [[url: "${buildtools_url}"]], extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${WORKSPACE}/.launchers"]]])
                echo "Loading and generating bash libraries.."
                commonlib = load("${WORKSPACE}/.launchers/pipeline/common.groovy")
                app_pipelinelib_bash_functions = commonlib.app_pipelinelib_bash_functions()

                commonlib.generate_app_bash_functions("${env.NODE_NAME}", WORKSPACE, buildtools_branch, buildtools_url, app_pipelinelib_bash_functions)
            }

            stage("Prepare WS") {
                try {
                    timestamps {
                        script {
                            if (pipelineType.contains("verify")) {
                                sh(returnStdout: true, script: """#!/bin/bash -e
                                    source ${WORKSPACE}/.launchers/libtools/pipeline/app_pipeline.lib && app_ws_worker ${VERBOSE} ${PIPELINE_TYPE} ${dev_env} ${build_workspace} \
                                                                                                          ${repo_manifest_url} ${GERRIT_BRANCH} ${repo_manifest_xml} \
                                                                                                          ${GERRIT_HOST} ${GERRIT_PROJECT} ${GERRIT_CHANGE_NUMBER} \
                                                                                                          ${GERRIT_PATCHSET_NUMBER} \"${dependencies}\"  """)
                            } else if (pipelineType.contains("master")) {
                                sh(returnStdout: true, script: """#!/bin/bash -e
                                    source ${WORKSPACE}/.launchers/libtools/pipeline/app_pipeline.lib && app_ws_worker ${VERBOSE} ${PIPELINE_TYPE} ${dev_env} ${build_workspace} \
                                                                                                         ${repo_manifest_url} ${GERRIT_BRANCH} ${repo_manifest_xml}  """)
                                code_change = sh(returnStdout: true, script: """#!/bin/bash -e
                                    source ${WORKSPACE}/.launchers/libtools/pipeline/app_pipeline.lib && code_change ${VERBOSE} ${build_workspace} ${prebuilt_app_workspace}""")

                                if (code_change.isEmpty() && FORCE_RUN == "false") {
                                    echo "There is no code change in ${GERRIT_PROJECT} => skip the build"
                                    currentBuild.result = 'SUCCESS'
                                    BUILD_STATUS = 'ABORTED'
                                    commonlib.__INFO("No new change in ==> SKIP MASTER BUILD!!!")
                                    currentBuild.description = "SKIPPED: No Change in ${GERRIT_PROJECT}"
                                    return
                                } else {
                                    echo "Eithere there is any change in Code or Build has started with FORCE_RUN flag enabled"
                                }
                            }
                        }
                    }
                } catch (err) {
                    commonlib.__INFO(err.toString())
                    currentBuild.result = 'FAILURE'
                    BUILD_STATUS = 'FAILURE'
                    build_addinfo = "(Clone App projects)!!!"
                    failure_mail_body = String.format(failure_mail_body, err.toString() + build_addinfo + "%s", email_build_info)
                }
            }
            if (pipelineType.contains("master")) {
                stage("get version") {
                    try {
                        timestamps {
                            if (BUILD_STATUS == "SUCCESS") {
                                script {
                                    version = sh(returnStdout: true, script: """#!/bin/bash
                                        source ${WORKSPACE}/.launchers/libtools/pipeline/app_pipeline.lib && get_version ${VERBOSE} ${build_workspace} ${prebuilt_app_workspace}""")
                                    version = version.trim()
                                    if (version > '90' && FORCE_BUILD == "false") {
                                        currentBuild.result = 'ABORTED'
                                        commonlib.__INFO("${GERRIT_PROJECT} is disabled to build as current build number is higher than 90 to save buildnumber for version. To reran job select FORCE_BUILD option==> SKIP!!!")
                                        currentBuild.description = "SKIPPED: ${GERRIT_PROJECT}"
                                    } else {
                                        sh(script: """#!/bin/bash
                                        echo ${version} > ${build_workspace}/version.txt
                                        echo ${branch_identifier} > ${build_workspace}/branch_identifier.txt""")
                                        echo "App version for this current build: ${version}"
                                    }
                                }
                            }
                        }
                    } catch (err) {
                        commonlib.__INFO(err.toString())
                        currentBuild.result = 'FAILURE'
                        BUILD_STATUS = 'FAILURE'
                        build_addinfo = "(Get version)"
                        failure_mail_body = String.format(failure_mail_body, err.toString() + build_addinfo + "%s", email_build_info)
                    } /* catch */
                }
            }
            stage("App build") {
                try {
                    timestamps {
                        if (BUILD_STATUS == "SUCCESS") {
                            script {
                                echo "node name:" + buildBot
                                echo "Application build is starting for : ${GERRIT_PROJECT}"
                                if (pipelineType.contains("verify")) {
                                    sh(returnStdout: true, script: """#!/bin/bash
                                        source ${WORKSPACE}/.launchers/libtools/pipeline/app_pipeline.lib && source ${dev_env} && app_build ${VERBOSE} ${build_workspace} ${dev_env} ${PIPELINE_TYPE} """)
                                } else if (pipelineType.contains("master")) {
                                    sh(returnStdout: true, script: """#!/bin/bash
                                        source ${WORKSPACE}/.launchers/libtools/pipeline/app_pipeline.lib && source ${dev_env} && app_build ${VERBOSE} ${build_workspace} ${dev_env} ${PIPELINE_TYPE} ${version} "${branch_identifier}" """)
                                }
                            }
                        }
                    }
                } catch (err) {
                    commonlib.__INFO(err.toString())
                    currentBuild.result = 'FAILURE'
                    BUILD_STATUS = 'FAILURE'
                    build_addinfo = "(build)"
                    failure_mail_body = String.format(failure_mail_body, err.toString() + build_addinfo + "%s", email_build_info)
                } /* catch */
            }
            stage("Testing") {
                try {
                    testing_disable = project_config_json[GERRIT_PROJECT]["testing_disable"]
                    if (testing_disable == 'true') {
                        echo "Skip testing stage as testing is disable for ${GERRIT_PROJECT}"
                        TESTING_DISABLE = true
                    }
                    if (TESTING_DISABLE == "false") {
                        timestamps {
                            if (BUILD_STATUS == "SUCCESS") {
                                echo "Test job has been started: ${test_job}"
                                parameter_list = [
                                        "FLASH_DEVICE=" + "true",
                                        "BUILDBOT_WORKSPACE=" + buildBot + ":" + build_workspace,
                                        "JAVA_HOST_TEST=" + "true",
                                        "JAVA_TARGET_TEST=" + "true",
                                        "MAT=" + "true",
                                        "VERBOSE=" + VERBOSE
                                ]
                                launched_jobs = commonlib.eb_build(job: test_job, wait: true, propagate: false, timeout: test_job_timeout, parameters: parameter_list)
                                if (launched_jobs.EXCEPT) {
                                    throw launched_jobs.EXCEPT
                                }
                                def failure_mail_body_builder = ""
                                if (launched_jobs.RESULT != "SUCCESS") {
                                    commonlib.__INFO("Error: An error occured during testing, marking build as FAILURE.")
                                    currentBuild.result = "FAILURE"
                                    TEST_STATUS = 'FAILURE'
                                    failure_mail_body_builder = launched_jobs.BUILD_URL + ":" + TEST_STATUS + "\n\t"
                                    email_recipients = email_recipients + email_testing_team
                                    // email testing team only if testing fails.
                                }
                                failure_mail_body = String.format(failure_mail_body, failure_mail_body_builder + "%s", email_build_info)
                            }
                        }
                    }
                } catch (err) {
                    TEST_STATUS = 'FAILURE'
                    commonlib.__INFO(err.toString())
                    currentBuild.result = "FAILURE"
                    failure_mail_body = String.format(failure_mail_body, err.toString() + "%s", email_build_info)
                }
            }
            if (pipelineType.contains("verify")) {
                stage("Review Feedback") {

                    try {
                        timestamps {
                            if (BUILD_STATUS == 'FAILURE') {
                                verify_message = verify_message + "\n\t [  ] BUILD: FAILED " + build_addinfo
                                verify_score = -1
                            } else {
                                verify_message = verify_message + "\n\t [OK] BUILD: PASSED"
                                if (TEST_STATUS == 'FAILURE') {
                                    verify_message = verify_message + "\n\t [  ] TEST: FAILED "
                                    if (TESTING_DISABLE == "false") {
                                        verify_score = -1
                                    }
                                } else {
                                    verify_message = verify_message + "\n\t [OK] TEST: PASSED"
                                }
                            }
                            verify_message = verify_message + "\n\n * Build Log: ${env.BUILD_URL}consoleFull"

                            // the depends-on changes shouldn't be given -1 when verify fails.
                            // But, if verify passes, +1 should be given. This is to avoid overwriting a previous verified +1 score
                            // on the dependent changes if at all they were verified independently.
                            if (verify_score > 0 && dependencies != "") {
                                // Join all the changes, # separated. This goes as an input to the promotion job, which calls a couple of python scripts that validates, and submit all changes, atomically.
                                changelist = dependencies.split(" ").join("%23")
                                parameter_list = [
                                        "CHANGELIST="+                changelist
                                ]
                                launched_jobs = [:]
                                launched_jobs["ReviewFeedback"] = commonlib.eb_build( job: bundle_promotion_job,
                                        timeout: 1800, wait: true, propagate: true, parameters: parameter_list )
                                if (launched_jobs["ReviewFeedback"].EXCEPT) {
                                    throw launched_jobs[build_target].EXCEPT
                                }
                                if (dependencies.contains(",false")) {
                                    verify_message = "\n\n * Bundled changes: \n\tThis is a bundle-verify. One or more of the commits in Relation Chain are based on outdated patchset." + \
                        "\n" + "This makes submitting all changes to fail sometimes. So please go through the list of changes below and " + \
                        "\n" + "those changes numbers beside which you see the string 'patchset-out-of-date' are based on an outdated patchset. Fix them by rebasing, and then run verify again." + \
                        "\n" + " This is the reason why your change has been given a Verified -1.\n\n" + configRuntime.verify_message
                                    verify_score = -1
                                } else {
                                    verify_message =  "\n\n * Bundled changes: \n\t Get Code-Review +2 for " + dependencies.replace(",true", "") + \
                        " then go to " + launched_jobs["ReviewFeedback"].BUILD_URL + "promotion/ and click on Execute Promotion. This will submit all bundled changes, provided they have right score.\n\n" + \
                        verify_message
                                    // Verified +1 will be given by bundle promotion job!
                                }
                                commonlib.gerrit_SetReview(GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER, verify_message.toString(), verify_score)
                            } else {
                                // There is only one change - i.e not a bundle. So, give it a verified +1 and let the devs submit it whenever they want.
                                commonlib.gerrit_SetReview(GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER, verify_message.toString(), verify_score)
                            }
                        } /* timestamp */
                    } catch (err) {
                        commonlib.__INFO(err.toString())
                        currentBuild.result = 'FAILURE'
                        build_addinfo = "(Review Feedback)!!!"
                        failure_mail_body = String.format(failure_mail_body, err.toString() + build_addinfo + "%s", email_build_info)
                    } /* catch */

                }
            }
            if (pipelineType.contains("master")) {
                if (BUILD_STATUS == "SUCCESS" && TEST_STATUS == "SUCCESS") {
                    stage("Publish") {
                        try {
                            timestamps {
                                deploy_to_artifactory = project_config_json[GERRIT_PROJECT]["deploy_to_artifactory"]
                                depoy_to_documentation = project_config_json[GERRIT_PROJECT]["depoy_to_documentation"]
                                publish_app = project_config_json[GERRIT_PROJECT]["publish_app"]
                                echo "publish to binary repo"
                                sh(returnStdout: true, script: """#!/bin/bash
                                            source ${WORKSPACE}/.launchers/libtools/pipeline/app_pipeline.lib && publish_to_git ${VERBOSE} ${app_workspace} ${prebuilt_app_workspace} ${GERRIT_BRANCH} \"${publish_app}\" """)
                                if (deploy_to_artifactory == 'true') {
                                  sh(returnStdout: true, script: """#!/bin/bash
                                              source ${WORKSPACE}/.launchers/libtools/pipeline/app_pipeline.lib && publish_to_artifactory ${VERBOSE} ${dev_env} ${PROJECT} ${build_workspace} ${version} """)
                                }
                                /*if(depoy_to_documentation) {
                                   sh(returnStdout: true, script: """#!/bin/bash
                                               source ${WORKSPACE}/.launchers/libtools/pipeline/app_pipeline.lib && source ${dev_env} && publish_to_documentation ${build_workspace} ${dev_env} "${gradle_prop_path}" """)
                                }*/
                            }
                        } catch (err) {
                            commonlib.__INFO(err.toString())
                            currentBuild.result = 'FAILURE'
                            PUBLISH_STATUS = 'FAILURE'
                            build_addinfo = "(Publish)!!!"
                            failure_mail_body = String.format(failure_mail_body, err.toString() + build_addinfo + "%s", email_build_info)
                        } /* catch */
                        if (PUBLISH_STATUS == "SUCCESS") {
                            try {
                                //check and run prebuilt configuration to preinstalled the new app into snapshot
                                aosp_preinstalled_app = project_config_json[GERRIT_PROJECT]["aosp_preinstalled_app"]
                                aosp_project = project_config_json.aosp_project
                                aosp_prebuilt_make_file = project_config_json.aosp_prebuilt_make_file
                                template_make_file = project_config_json.template_make_file
                                if (aosp_preinstalled_app) {
                                    sh(returnStdout: true, script: """#!/bin/bash
                                    cp ${WORKSPACE}/.launchers/libtools/pipeline/template_Android.mk ${build_workspace}
                                    source ${WORKSPACE}/.launchers/libtools/pipeline/app_pipeline.lib && aosp_preinstalled_configuration ${VERBOSE} ${prebuilt_app_workspace} ${GERRIT_BRANCH} \"${aosp_project}\" \"${aosp_preinstalled_app}\" ${aosp_prebuilt_make_file} ${template_make_file} \"${gerrit_reviewer_third_patry_app}\" """)
                                }
                            } catch (err) {
                                commonlib.__INFO(err.toString())
                                currentBuild.result = 'FAILURE'
                                PUBLISH_STATUS = 'FAILURE'
                                build_addinfo = "(prebuilt app configuration)!!!"
                                failure_mail_body = String.format(failure_mail_body, err.toString() + build_addinfo + "%s", email_build_info)
                            } /* catch */
                        }
                    }
                }
            }
        }
    }
}

node(leastloadednode){
    if (BUILD_STATUS == 'FAILURE'){
        currentBuild.description = build_description + "B:-1" + build_addinfo + " T:0"
    } else if (TEST_STATUS == "FAILURE"){
        currentBuild.description = build_description + "B:+1 T:-1"
    } else if (PUBLISH_STATUS == "FAILURE") {
        currentBuild.description = build_description + "B:+1 T:+1 P:-1"
    } else {
        if (currentBuild.result == "ABORTED") {
            currentBuild.description = build_description + " (ABORTED/SKIPPED)"
        } else {
            NOTIFICATION_FAILURE = false
            currentBuild.description = build_description + "B:+1 T:+1 P:+1"
        }
    }

    if (NOTIFICATION_FAILURE) {
        dir(build_workspace) {
            sh """#!/bin/bash -x
                rm -rf *_${BUILD_NUMBER}_buildlog.txt ${BUILD_NUMBER}_testlog.txt
            """
            def build_log = "${GERRIT_PROJECT}_${BUILD_NUMBER}_buildlog.txt".replace("/", "_")
            email_recipients = email_recipients.replaceAll(",null", "").trim()
            commonlib.fetchJenkinsLog(BUILD_URL, build_log)
            if (BUILD_STATUS == 'FAILURE') {
                attachmentlist = "**/*_${BUILD_NUMBER}/*_${BUILD_NUMBER}.output"
            } else {
                if (TEST_STATUS == "FAILURE") {
                    test_log = "${BUILD_NUMBER}_testlog.txt"
                    commonlib.fetchJenkinsLog(launched_jobs.BUILD_URL, test_log)
                    attachmentlist = "${test_log}"
                }
            }
            def subject = currentBuild.result + ": ${JOB_NAME}#${BUILD_NUMBER}"
            def check_abort_msg = sh(returnStdout: true, script: """#!/bin/bash -x
                grep -Eh \"Aborted\\s+by\" ${build_log} || true""").trim()
            failure_mail_body = String.format(failure_mail_body, ": " + check_abort_msg).trim()

            emailext(attachmentsPattern: attachmentlist,
                    body: failure_mail_body,
                    to: email_recipients,
                    from: default_email_sender,
                    subject: subject)
        }
    }
    if(pipelineType.contains("verify")){
        stage("CleanUp WS") {
            timestamps {
                dir(build_workspace){
                    deleteDir()
                }
                dir("${build_workspace}@tmp"){
                    deleteDir()
                }
            }
        }
    }
}
