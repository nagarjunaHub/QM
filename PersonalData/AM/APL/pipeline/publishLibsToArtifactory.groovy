import hudson.model.*
import groovy.json.JsonSlurperClassic

// For pipeline common config
def commonlib
def pipeline_node = BUILDBOT
def latest_version, VERSION
def upload = "false"
def buildInfo, artifactory_url, artifactory_repo, dev_env

// For Release/Feedback info config
def default_email_sender, email_recipients
def email_build_info = ""
def failure_mail_body = """JOB URL: ${BUILD_URL}
ERROR: %s
INFO: %s
"""


def upload_latest_artifact = { pattern, target, server ->
    buildInfo = Artifactory.newBuildInfo()
    buildInfo.name = SNAPSHOT_VERSION
    buildInfo.number = SSD_PATH.split('/').last()

    def uploadSpec = """{
        "files": [
        {
            "pattern": "$pattern",
            "target": "$target",
            "flat": "true"
        }
        ]
    }"""
    server.upload spec: uploadSpec, failNoOp: true, buildInfo: buildInfo
}

def download_latest_artifact = { artifactId, pattern, server ->
    def downloadMetadata = """{
        "files": [
        {
            "pattern": "$pattern/maven-metadata.xml",
            "target": "temp_$artifactId/",
            "flat": "true"
        }
        ]
    }"""
    server.download spec: downloadMetadata

    latest_version = sh(returnStdout: true, script: """#!/bin/bash -x
    if [ -f ${WORKSPACE}/temp_${artifactId}/maven-metadata.xml ]; then
      echo `cat ${WORKSPACE}/temp_${artifactId}/maven-metadata.xml | grep latest | awk -F'<latest>' '{print \$2}' | awk -F'</latest>' '{print \$1}' `
    else
      echo "null"
    fi""").trim()

    if ( latest_version != "null" ) {
        pattern = pattern + "/" + latest_version + "/"
        downloadLatestVersion = """{
            "files": [
            {
                "pattern": "$pattern",
                "target": "temp_$artifactId/",
                "flat": "true"
            }
            ]
        }"""
        echo "-------------------- Download Latest version of ${artifactId}: ${latest_version} --------------------"
        server.download spec: downloadLatestVersion, failNoOp: true
    } else {
        latest_version = null
    }
    return latest_version
}


node(pipeline_node) {
    cleanWs()
    timestamps {
        try {
            // Checkout build tools into workspsace
            checkout([$class: 'GitSCM', branches: [[name: "${buildtools_branch}"]], userRemoteConfigs: [[url: "${buildtools_url}"]], extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${WORKSPACE}/.launchers"]]])
            echo "Loading and generating bash libraries.."
            commonlib = load("${WORKSPACE}/.launchers/pipeline/common.groovy")

            if ("${PROJECT_CONFIG}" != "") {
                commonlib.__INFO("Using PROJECT_CONFIG from the Jenkins multi-line text field.")
                project_config = "${PROJECT_CONFIG}".toString()
            } else {
                commonlib.__INFO("Using PROJECT_CONFIG from the Jenkins PROJECT_CONFIG_FILE.")
                project_config = readFile("${WORKSPACE}/.launchers/pipeline/project_config/${PROJECT_CONFIG_FILE}")
            }

            project_config_json = commonlib.parseJsonToMap(project_config)
            default_email_sender = project_config_json.default_email_sender
            email_recipients = project_config_json.email_recipients
            artifactory_url = project_config_json.artifactory_url
            artifactory_repo = project_config_json.artifactory_repo
            dev_env = project_config_json.dev_env

            //define artifactory server variable
            def server = Artifactory.newServer url: "${artifactory_url}", credentialsId: 'jenkins_eb_artifactory'

            project_config_json.artifacts.each {
                def artifactId = it.artifactId
                def groupId = it.groupId
                def pattern = artifactory_repo + "/" + groupId + "/" + artifactId

                commonlib.__INFO("Download latest version: ${artifactId}")
                latest_version = download_latest_artifact(artifactId, pattern, server)
                echo "latest version for ${artifactId}: ${latest_version}"
                VERSION = (latest_version == null) ? "1.0.0" : latest_version.substring(0, latest_version.lastIndexOf(".")) + "." + (latest_version.split('\\.').last().toInteger() + 1).toString()
                echo "new version for ${artifactId} will be: ${VERSION}"
                def POM_FILE_PATH = "${WORKSPACE}/${artifactId}-${VERSION}.pom"
                def POM_UPLOAD_PATH = "${artifactory_repo}/${groupId}/${artifactId}/${VERSION}/${artifactId}-${VERSION}.pom"

                commonlib.__INFO("Compare latest artifactory artifacts with the new snapshot artifacts for ${artifactId}")
                it.files.each {
                    file_type = it.file_type
                    if (file_type == "jar" || file_type == "aar") {
                        new_input_file = SSD_PATH + "/" + it.input_file
                        latest_input_file = "${WORKSPACE}/temp_${artifactId}/${artifactId}-${latest_version}.${file_type}"
                        commonlib.__INFO("${new_input_file}: ${latest_input_file}")
                        groupId_pom = groupId.replace('/', '.')
                        output = sh(returnStdout: true, script: """#!/bin/bash
                                                               source ${dev_env} &>/dev/null 
                                                               if [ -f ${latest_input_file} ]; then
                                                                   python ${WORKSPACE}/.launchers/libtools/pipeline/jardiff.py ${new_input_file} ${latest_input_file} 
                                                               else
                                                                   echo "First time upload of the ${new_input_file}"
                                                                   cp ${WORKSPACE}/.launchers/libtools/pipeline/template_pom.xml ${POM_FILE_PATH}
                                                                   sed -i "s/__groupId_pom__/${groupId_pom}/g" ${POM_FILE_PATH}
                                                                   sed -i "s/__artifactId_pom__/${artifactId}/g" ${POM_FILE_PATH}
                                                                   sed -i "s/__version__/${VERSION}/g" ${POM_FILE_PATH}
                                                                   sed -i "s/__file_type__/${file_type}/g" ${POM_FILE_PATH}
                                                               fi""")
                        if (output != "") {
                            echo "There is a difference in ${it.input_file}"
                            upload = "true"
                        } else {
                            echo "There is no difference in ${it.input_file}"
                        }
                    }
                }
                if (upload == "true") {
                    it.files.each {
                        file_type = it.file_type
                        FILE_TO_UPLOAD = SSD_PATH + "/" + it.input_file
                        ARTIFACTORY_UPLOAD_PATH = "${artifactory_repo}/${groupId}/${artifactId}/${VERSION}/${artifactId}-${VERSION}.${file_type}"
                        if (file_type == "zip") {
                            sh """#!/bin/bash
                               pushd \$(dirname ${FILE_TO_UPLOAD}) &>/dev/null
                               zip -xzvf ${WORKSPACE}/temp_${artifactId}.zip \$(basename ${FILE_TO_UPLOAD})/*
                               popd &>/dev/null
                            """
                            FILE_TO_UPLOAD = "${WORKSPACE}/temp_${artifactId}.zip"
                        }
                        //upload pom file in case of there is jar or aar file
                        else if (file_type == "jar" || file_type == "aar") {
                            //update the version of the old pom file

                            sh """#!/bin/bash -x
                                  if [ -f ${latest_input_file} ]; then
                                    cp ${WORKSPACE}/temp_${artifactId}/${artifactId}-${latest_version}.pom ${POM_FILE_PATH} && sed -i "s/${latest_version}/${VERSION}/g" ${POM_FILE_PATH} 
                                  fi"""
                            upload_latest_artifact(POM_FILE_PATH, POM_UPLOAD_PATH, server)
                        }
                        commonlib.__INFO("Upload ${VERSION} of ${artifactId} to artifactory")
                        upload_latest_artifact(FILE_TO_UPLOAD, ARTIFACTORY_UPLOAD_PATH, server)
                    }
                } else {
                    commonlib.__INFO("No change in ${artifactId} => SKIP uploading ${artifactId}")
                }
            }
        } catch (err) {
            commonlib.__INFO(err.toString())
            currentBuild.result = 'FAILURE'
            build_addinfo = "(Deploy AOSP libs to Artifactory failed)!!!"
            failure_mail_body = String.format(failure_mail_body, err.toString() + build_addinfo + "%s", email_build_info)
            subject = currentBuild.result+": ${JOB_NAME}#${BUILD_NUMBER}"

            emailext(body: failure_mail_body,
                    to: email_recipients,
                    from: default_email_sender,
                    subject: subject)
        } /* catch */
    }
}