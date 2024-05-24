import hudson.model.*
import groovy.json.JsonSlurperClassic

// For pipeline common config
def commonlib
def pipeline_node = "Apps_BuildBot"
def version
def buildInfo, artifactory_url
def prebuilt_app_workspace
def default_email_sender, email_recipients

def download_latest_artifact = { artifactId, pattern, server ->
    def downloadMetadata = """{
        "files": [
        {
            "pattern": "$pattern",
            "target": "download/",
            "flat": "true"
        }
        ]
    }"""
    server.download spec: downloadMetadata
    buildInfo.env.capture = true
}


node(pipeline_node) {
    def appsList
    def new_change = true
    cleanWs()
    timestamps {
        echo "Checking out ${buildtools_url}.."
        checkout([$class: 'GitSCM', branches: [[name: "${buildtools_branch}"]], userRemoteConfigs: [[url: "${buildtools_url}"]], extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${WORKSPACE}/.launchers"]]])
        echo "Loading and generating bash libraries.."
        commonlib = load("${WORKSPACE}/.launchers/pipeline/common.groovy")
        app_pipelinelib_bash_functions = commonlib.app_pipelinelib_bash_functions()

        commonlib.generate_app_bash_functions("${env.NODE_NAME}", WORKSPACE, buildtools_branch, buildtools_url, app_pipelinelib_bash_functions)

        if ("${PROJECT_CONFIG}" != "") {
            commonlib.__INFO("Using PROJECT_CONFIG from the Jenkins multi-line text field.")
            project_config = "${PROJECT_CONFIG}".toString()
        } else {
            commonlib.__INFO("Using PROJECT_CONFIG from the Jenkins PROJECT_CONFIG_FILE.")
            project_config = readFile("${WORKSPACE}/.launchers/pipeline/project_config/${PROJECT_CONFIG_FILE}")
        }
        project_config_json = commonlib.parseJsonToMap(project_config)
        def GERRIT_BRANCH = project_config_json.project_line + "_" + project_config_json.android_version + "_" + project_config_json.branch_identifier
        //define artifactory server variable
        default_email_sender = project_config_json.default_email_sender
        email_recipients = project_config_json.email_recipients
        artifactory_url = project_config_json.artifactory_url
        artifactory_repo = project_config_json.artifactory_third_party_repo
        sony_prebuilt_repo = project_config_json.sony_prebuilt_repo
        gerrit_reviewer_third_patry_app = project_config_json.gerrit_reviewer_third_patry_app
        def server = Artifactory.newServer url: "${artifactory_url}", credentialsId: 'jenkins_eb_artifactory'
        buildInfo = Artifactory.newBuildInfo()

        sony_app_list = project_config_json.sony_app_list
        //cloning sony prebuilt repository to update the version of sony apps
        prebuilt_app_workspace = "${WORKSPACE}/sony_prebuilts"
        dir(prebuilt_app_workspace) {
            git branch: GERRIT_BRANCH, url: sony_prebuilt_repo
        }
        stage("download sony apps") {
            sony_app_list.split(' ').each { app ->
                println "Download latest version of Sony app: " + app
                artifactId = "sony"
                artifactory_path = artifactId + "/" + app

                if(VERSION){
                    version = VERSION
                } else {
                    withCredentials([usernameColonPassword(credentialsId: 'jenkins_eb_artifactory', variable: 'USERPASS')]) {
                        version = sh(returnStdout: true, script: """#!/bin/bash
                                        source ${WORKSPACE}/.launchers/libtools/common.lib && get_latest_version_from_artifactory \"${USERPASS}\" ${artifactory_url} ${artifactory_repo} ${artifactory_path} """).trim()
                    }
                }

                if(version){
                    println "Latest version on the artifactory for ${app} is: " + version
                    pattern = "${artifactory_repo}/${artifactId}/${app}/${version}/*.apk"
                    download_latest_artifact(artifactId, pattern, server, buildInfo)

                    dir('download') {
                        appsList = sh(returnStdout: true, script: """#!/bin/bash -e
                        echo \$(ls *.apk) | tee apk.list""").trim()
                        println 'Sony Applications found on artifactory: ' + appsList
                    }
                    dir(prebuilt_app_workspace) {
                        appsList.split(' ').each { apk ->
                            app = apk.split('\\.').first().replace("-debug","").replace("-release","")
                            println "creating version file and copying the apk of " + apk + ":" + app
                            sh """echo ${version} > ${app}.version.txt"""
                            sh """cp ../download/${apk} ."""
                            new_change = sh(returnStdout: true, script: """if git status --porcelain |grep .; then echo "true"; else echo "false"; fi""")
                        }
                    }
                } else {
                    println "----------------- For " + app + " valid version not found --------------------"
                }
            }
        }

        if(new_change) {
            stage('Integrate sony apps') {

                aosp_project = project_config_json.aosp_project
                aosp_prebuilt_make_file = project_config_json.aosp_prebuilt_make_file
                template_make_file = project_config_json.template_make_file

                aosp_preinstalled_app = appsList
                app_workspace = "${WORKSPACE}/download"
                direct_push = false
                commit_msg = "integrate sony app:" + appsList
                stage("Prebuilt app configuration") {
                    sh(script: """#!/bin/bash
                                  cp ${WORKSPACE}/.launchers/libtools/pipeline/template_Android.mk ${WORKSPACE}
                                  # check for new app and if detected then create it's Android.mk file and also include the app into aosp-project
				  echo "aosp_preinstalled_configuration---------------------------"
                                  source ${WORKSPACE}/.launchers/libtools/pipeline/app_pipeline.lib && aosp_preinstalled_configuration ${VERBOSE} ${prebuilt_app_workspace} ${GERRIT_BRANCH} \"${aosp_project}\" \"${aosp_preinstalled_app}\" ${aosp_prebuilt_make_file} ${template_make_file} \"${gerrit_reviewer_third_patry_app}\"
                                  echo "git push all the app with version file-----------------------------"
                                  source ${WORKSPACE}/.launchers/libtools/pipeline/app_pipeline.lib && git_push ${prebuilt_app_workspace} ${GERRIT_BRANCH} \"${commit_msg}\" "${direct_push}" \"${gerrit_reviewer_third_patry_app}\"
                  """)
                }
            }
        } else {
            println "SKIP to integrate => sony apps as new version has not been found"
        }
    }
}

if(currentBuild.result == 'FAILURE') {
    def subject = currentBuild.result + ": ${JOB_NAME}#${BUILD_NUMBER}"
    attachmentlist = ""
    def failure_mail_body = "JOB ${BUILD_URL} has been " + currentBuild.result + "\nPlease check the console log for more details"
    emailext(attachmentsPattern: attachmentlist,
            body: failure_mail_body,
            to: email_recipients,
            from: default_email_sender,
            subject: subject)
}