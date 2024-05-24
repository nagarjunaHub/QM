import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder

def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config
  def configRuntime = body.script.configRuntime
  configRuntime.force_publish = false

  node(configRuntime.leastloadednode) {
    timestamps {
      dir "${configRuntime.workspace}/.launchers", {
        git branch: "${body.script.buildtools_branch}",
                url: "${body.script.buildtools_url}"
      }
    }
    try {
      if (configRuntime.pipelineType.contains("verify")) {
        sh(returnStdout: true, script: """#!/bin/bash -e
                              source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && app_ws_worker ${VERBOSE} ${configRuntime.pipelineType} ${config.dev_env} ${configRuntime.build_workspace} \
                                                                                                        ${config.repo_manifest_url} ${GERRIT_BRANCH} ${configRuntime.repo_manifest_xml} \
                                                                                                        ${GERRIT_HOST} ${GERRIT_PROJECT} ${GERRIT_CHANGE_NUMBER} \
                                                                                                        ${GERRIT_PATCHSET_NUMBER} \"${configRuntime.dependencies}\"  """)
        if (configRuntime.gradle_url_validation == "true") {
          if (fileExists("${configRuntime.app_workspace}/gradlew")) {
            def  validateUrlExitStatus = sh(returnStatus: true, script: """#!/bin/bash -e
                              source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && validate_distribution_url ${VERBOSE} ${configRuntime.app_workspace} ${config.gradle_distributionsUrl}""")
            if (validateUrlExitStatus != 0) {
              throw new Exception("validate gradle distribution url failed")
            }
          } else {
            println "gradlew not found in ${configRuntime.app_workspace}. Skipping validation."
          }
        }
      } else if (configRuntime.pipelineType.contains("master")) {
        sh(returnStdout: true, script: """#!/bin/bash -e
                              source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && app_ws_worker ${VERBOSE} ${configRuntime.pipelineType} ${config.dev_env} ${configRuntime.build_workspace} \
                                                                                                       ${config.repo_manifest_url} ${GERRIT_BRANCH} ${configRuntime.repo_manifest_xml}  """)
        if (configRuntime.deploy_to_artifactory != "true") {
          code_change = sh(returnStdout: true, script: """#!/bin/bash -e
                              source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && code_change ${VERBOSE} ${configRuntime.build_workspace} ${configRuntime.prebuilt_app_workspace}""")
          if(code_change.isEmpty()){
            code_change = sh(returnStdout: true, script: """#!/bin/bash -e
                                                            if [ ! -f ${configRuntime.prebuilt_app_workspace}/manifest.xml ]; then
                                                              echo "prebuilt repo is empty"
                                                            fi """).trim()
          }
        } else {
          code_change = "code change for lib will be detected while uploading the lib"
        }
        sourcecode_avail = sh(returnStdout: true, script: """#!/bin/bash
                                                        if [ -f ${configRuntime.app_workspace}/build.gradle ]; then
                                                          echo "true"
                                                        else
                                                          echo "false"
                                                        fi """).trim()
        println "code_available: " + sourcecode_avail
        println "code_change: " + code_change
        configRuntime.force_publish = code_change ? true : false
        if ( FORCE_RUN == "true" ){
          echo "Build has started with FORCE_RUN flag enabled"
        } else {
          if(sourcecode_avail.toString() == "false") {
            currentBuild.result = 'ABORTED'
            commonlib.__INFO("No source code in ${GERRIT_PROJECT} ==> SKIP THE BUILD!!!")
            configRuntime.build_description = "No Change in ${configRuntime.project}"
            return
          } else if (code_change.isEmpty()) {
            currentBuild.result = 'ABORTED'
            commonlib.__INFO("No new change in ${GERRIT_PROJECT} ==> SKIP THE BUILD!!!")
            configRuntime.build_description = "No Change in ${configRuntime.project}"
            return
          } else {
            echo "Build will start as either there is new change in Code found or the project is lib project"
          }
        }
        configRuntime.buildPerDay = sh(returnStdout: true, script: """#!/bin/bash
                                                                  source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && get_buildPerDay ${VERBOSE} ${configRuntime.build_workspace} ${config.dev_env} ${configRuntime.prebuilt_app_workspace}""")
      }
    } catch(err) {
      commonlib.__INFO(err.toString())
      currentBuild.result = 'FAILURE'
      configRuntime.BUILD_STATUS = 'FAILURE'
      configRuntime.build_addinfo = "(Create App WS)!!!"
      configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(), err.toString() + configRuntime.build_addinfo + "%s", configRuntime.email_build_info)
    }
  }
}
